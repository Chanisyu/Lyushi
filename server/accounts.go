package main

import (
	"crypto/pbkdf2"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// User 仅包含可公开的账户属性；密码派生值不进入 API、导出文件或审计正文。
type User struct {
	ID          string `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
	Enabled     bool   `json:"enabled"`
	MustChange  bool   `json:"mustChangePassword"`
	Deleted     bool   `json:"deleted"`
}

// Catalog 将账户认证与独立用户数据库关联。gate 跨越授权和业务提交，防止撤销竞态。
type Catalog struct {
	gate       sync.Mutex
	stores     map[string]*Service
	path       string
	loginSlots chan struct{}
	rateMu     sync.Mutex
	attempts   map[string]loginAttempt
	cleaned    int64
}
type loginAttempt struct {
	Count int
	Until int64
}

var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_.-]{2,31}$`)
var accountIDPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// 升级只增加账户目录；旧单用户数据不会自动获得用户归属或被普通设备访问。
func (s *Service) initCatalog(path string) error {
	s.catalog = &Catalog{path: path, stores: map[string]*Service{}, loginSlots: make(chan struct{}, 2), attempts: map[string]loginAttempt{}}
	for _, statement := range []string{
		`CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,display_name TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('admin','user')),enabled INTEGER NOT NULL DEFAULT 1,must_change INTEGER NOT NULL DEFAULT 1,deleted INTEGER NOT NULL DEFAULT 0,salt TEXT NOT NULL,password_hash TEXT NOT NULL,auth_version INTEGER NOT NULL DEFAULT 1)`,
		`CREATE UNIQUE INDEX IF NOT EXISTS sole_admin ON users(role) WHERE role='admin'`,
		`CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,csrf TEXT NOT NULL,expires INTEGER NOT NULL,auth_version INTEGER NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id)`,
		`CREATE TABLE IF NOT EXISTS audit(seq INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,actor TEXT NOT NULL,target_user TEXT NOT NULL,action TEXT NOT NULL,object_id TEXT NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS audit_time ON audit(at)`,
	} {
		if _, err := s.db.Exec(statement); err != nil {
			return err
		}
	}
	// 旧设备表缺少归属字段时显式增加；空归属旧密钥始终认证失败。
	rows, err := s.db.Query(`PRAGMA table_info(devices)`)
	if err != nil {
		return err
	}
	found := map[string]bool{}
	for rows.Next() {
		var cid, notnull, pk int
		var name, kind string
		var def any
		if err = rows.Scan(&cid, &name, &kind, &notnull, &def, &pk); err != nil {
			rows.Close()
			return err
		}
		found[name] = true
	}
	rows.Close()
	for name, statement := range map[string]string{"user_id": `ALTER TABLE devices ADD COLUMN user_id TEXT NOT NULL DEFAULT ''`, "last_sync": `ALTER TABLE devices ADD COLUMN last_sync INTEGER NOT NULL DEFAULT 0`} {
		if !found[name] {
			if _, err = s.db.Exec(statement); err != nil {
				return err
			}
		}
	}
	_, err = s.db.Exec(`CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id)`)
	return err
}

// PBKDF2-SHA256 使用随机盐和 600000 次迭代；同时限制密码字节数及并发验证数量。
func derivePassword(password, salt string) (string, error) {
	if utf8.RuneCountInString(password) < 12 || len(password) > 256 {
		return "", errors.New("密码至少 12 个字符，且不超过 256 字节")
	}
	raw, err := hex.DecodeString(salt)
	if err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, raw, 600000, 32)
	return hex.EncodeToString(key), err
}
func passwordMatches(password, salt, expected string) bool {
	actual, err := derivePassword(password, salt)
	return err == nil && subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) == 1
}

// 用户编号由服务器生成，并直接决定独立数据库文件名；绝不接受浏览器提供的文件路径。
func (s *Service) accountPath(id string) (string, error) {
	if !accountIDPattern.MatchString(id) {
		return "", errors.New("无效账户编号")
	}
	return filepath.Join(filepath.Dir(s.catalog.path), "accounts", id+".sqlite"), nil
}
func (s *Service) userStore(id string) (*Service, error) {
	if store := s.catalog.stores[id]; store != nil {
		return store, nil
	}
	path, err := s.accountPath(id)
	if err != nil {
		return nil, err
	}
	store, err := openService(path)
	if err != nil {
		return nil, err
	}
	s.catalog.stores[id] = store
	return store, nil
}
func (s *Service) user(id string) (User, error) {
	var u User
	err := s.db.QueryRow(`SELECT id,username,display_name,role,enabled,must_change,deleted FROM users WHERE id=?`, id).Scan(&u.ID, &u.Username, &u.DisplayName, &u.Role, &u.Enabled, &u.MustChange, &u.Deleted)
	return u, err
}
func (s *Service) userByName(name string) (User, error) {
	var id string
	err := s.db.QueryRow(`SELECT id FROM users WHERE username=?`, strings.ToLower(strings.TrimSpace(name))).Scan(&id)
	if err != nil {
		return User{}, err
	}
	return s.user(id)
}

// 仅初始化命令可创建唯一管理员；网页创建操作固定为普通用户，不接受角色提升字段。
func (s *Service) createUser(username, display, role, password string) (User, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	display = strings.TrimSpace(display)
	if !usernamePattern.MatchString(username) || !text(display, 1, 200) || (role != "admin" && role != "user") {
		return User{}, errors.New("用户名须为 3—32 位小写字母、数字、点、下划线或连字符；名称不超过 200 字")
	}
	salt := randomID()
	hash, err := derivePassword(password, salt)
	if err != nil {
		return User{}, err
	}
	id := randomID()
	if _, err = s.db.Exec(`INSERT INTO users(id,username,display_name,role,salt,password_hash) VALUES(?,?,?,?,?,?)`, id, username, display, role, salt, hash); err != nil {
		return User{}, errors.New("用户名已存在或管理员已初始化")
	}
	return s.user(id)
}

// 密码重置、停用和停用后启用均不复活旧会话及设备；设备必须明确重新创建。
func (s *Service) invalidate(tx *sql.Tx, id string) error {
	if _, err := tx.Exec(`DELETE FROM sessions WHERE user_id=?`, id); err != nil {
		return err
	}
	_, err := tx.Exec(`UPDATE devices SET revoked=1 WHERE user_id=?`, id)
	return err
}
func (s *Service) setPassword(id, password string, temporary bool) error {
	salt := randomID()
	hash, err := derivePassword(password, salt)
	if err != nil {
		return err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`UPDATE users SET salt=?,password_hash=?,must_change=?,auth_version=auth_version+1 WHERE id=?`, salt, hash, temporary, id); err != nil {
		return err
	}
	if _, err = tx.Exec(`DELETE FROM sessions WHERE user_id=?`, id); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Service) audit(actor, target, action, object string) error {
	_, err := s.db.Exec(`INSERT INTO audit(at,actor,target_user,action,object_id) VALUES(?,?,?,?,?)`, time.Now().UnixMilli(), actor, target, action, object)
	return err
}

// 同一用户最多拥有十个有效设备；密钥仅在此返回一次，数据库只保存摘要。
func (s *Service) createDevice(userID, label string) (map[string]any, error) {
	u, err := s.user(userID)
	if err != nil || !u.Enabled || u.Deleted {
		return nil, errors.New("账户不可用")
	}
	if !text(label, 1, 200) {
		return nil, errors.New("设备名称须为 1—200 字")
	}
	var count int
	if err = s.db.QueryRow(`SELECT COUNT(*) FROM devices WHERE user_id=? AND revoked=0`, userID).Scan(&count); err != nil {
		return nil, err
	}
	if count >= 10 {
		return nil, errors.New("每个账户最多十台有效设备")
	}
	key, id := randomID(), randomID()
	hash := sha256.Sum256([]byte(key))
	if _, err = s.db.Exec(`INSERT INTO devices(id,label,token_hash,user_id) VALUES(?,?,?,?)`, id, strings.TrimSpace(label), hex.EncodeToString(hash[:]), userID); err != nil {
		return nil, err
	}
	return map[string]any{"id": id, "label": label, "key": key}, nil
}

// 停用先提交认证撤销，清理文件失败时保留可重试的删除状态，不再允许同步。
func (s *Service) deleteUser(actor string, u User) error {
	if u.Role == "admin" {
		return errors.New("不能删除管理员")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`UPDATE users SET enabled=0,deleted=1,auth_version=auth_version+1 WHERE id=?`, u.ID); err != nil {
		return err
	}
	if err = s.invalidate(tx, u.ID); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	if store := s.catalog.stores[u.ID]; store != nil {
		store.close()
		delete(s.catalog.stores, u.ID)
	}
	path, err := s.accountPath(u.ID)
	if err != nil {
		return err
	}
	for _, suffix := range []string{"-wal", "-shm", ""} {
		if err = os.Remove(path + suffix); err != nil && !os.IsNotExist(err) {
			return errors.New("账户已停用，数据文件清理失败，请重试")
		}
	}
	tx, err = s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`DELETE FROM devices WHERE user_id=?`, u.ID); err != nil {
		return err
	}
	if _, err = tx.Exec(`DELETE FROM users WHERE id=?`, u.ID); err != nil {
		return err
	}
	if _, err = tx.Exec(`INSERT INTO audit(at,actor,target_user,action,object_id) VALUES(?,?,?,?,?)`, time.Now().UnixMilli(), actor, u.ID, "delete_user", u.Username); err != nil {
		return err
	}
	return tx.Commit()
}

// 清空仅适用于尚未初始化账户的旧单用户库；不提供对已运行多用户目录的批量清空入口。
func (s *Service) clearLegacy() error {
	var count int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&count); err != nil {
		return err
	}
	if count != 0 {
		return errors.New("已存在用户，拒绝清空")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, table := range []string{"heads", "versions", "changes", "devices"} {
		if _, err = tx.Exec("DELETE FROM " + table); err != nil {
			return err
		}
	}
	s.id = randomID()
	if _, err = tx.Exec(`UPDATE meta SET value=? WHERE key='serverId'`, s.id); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	_, err = s.db.Exec(`VACUUM`)
	return err
}

// 关闭缓存的用户连接后再关闭目录库，避免账户删除或测试留下 WAL 句柄。
func (s *Service) close() {
	if s.catalog != nil {
		for _, store := range s.catalog.stores {
			store.close()
		}
	}
	s.db.Close()
}

// 管理输出文件必须原先不存在，权限为 0600，任何错误均不把秘密写入标准输出。
func secretFile(path, value string) error {
	if path == "" {
		return errors.New("必须指定秘密输出文件")
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, err = f.WriteString(value)
	if err == nil {
		err = f.Sync()
	}
	closed := f.Close()
	if err == nil {
		err = closed
	}
	if err != nil {
		os.Remove(path)
	}
	return err
}
func (s *Service) accountIdentity(u User, device, label string) (map[string]any, error) {
	store, err := s.userStore(u.ID)
	if err != nil {
		return nil, err
	}
	return map[string]any{"userId": u.ID, "username": u.Username, "displayName": u.DisplayName, "serverId": store.id, "deviceId": device, "deviceLabel": label, "protocol": 4}, nil
}

// 维护命令与网页使用相同的目录方法，错误仅返回上下文而不回显密码或密钥。
func catalogError(err error) error {
	if err != nil {
		return fmt.Errorf("账户操作失败：%w", err)
	}
	return nil
}
