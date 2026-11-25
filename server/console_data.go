package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

// WebCommand 使用明确字段，拒绝未知权限或数据库路径参数；目标账户另行执行权限检查。
type WebCommand struct {
	Action       string         `json:"action"`
	UserID       string         `json:"userId"`
	ID           string         `json:"id"`
	Kind         string         `json:"kind"`
	Expected     string         `json:"expectedVersion"`
	Name         string         `json:"name"`
	Label        string         `json:"label"`
	Username     string         `json:"username"`
	DisplayName  string         `json:"displayName"`
	Confirmation string         `json:"confirmation"`
	OldPassword  string         `json:"oldPassword"`
	Password     string         `json:"password"`
	Enabled      *bool          `json:"enabled"`
	Entity       map[string]any `json:"entity"`
	Backup       map[string]any `json:"backup"`
}

// 只读列表确定一个展示分支，其他分支仍可在冲突页查看，绝不按时间戳强行覆盖。
func (s *Service) current(kind string, deleted bool) ([]map[string]any, error) {
	rows, err := s.db.Query(`SELECT v.operation FROM heads h JOIN versions v ON v.id=h.version WHERE h.kind=? ORDER BY h.entity_id,h.version`, kind)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	chosen := map[string]map[string]any{}
	priority := func(e map[string]any) int {
		p := 0
		if e["deleted"] == false {
			p += 4
		}
		if kind == "tag" && e["deleted"] == true {
			p += 16
		}
		if kind == "record" && e["ended"] == nil {
			p += 8
		}
		return p
	}
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return nil, err
		}
		var op Operation
		d := json.NewDecoder(strings.NewReader(raw))
		d.UseNumber()
		if err = d.Decode(&op); err != nil {
			return nil, err
		}
		old := chosen[op.EntityID]
		if old == nil || priority(op.Entity) > priority(old) {
			chosen[op.EntityID] = op.Entity
		}
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	result := []map[string]any{}
	ids := []string{}
	for id := range chosen {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		if deleted || chosen[id]["deleted"] == false {
			result = append(result, chosen[id])
		}
	}
	return result, nil
}
func (s *Service) headsFor(kind, id string) ([]map[string]any, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	return readHeads(tx, kind, id)
}
func (s *Service) hasActive(ledger string) (bool, error) {
	rows, err := s.db.Query(`SELECT v.operation FROM heads h JOIN versions v ON v.id=h.version WHERE h.kind='record'`)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return false, err
		}
		var op Operation
		if err = json.Unmarshal([]byte(raw), &op); err != nil {
			return false, err
		}
		if op.Entity["deleted"] == false && op.Entity["ended"] == nil && (ledger == "" || op.Entity["ledgerId"] == ledger) {
			return true, nil
		}
	}
	return false, rows.Err()
}

// 业务修改复用同步操作：校验所见版本、拒绝编辑活动任务，明确删除时覆盖全部已知分支。
func (s *Service) prepare(kind string, e map[string]any, expected string, remove bool) (Operation, error) {
	if kind != "ledger" && kind != "record" && kind != "tag" {
		return Operation{}, errors.New("无效业务类型")
	}
	copy := map[string]any{}
	for k, v := range e {
		copy[k] = v
	}
	id, _ := copy["id"].(string)
	if id == "" {
		id = randomID()
		if kind == "tag" {
			name, _ := copy["name"].(string)
			id = tokenHash(strings.TrimSpace(name))
		}
		copy["id"] = id
	}
	heads, err := s.headsFor(kind, id)
	if err != nil {
		return Operation{}, err
	}
	var base map[string]any
	parents := []string{}
	for _, head := range heads {
		if head["version"] == expected {
			base = head
		}
		if kind == "record" && head["deleted"] == false && head["ended"] == nil {
			return Operation{}, errors.New("进行中的任务只读，请先在客户端结束")
		}
	}
	restoreTag := false
	if kind == "tag" && expected == "" && !remove && len(heads) > 0 {
		restoreTag = true
		for _, head := range heads {
			if head["deleted"] != true {
				return Operation{}, errors.New("标签已存在")
			}
		}
		base = heads[0]
		expected = base["version"].(string)
	}
	if len(heads) > 0 && base == nil {
		return Operation{}, errors.New("内容已更新，请刷新后重新操作")
	}
	if len(heads) == 0 && expected != "" {
		return Operation{}, errors.New("记录已不存在，请刷新")
	}
	if kind == "record" && copy["ended"] == nil {
		return Operation{}, errors.New("网页只能补记或修改已结束任务")
	}
	if kind == "record" && base != nil && base["ledgerId"] != copy["ledgerId"] {
		return Operation{}, errors.New("不支持直接改变任务所属账本")
	}
	if remove || restoreTag {
		for _, head := range heads {
			parents = append(parents, head["version"].(string))
		}
	} else if base != nil {
		parents = append(parents, expected)
	}
	revision := int64(1)
	if base != nil {
		old, _ := number(base["revision"])
		revision = old + 1
	}
	version := randomID()
	copy["revision"] = json.Number(fmt.Sprint(revision))
	copy["version"] = version
	copy["deleted"] = remove
	op := Operation{ID: version, Kind: kind, EntityID: id, Parents: parents, Entity: copy}
	if err = validate(op); err != nil {
		return Operation{}, errors.New("字段、名称或分段时间无效；请检查区间顺序及长度")
	}
	return op, nil
}
func (s *Service) applyWeb(actor string, ops []Operation) error {
	_, err := s.syncBatch(context.Background(), "web:"+actor, Request{ServerID: s.id, Operations: ops}, true)
	return err
}

// 列表按页提供，筛选仅作用于已授权用户的数据库；设备密钥摘要不进入页面。
func (s *Service) webState(w http.ResponseWriter, r *http.Request, u User) {
	store, err := s.userStore(u.ID)
	if err != nil {
		apiError(w, 500, "账户存储不可用")
		return
	}
	kind := r.URL.Query().Get("kind")
	if kind == "" {
		kind = "record"
	}
	if kind != "record" && kind != "ledger" && kind != "tag" && kind != "conflict" {
		apiError(w, 400, "无效列表类型")
		return
	}
	ledgers, err := store.current("ledger", false)
	if err != nil {
		apiError(w, 500, "读取失败")
		return
	}
	tags, err := store.current("tag", false)
	if err != nil {
		apiError(w, 500, "读取失败")
		return
	}
	devices := []map[string]any{}
	rows, err := s.db.Query(`SELECT id,label,revoked,last_sync FROM devices WHERE user_id=? ORDER BY label,id`, u.ID)
	if err != nil {
		apiError(w, 500, "读取设备失败")
		return
	}
	for rows.Next() {
		var id, label string
		var revoked bool
		var last int64
		if err = rows.Scan(&id, &label, &revoked, &last); err != nil {
			break
		}
		devices = append(devices, map[string]any{"id": id, "label": label, "revoked": revoked, "lastSync": last})
	}
	rows.Close()
	if err != nil {
		apiError(w, 500, "读取设备失败")
		return
	}
	items := []map[string]any{}
	if kind == "conflict" {
		items, err = store.conflictContents()
	} else {
		items, err = store.current(kind, false)
	}
	if err != nil {
		apiError(w, 500, "读取列表失败")
		return
	}
	filtered := []map[string]any{}
	query := strings.ToLower(r.URL.Query().Get("q"))
	ledgerID := r.URL.Query().Get("ledgerId")
	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	alive := map[string]bool{}
	for _, book := range ledgers {
		alive[book["id"].(string)] = true
	}
	for _, item := range items {
		if kind == "record" {
			if !alive[item["ledgerId"].(string)] {
				continue
			}
			if ledgerID != "" && item["ledgerId"] != ledgerID {
				continue
			}
			start, _ := number(item["started"])
			date := time.UnixMilli(start).In(time.FixedZone("CST", 8*3600)).Format("2006-01-02")
			if from != "" && date < from || to != "" && date > to {
				continue
			}
		}
		raw, _ := json.Marshal(item)
		if query != "" && !strings.Contains(strings.ToLower(string(raw)), query) {
			continue
		}
		filtered = append(filtered, item)
	}
	if kind == "record" {
		sort.Slice(filtered, func(i, j int) bool {
			a, _ := number(filtered[i]["started"])
			b, _ := number(filtered[j]["started"])
			return a > b
		})
	}
	total := len(filtered)
	start := pageNumber(r) * 50
	if start > total {
		start = total
	}
	end := start + 50
	if end > total {
		end = total
	}
	active, _ := store.hasActive("")
	jsonReply(w, 200, map[string]any{"user": u, "items": filtered[start:end], "total": total, "page": pageNumber(r), "ledgers": ledgers, "tags": tags, "devices": devices, "hasActive": active})
}
func (s *Service) webUsers(w http.ResponseWriter) {
	rows, err := s.db.Query(`SELECT id FROM users ORDER BY role,username`)
	if err != nil {
		apiError(w, 500, "读取用户失败")
		return
	}
	ids := []string{}
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	result := []map[string]any{}
	for _, id := range ids {
		u, err := s.user(id)
		if err != nil {
			continue
		}
		var devices int
		var last int64
		s.db.QueryRow(`SELECT COUNT(*),COALESCE(MAX(last_sync),0) FROM devices WHERE user_id=? AND revoked=0`, id).Scan(&devices, &last)
		books, count := 0, 0
		active := false
		if !u.Deleted {
			store, err := s.userStore(id)
			if err == nil {
				ls, _ := store.current("ledger", false)
				rs, _ := store.current("record", false)
				books = len(ls)
				count = len(rs)
				active, _ = store.hasActive("")
			}
		}
		result = append(result, map[string]any{"user": u, "deviceCount": devices, "lastSync": last, "ledgerCount": books, "recordCount": count, "hasActive": active})
	}
	jsonReply(w, 200, map[string]any{"users": result})
}
func (s *Service) webAudit(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(`SELECT a.seq,a.at,COALESCE(u.username,a.actor),COALESCE(t.username,a.target_user),a.action,a.object_id FROM audit a LEFT JOIN users u ON u.id=a.actor LEFT JOIN users t ON t.id=a.target_user ORDER BY a.seq DESC LIMIT 50 OFFSET ?`, pageNumber(r)*50)
	if err != nil {
		apiError(w, 500, "读取日志失败")
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var seq, at int64
		var actor, target, action, id string
		if rows.Scan(&seq, &at, &actor, &target, &action, &id) != nil {
			break
		}
		items = append(items, map[string]any{"seq": seq, "at": at, "actor": actor, "target": target, "action": action, "objectId": id})
	}
	jsonReply(w, 200, map[string]any{"items": items})
}

// 账户与设备管理操作只修改目录；业务修改在相应用户库产生正常版本，再记录审计索引。
func (s *Service) webAction(actor, target User, c WebCommand, r *http.Request) (any, error) {
	ok := map[string]any{"ok": true}
	admin := actor.Role == "admin"
	switch c.Action {
	case "logout":
		cookie, _ := r.Cookie("__Host-lyushi_session")
		s.db.Exec(`DELETE FROM sessions WHERE token_hash=?`, tokenHash(cookie.Value))
		return ok, nil
	case "password":
		if target.ID != actor.ID {
			return nil, errors.New("只能修改本人密码")
		}
		var salt, hash string
		s.db.QueryRow(`SELECT salt,password_hash FROM users WHERE id=?`, actor.ID).Scan(&salt, &hash)
		if !passwordMatches(c.OldPassword, salt, hash) {
			return nil, errors.New("原密码不正确")
		}
		if c.Password == c.OldPassword {
			return nil, errors.New("新密码不能与原密码相同")
		}
		if err := s.setPassword(actor.ID, c.Password, false); err != nil {
			return nil, err
		}
		s.audit(actor.ID, actor.ID, "change_password", "")
		return ok, nil
	case "create_user":
		if !admin {
			return nil, errors.New("需要管理员权限")
		}
		password := randomID()[:24]
		u, err := s.createUser(c.Username, c.DisplayName, "user", password)
		if err != nil {
			return nil, err
		}
		s.audit(actor.ID, u.ID, "create_user", u.Username)
		return map[string]any{"user": u, "temporaryPassword": password}, nil
	case "reset_password":
		if !admin || target.Role == "admin" {
			return nil, errors.New("管理员密码须通过服务器本地命令重置")
		}
		password := randomID()[:24]
		if err := s.setPassword(target.ID, password, true); err != nil {
			return nil, err
		}
		s.audit(actor.ID, target.ID, "reset_password", "")
		return map[string]any{"temporaryPassword": password}, nil
	case "user_status":
		if !admin || target.Role == "admin" || c.Enabled == nil {
			return nil, errors.New("不能停用管理员或缺少账户状态")
		}
		tx, err := s.db.Begin()
		if err != nil {
			return nil, err
		}
		defer tx.Rollback()
		if _, err = tx.Exec(`UPDATE users SET enabled=?,auth_version=auth_version+1 WHERE id=?`, *c.Enabled, target.ID); err != nil {
			return nil, err
		}
		if err = s.invalidate(tx, target.ID); err != nil {
			return nil, err
		}
		if err = tx.Commit(); err != nil {
			return nil, err
		}
		s.audit(actor.ID, target.ID, "user_status", fmt.Sprint(*c.Enabled))
		return ok, nil
	case "delete_user":
		if !admin || c.Confirmation != target.Username {
			return nil, errors.New("请输入正确的用户名确认删除")
		}
		return ok, s.deleteUser(actor.ID, target)
	case "profile":
		if !text(c.DisplayName, 1, 200) {
			return nil, errors.New("名称须为 1—200 字")
		}
		_, err := s.db.Exec(`UPDATE users SET display_name=? WHERE id=?`, strings.TrimSpace(c.DisplayName), target.ID)
		if err == nil {
			s.audit(actor.ID, target.ID, "profile", "")
		}
		return ok, err
	case "create_device":
		device, err := s.createDevice(target.ID, c.Label)
		if err == nil {
			s.audit(actor.ID, target.ID, "create_device", device["id"].(string))
		}
		return device, err
	case "revoke_device", "rotate_device":
		var id string
		if err := s.db.QueryRow(`SELECT id FROM devices WHERE id=? AND user_id=? AND revoked=0`, c.ID, target.ID).Scan(&id); err != nil {
			return nil, errors.New("设备不存在或已撤销")
		}
		if c.Action == "revoke_device" {
			_, err := s.db.Exec(`UPDATE devices SET revoked=1 WHERE id=?`, id)
			if err == nil {
				s.audit(actor.ID, target.ID, c.Action, id)
			}
			return ok, err
		}
		key := randomID()
		_, err := s.db.Exec(`UPDATE devices SET token_hash=? WHERE id=?`, tokenHash(key), id)
		if err == nil {
			s.audit(actor.ID, target.ID, c.Action, id)
		}
		return map[string]any{"key": key}, err
	}
	if !target.Enabled || target.Deleted {
		return nil, errors.New("请先启用该账户")
	}
	store, err := s.userStore(target.ID)
	if err != nil {
		return nil, err
	}
	switch c.Action {
	case "save":
		if c.Entity == nil {
			return nil, errors.New("缺少业务内容")
		}
		op, err := store.prepare(c.Kind, c.Entity, c.Expected, false)
		if err != nil {
			return nil, err
		}
		if err = store.applyWeb(actor.ID, []Operation{op}); err != nil {
			return nil, errors.New("保存未提交，请刷新后重试")
		}
		s.audit(actor.ID, target.ID, "save_"+c.Kind, op.EntityID)
		return map[string]any{"entity": op.Entity}, nil
	case "delete":
		heads, err := store.headsFor(c.Kind, c.ID)
		if err != nil || len(heads) == 0 {
			return nil, errors.New("对象不存在")
		}
		var entity map[string]any
		for _, head := range heads {
			if head["version"] == c.Expected {
				entity = head
			}
		}
		if entity == nil {
			return nil, errors.New("内容已更新，请刷新")
		}
		name, _ := entity["name"].(string)
		if c.Kind == "record" {
			name, _ = entity["task"].(string)
		}
		if c.Confirmation != name {
			return nil, errors.New("删除确认内容不一致")
		}
		ops := []Operation{}
		if c.Kind == "ledger" {
			active, err := store.hasActive(c.ID)
			if err != nil {
				return nil, err
			}
			if active {
				return nil, errors.New("账本含进行中的任务，请先在客户端结束")
			}
			records, err := store.current("record", false)
			if err != nil {
				return nil, err
			}
			for _, record := range records {
				if record["ledgerId"] == c.ID {
					op, err := store.prepare("record", record, record["version"].(string), true)
					if err != nil {
						return nil, err
					}
					ops = append(ops, op)
				}
			}
		}
		op, err := store.prepare(c.Kind, entity, c.Expected, true)
		if err != nil {
			return nil, err
		}
		ops = append(ops, op)
		if err = store.applyWeb(actor.ID, ops); err != nil {
			return nil, errors.New("删除未提交，请刷新重试")
		}
		s.audit(actor.ID, target.ID, "delete_"+c.Kind, c.ID)
		return ok, nil
	case "import":
		active, err := store.hasActive("")
		if err != nil {
			return nil, err
		}
		if active {
			return nil, errors.New("请先结束该账户正在记录的任务")
		}
		result, err := store.importWeb(actor.ID, c.Backup)
		if err == nil {
			s.audit(actor.ID, target.ID, "import_backup", "")
		}
		return result, err
	default:
		return nil, errors.New("不支持的操作")
	}
}
