// 柳时同步服务：单用户、多设备，使用不可变操作和版本分支保留并发内容。
// 仅由现有 HTTPS 反向代理对外提供服务，不记录请求正文或设备密钥。
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"

	_ "modernc.org/sqlite"
)

// Operation 的编号也是新版本编号；同一编号的请求必须永远具有相同内容。
type Operation struct {
	ID       string         `json:"id"`
	Kind     string         `json:"kind"`
	EntityID string         `json:"entityId"`
	Parents  []string       `json:"parents"`
	Entity   map[string]any `json:"entity"`
}
type Request struct {
	ServerID   string      `json:"serverId"`
	Cursor     int64       `json:"cursor"`
	Operations []Operation `json:"operations"`
}
type Change struct {
	Seq   int64            `json:"seq"`
	Kind  string           `json:"kind"`
	ID    string           `json:"id"`
	Heads []map[string]any `json:"heads"`
}
type Response struct {
	ServerID string   `json:"serverId"`
	Cursor   int64    `json:"cursor"`
	Ack      []string `json:"ack"`
	Changes  []Change `json:"changes"`
	More     bool     `json:"more"`
}
type Service struct {
	db *sql.DB
	id string
}

// 随机编号不包含设备名或用户内容，密钥使用 256 位随机数。
func randomID() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// 单连接串行事务确保版本头、操作去重和变更游标同步提交。SQLite 保留 WAL 与完整同步。
func openService(path string) (*Service, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	fail := func(err error) (*Service, error) { db.Close(); return nil, err }
	for _, statement := range []string{
		"PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL", "PRAGMA busy_timeout=5000",
		"CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,label TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,revoked INTEGER NOT NULL DEFAULT 0)",
		"CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,operation TEXT NOT NULL,device TEXT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS heads(kind TEXT NOT NULL,entity_id TEXT NOT NULL,version TEXT NOT NULL,PRIMARY KEY(kind,entity_id,version))",
		"CREATE TABLE IF NOT EXISTS changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,entity_id TEXT NOT NULL,payload TEXT NOT NULL)",
	} {
		if _, err := db.Exec(statement); err != nil {
			return fail(err)
		}
	}
	if _, err := db.Exec("INSERT OR IGNORE INTO meta(key,value) VALUES('serverId',?)", randomID()); err != nil {
		return fail(err)
	}
	service := &Service{db: db}
	if err := db.QueryRow("SELECT value FROM meta WHERE key='serverId'").Scan(&service.id); err != nil {
		return fail(err)
	}
	if err := os.Chmod(path, 0600); err != nil {
		return fail(err)
	}
	return service, nil
}

// 校验 JSON 类型与整数范围，避免字符串或浮点数被解释成合法时间。
func number(value any) (int64, bool) {
	n, ok := value.(json.Number)
	if !ok {
		return 0, false
	}
	v, e := n.Int64()
	return v, e == nil
}
func text(value any, min, max int) bool {
	s, ok := value.(string)
	return ok && len(strings.TrimSpace(s)) >= min && len(utf16.Encode([]rune(s))) <= max
}
func validate(op Operation) error {
	e := op.Entity
	if !text(op.ID, 1, 128) || !text(op.EntityID, 1, 128) || e == nil || e["id"] != op.EntityID || e["version"] != op.ID || len(op.Parents) > 128 {
		return errors.New("invalid operation identity")
	}
	if _, ok := e["deleted"].(bool); !ok {
		return errors.New("invalid deleted flag")
	}
	if revision, ok := number(e["revision"]); !ok || revision < 1 || revision > 9007199254740991 {
		return errors.New("invalid revision")
	}
	allowed := map[string]bool{"id": true, "deleted": true, "revision": true, "version": true}
	switch op.Kind {
	case "ledger", "tag":
		allowed["name"] = true
		if !text(e["name"], 1, 200) {
			return errors.New("invalid ledger name")
		}
	case "record":
		for _, key := range []string{"task", "ledgerId", "started", "ended", "tag"} {
			allowed[key] = true
		}
		if !text(e["task"], 1, 10000) || !text(e["ledgerId"], 1, 128) {
			return errors.New("invalid record")
		}
		// 标签是任务发生时的名称快照；删除候选标签不会改写历史任务。
		if tag, exists := e["tag"]; exists && !text(tag, 1, 200) {
			return errors.New("invalid record tag")
		}
		start, ok := number(e["started"])
		if !ok || start < 0 || start >= 253402271999000 {
			return errors.New("invalid start")
		}
		end, exists := e["ended"]
		if !exists {
			return errors.New("missing end")
		}
		if end != nil {
			n, ok := number(end)
			if !ok || n < start || n >= 253402271999000 {
				return errors.New("invalid end")
			}
		}
	case "archive":
		allowed["content"] = true
		content, ok := e["content"].(map[string]any)
		if !ok {
			return errors.New("invalid archive")
		}
		kind, ok := content["kind"].(string)
		if !ok || (kind != "ledger" && kind != "record" && kind != "tag") {
			return errors.New("invalid archive kind")
		}
		original, ok := content["entity"].(map[string]any)
		if !ok {
			return errors.New("invalid archived entity")
		}
		// 历史实体可能没有版本字段；如果存在，必须具有与普通实体相同的体积界限。
		if value, exists := original["version"]; exists && !text(value, 1, 128) {
			return errors.New("invalid archived version")
		}
		for field, value := range content {
			switch field {
			case "kind", "entity":
			case "archived":
				if _, ok := value.(bool); !ok {
					return errors.New("invalid archive flag")
				}
			case "reason":
				if !text(value, 1, 200) {
					return errors.New("invalid archive reason")
				}
			default:
				return errors.New("unknown archive field")
			}
		}
		copy := map[string]any{}
		for key, value := range original {
			copy[key] = value
		}
		copy["version"] = op.ID
		entityID, ok := copy["id"].(string)
		if !ok {
			return errors.New("invalid archive identity")
		}
		if err := validate(Operation{ID: op.ID, Kind: kind, EntityID: entityID, Parents: []string{}, Entity: copy}); err != nil {
			return err
		}
	default:
		return errors.New("invalid kind")
	}
	for key := range e {
		if !allowed[key] {
			return errors.New("unknown entity field")
		}
	}
	seen := map[string]bool{}
	for _, parent := range op.Parents {
		if !text(parent, 1, 128) || parent == op.ID || seen[parent] {
			return errors.New("invalid parent")
		}
		seen[parent] = true
	}
	return nil
}

// 对版本头拍摄完整快照，离线客户端按递增游标恢复，设备时钟不参与覆盖取舍。
func readHeads(tx *sql.Tx, kind, id string) ([]map[string]any, error) {
	rows, err := tx.Query("SELECT v.operation FROM heads h JOIN versions v ON v.id=h.version WHERE h.kind=? AND h.entity_id=? ORDER BY h.version", kind, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []map[string]any{}
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return nil, err
		}
		var operation Operation
		decoder := json.NewDecoder(strings.NewReader(raw))
		decoder.UseNumber()
		if err = decoder.Decode(&operation); err != nil {
			return nil, err
		}
		result = append(result, operation.Entity)
	}
	return result, rows.Err()
}

// 备份恢复可能带来相同业务内容的另一个版本编号，按内容等价去重但保留操作历史。
func businessContent(entity map[string]any) string {
	copy := map[string]any{}
	for key, value := range entity {
		if key != "version" && key != "revision" {
			copy[key] = value
		}
	}
	raw, _ := json.Marshal(copy)
	return string(raw)
}

// 批次全有或全无。重复上传读出原操作核对；分支只替换其明确声明的父版本。
func (s *Service) sync(ctx context.Context, device string, request Request) (Response, error) {
	result := Response{ServerID: s.id, Cursor: request.Cursor, Ack: []string{}, Changes: []Change{}}
	if request.Cursor < 0 || len(request.Operations) > 50 || (request.ServerID != "" && request.ServerID != s.id) || (request.ServerID == "" && request.Cursor != 0) {
		return result, errors.New("server_identity_or_cursor")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var maximum int64
	if err = tx.QueryRow("SELECT COALESCE(MAX(seq),0) FROM changes").Scan(&maximum); err != nil {
		return result, err
	}
	if request.Cursor > maximum {
		return result, errors.New("server_identity_or_cursor")
	}
	for _, op := range request.Operations {
		if err = validate(op); err != nil {
			return result, err
		}
		raw, _ := json.Marshal(op)
		var existing string
		err = tx.QueryRow("SELECT operation FROM versions WHERE id=?", op.ID).Scan(&existing)
		if err == nil {
			if existing != string(raw) {
				return result, errors.New("operation_identity_reused")
			}
			result.Ack = append(result.Ack, op.ID)
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return result, err
		}
		replacedContent := map[string]bool{businessContent(op.Entity): true}
		for _, parent := range op.Parents {
			var kind, id string
			if err = tx.QueryRow("SELECT kind,entity_id FROM versions WHERE id=?", parent).Scan(&kind, &id); err != nil || kind != op.Kind || id != op.EntityID {
				return result, errors.New("unknown_or_mismatched_parent")
			}
			var parentRaw string
			if err = tx.QueryRow("SELECT operation FROM versions WHERE id=?", parent).Scan(&parentRaw); err != nil {
				return result, err
			}
			var parentOp Operation
			decoder := json.NewDecoder(strings.NewReader(parentRaw))
			decoder.UseNumber()
			if err = decoder.Decode(&parentOp); err != nil {
				return result, err
			}
			replacedContent[businessContent(parentOp.Entity)] = true
		}
		if op.Kind == "record" {
			var count int
			if err = tx.QueryRow("SELECT COUNT(*) FROM heads WHERE kind='ledger' AND entity_id=?", op.Entity["ledgerId"]).Scan(&count); err != nil {
				return result, err
			}
			if count == 0 {
				return result, errors.New("missing_ledger")
			}
		}
		if _, err = tx.Exec("INSERT INTO versions(id,kind,entity_id,operation,device) VALUES(?,?,?,?,?)", op.ID, op.Kind, op.EntityID, string(raw), device); err != nil {
			return result, err
		}
		previousHeads, err := readHeads(tx, op.Kind, op.EntityID)
		if err != nil {
			return result, err
		}
		for _, head := range previousHeads {
			if replacedContent[businessContent(head)] {
				if _, err = tx.Exec("DELETE FROM heads WHERE kind=? AND entity_id=? AND version=?", op.Kind, op.EntityID, head["version"]); err != nil {
					return result, err
				}
			}
		}
		for _, parent := range op.Parents {
			if _, err = tx.Exec("DELETE FROM heads WHERE kind=? AND entity_id=? AND version=?", op.Kind, op.EntityID, parent); err != nil {
				return result, err
			}
		}
		if _, err = tx.Exec("INSERT INTO heads(kind,entity_id,version) VALUES(?,?,?)", op.Kind, op.EntityID, op.ID); err != nil {
			return result, err
		}
		heads, err := readHeads(tx, op.Kind, op.EntityID)
		if err != nil {
			return result, err
		}
		if len(heads) > 128 {
			return result, errors.New("too_many_branches")
		}
		payload, _ := json.Marshal(heads)
		if _, err = tx.Exec("INSERT INTO changes(kind,entity_id,payload) VALUES(?,?,?)", op.Kind, op.EntityID, string(payload)); err != nil {
			return result, err
		}
		result.Ack = append(result.Ack, op.ID)
	}
	rows, err := tx.Query("SELECT seq,kind,entity_id,payload FROM changes WHERE seq>? ORDER BY seq LIMIT 201", request.Cursor)
	if err != nil {
		return result, err
	}
	pageBytes := 0
	for rows.Next() {
		var change Change
		var raw string
		if err = rows.Scan(&change.Seq, &change.Kind, &change.ID, &raw); err != nil {
			rows.Close()
			return result, err
		}
		if len(result.Changes) == 200 || (pageBytes+len(raw) > 2*1024*1024 && len(result.Changes) > 0) {
			result.More = true
			break
		}
		pageBytes += len(raw)
		decoder := json.NewDecoder(strings.NewReader(raw))
		decoder.UseNumber()
		if err = decoder.Decode(&change.Heads); err != nil {
			rows.Close()
			return result, err
		}
		result.Changes = append(result.Changes, change)
		result.Cursor = change.Seq
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

// 公网只接受设备密钥。错误响应不回显数据库路径、请求内容或任何认证信息。
func (s *Service) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"status":"ok","protocol":2,"minimumClient":"0.4.0"}`)
	})
	slots := make(chan struct{}, 8)
	mux.HandleFunc("POST /v1/sync", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			http.Error(w, `{"error":"busy"}`, 503)
			return
		}
		key := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if len(key) != 64 || !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
			http.Error(w, `{"error":"unauthorized"}`, 401)
			return
		}
		hash := sha256.Sum256([]byte(key))
		var device string
		if err := s.db.QueryRowContext(r.Context(), "SELECT id FROM devices WHERE token_hash=? AND revoked=0", hex.EncodeToString(hash[:])).Scan(&device); err != nil {
			http.Error(w, `{"error":"unauthorized"}`, 401)
			return
		}
		// 旧客户端不能识别标签实体和任务标签字段；在写入事务前拒绝以避免元数据丢失。
		if r.Header.Get("X-Lyushi-Protocol") != "2" {
			http.Error(w, `{"error":"client_upgrade_required","minimumClient":"0.4.0"}`, http.StatusUpgradeRequired)
			return
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2*1024*1024))
		decoder.UseNumber()
		decoder.DisallowUnknownFields()
		var request Request
		if err := decoder.Decode(&request); err != nil {
			http.Error(w, `{"error":"invalid_request"}`, 400)
			return
		}
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			http.Error(w, `{"error":"invalid_request"}`, 400)
			return
		}
		response, err := s.sync(r.Context(), device, request)
		if err != nil {
			http.Error(w, `{"error":"sync_rejected"}`, 409)
			return
		}
		json.NewEncoder(w).Encode(response)
	})
	return mux
}

// 管理命令仅在服务器本机运行，密钥写入权限受限的文件，不输出到日志或进程参数。
func main() {
	os.Exit(run())
}
func run() int {
	flags := flag.NewFlagSet("lyushi-server", flag.ContinueOnError)
	dbPath := flags.String("db", "/var/lib/lyushi/sync.sqlite", "SQLite 数据文件")
	listen := flags.String("listen", "127.0.0.1:8093", "回环监听地址")
	create := flags.String("create-device", "", "创建具名设备")
	keyFile := flags.String("key-file", "", "新设备密钥输出文件，必须尚不存在")
	revoke := flags.String("revoke-device", "", "撤销设备编号")
	backup := flags.String("backup", "", "在线一致性备份输出文件，必须尚不存在")
	if err := flags.Parse(os.Args[1:]); err != nil {
		return 2
	}
	service, err := openService(*dbPath)
	if err != nil {
		log.Print("无法打开同步数据库")
		return 1
	}
	defer service.db.Close()
	if *create != "" {
		if *keyFile == "" {
			log.Print("需要指定密钥输出文件")
			return 2
		}
		file, err := os.OpenFile(*keyFile, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err != nil {
			log.Print("无法创建密钥文件")
			return 1
		}
		key, id := randomID(), randomID()
		hash := sha256.Sum256([]byte(key))
		if _, err = file.WriteString(key); err == nil {
			err = file.Sync()
		}
		closeErr := file.Close()
		if err == nil {
			err = closeErr
		}
		if err != nil {
			os.Remove(*keyFile)
			return 1
		}
		if _, err = service.db.Exec("INSERT INTO devices(id,label,token_hash) VALUES(?,?,?)", id, *create, hex.EncodeToString(hash[:])); err != nil {
			os.Remove(*keyFile)
			log.Print("创建设备失败")
			return 1
		}
		fmt.Println(id)
		return 0
	}
	if *revoke != "" {
		result, err := service.db.Exec("UPDATE devices SET revoked=1 WHERE id=?", *revoke)
		if err != nil {
			return 1
		}
		count, _ := result.RowsAffected()
		if count != 1 {
			return 1
		}
		return 0
	}
	if *backup != "" {
		if _, err := os.Stat(*backup); !os.IsNotExist(err) {
			return 1
		}
		if _, err := service.db.Exec("VACUUM INTO ?", *backup); err != nil {
			log.Print("数据库备份失败")
			return 1
		}
		os.Chmod(*backup, 0600)
		return 0
	}
	if !strings.HasPrefix(*listen, "127.0.0.1:") {
		log.Print("服务仅允许监听本机回环地址")
		return 2
	}
	server := &http.Server{Addr: *listen, Handler: service.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 20 * time.Second, WriteTimeout: 20 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		deadline, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		server.Shutdown(deadline)
	}()
	log.Print("柳时同步服务启动")
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Print("同步监听失败")
		return 1
	}
	return 0
}
