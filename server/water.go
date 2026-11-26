package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"time"
)

const maxBottleML = 10000

// 饮水日志独立于时间账本。物理操作以水壶版本为前置条件，历史更正以单条记录版本为前置条件。
type waterCommand struct {
	ID       string `json:"id"`
	Kind     string `json:"kind"`
	At       int64  `json:"at"`
	Base     string `json:"base,omitempty"`
	Target   string `json:"target,omitempty"`
	ML       int    `json:"ml,omitempty"`
	From     int    `json:"from,omitempty"`
	Capacity int    `json:"capacity,omitempty"`
	Entry    string `json:"entry,omitempty"`
	Voided   bool   `json:"voided,omitempty"`
}

type waterUndo struct {
	Capacity int    `json:"capacity"`
	ML       int    `json:"ml"`
	Entry    string `json:"entry,omitempty"`
}

type waterBottle struct {
	Capacity int        `json:"capacity"`
	ML       int        `json:"ml"`
	Version  string     `json:"version"`
	Undo     *waterUndo `json:"undo,omitempty"`
}

type waterEntry struct {
	ID      string `json:"id"`
	Kind    string `json:"kind"`
	At      int64  `json:"at"`
	ML      int    `json:"ml"`
	Version string `json:"version"`
	Voided  bool   `json:"voided"`
}

type waterChange struct {
	Seq      int64        `json:"seq"`
	Command  waterCommand `json:"command"`
	Bottle   *waterBottle `json:"bottle,omitempty"`
	Entry    *waterEntry  `json:"entry,omitempty"`
	Conflict string       `json:"conflict,omitempty"`
	Resolved string       `json:"resolved,omitempty"`
}

type waterRequest struct {
	ServerID   string         `json:"serverId"`
	Cursor     int64          `json:"cursor"`
	Operations []waterCommand `json:"operations"`
}

type waterResponse struct {
	ServerID string           `json:"serverId"`
	Cursor   int64            `json:"cursor"`
	More     bool             `json:"more"`
	Changes  []waterChange    `json:"changes"`
	Ack      map[string]int64 `json:"ack"`
}

var waterID = regexp.MustCompile(`^[a-f0-9-]{32,64}$`)

func waterKind(kind string) bool {
	return kind == "fill" || kind == "drink" || kind == "pour" || kind == "manual"
}
func countsWater(kind string) bool { return kind == "drink" || kind == "manual" }

// 容量与水量按毫升保存。无效请求使整批回滚，合法但版本过期的操作单独登记供核对。
func (c waterCommand) valid() bool {
	if !waterID.MatchString(c.ID) || c.At <= 0 || c.At >= 253402271999000 || c.ML < 0 || c.ML > maxBottleML ||
		c.Capacity < 0 || c.Capacity > maxBottleML || c.From < 0 || c.From > maxBottleML || len(c.Entry) > 16 ||
		(c.Base != "" && !waterID.MatchString(c.Base)) || (c.Target != "" && !waterID.MatchString(c.Target)) {
		return false
	}
	switch c.Kind {
	case "setup":
		return c.Capacity > 0 && c.ML == 0
	case "restore-bottle":
		return c.Capacity > 0 && c.ML <= c.Capacity
	case "capacity":
		return c.Capacity > 0 && c.Base != ""
	case "fill", "drink", "pour":
		return c.Base != "" && ((c.Kind == "fill" && c.ML > c.From) || (c.Kind != "fill" && c.ML < c.From))
	case "undo", "calibrate":
		return c.Base != ""
	case "manual":
		return c.ML > 0
	case "correct", "void":
		return c.Target != "" && c.Base != "" && (c.Kind == "void" || c.ML > 0)
	case "restore-entry":
		return c.Target != "" && c.ML > 0 && waterKind(c.Entry)
	case "dismiss", "recover":
		return c.Target != ""
	default:
		return false
	}
}

// 三张表与账户时间账本共用 SQLite 备份，饮水事务不修改时间记录、游标或待上传操作。
func (s *Service) initWater(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS water_bottle(singleton INTEGER PRIMARY KEY CHECK(singleton=1), payload TEXT NOT NULL);
		CREATE TABLE IF NOT EXISTS water_entries(id TEXT PRIMARY KEY, at INTEGER NOT NULL, ml INTEGER NOT NULL, kind TEXT NOT NULL, voided INTEGER NOT NULL, payload TEXT NOT NULL);
		CREATE INDEX IF NOT EXISTS water_entries_date ON water_entries(at DESC,id);
		CREATE TABLE IF NOT EXISTS water_log(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, command TEXT NOT NULL, change TEXT NOT NULL, conflict INTEGER NOT NULL, resolved INTEGER NOT NULL DEFAULT 0);`)
	return err
}

func readWater[T any](row *sql.Row) (T, error) {
	var value T
	var data string
	err := row.Scan(&data)
	if errors.Is(err, sql.ErrNoRows) {
		return value, nil
	}
	if err != nil {
		return value, err
	}
	return value, json.Unmarshal([]byte(data), &value)
}

// 持久化当前投影和原因只需查询被操作的条目，增量同步不扫描全部历史。
func resolveWater(tx *sql.Tx, c waterCommand) (waterChange, error) {
	change := waterChange{Command: c}
	conflict := func(reason string) (waterChange, error) { change.Conflict = reason; return change, nil }
	switch c.Kind {
	case "manual":
		change.Entry = &waterEntry{ID: c.ID, Kind: c.Kind, At: c.At, ML: c.ML, Version: c.ID}
	case "correct", "void", "restore-entry":
		entry, err := readWater[waterEntry](tx.QueryRow(`SELECT payload FROM water_entries WHERE id=?`, c.Target))
		if err != nil {
			return change, err
		}
		if c.Kind == "restore-entry" {
			if entry.ID != "" {
				if entry.Kind == c.Entry && entry.ML == c.ML && entry.At == c.At && entry.Voided == c.Voided {
					return change, nil
				}
				return conflict("history_differs")
			}
			change.Entry = &waterEntry{ID: c.Target, Kind: c.Entry, At: c.At, ML: c.ML, Version: c.ID, Voided: c.Voided}
			return change, nil
		}
		if entry.Version != c.Base || entry.Voided || !countsWater(entry.Kind) {
			return conflict("history_changed")
		}
		entry.Version = c.ID
		if c.Kind == "void" {
			entry.Voided = true
		} else {
			entry.ML, entry.At = c.ML, c.At
		}
		change.Entry = &entry
	case "dismiss", "recover":
		previous, err := readWater[waterCommand](tx.QueryRow(`SELECT command FROM water_log WHERE id=? AND conflict=1 AND resolved=0`, c.Target))
		if err != nil {
			return change, err
		}
		if previous.ID == "" {
			return change, nil
		}
		if c.Kind == "recover" {
			if previous.Kind != "drink" {
				return conflict("cannot_recover")
			}
			change.Entry = &waterEntry{ID: c.ID, Kind: "manual", At: previous.At, ML: previous.From - previous.ML, Version: c.ID}
		}
		change.Resolved = previous.ID
	default:
		bottle, err := readWater[waterBottle](tx.QueryRow(`SELECT payload FROM water_bottle WHERE singleton=1`))
		if err != nil {
			return change, err
		}
		if c.Kind == "setup" || c.Kind == "restore-bottle" {
			if bottle.Version != "" {
				return conflict("bottle_exists")
			}
			change.Bottle = &waterBottle{Capacity: c.Capacity, ML: c.ML, Version: c.ID}
			return change, nil
		}
		if bottle.Version == "" || bottle.Version != c.Base {
			return conflict("bottle_changed")
		}
		next := waterBottle{Capacity: bottle.Capacity, ML: bottle.ML, Version: c.ID}
		prior := waterUndo{Capacity: bottle.Capacity, ML: bottle.ML}
		switch c.Kind {
		case "capacity":
			if c.Capacity < bottle.ML {
				return conflict("capacity_too_small")
			}
			if c.Capacity == bottle.Capacity {
				return change, nil
			}
			next.Capacity, next.Undo = c.Capacity, &prior
		case "calibrate":
			if c.ML > bottle.Capacity {
				return conflict("capacity_too_small")
			}
			if c.ML == bottle.ML {
				return change, nil
			}
			next.ML, next.Undo = c.ML, &prior
		case "undo":
			if bottle.Undo == nil {
				return conflict("nothing_to_undo")
			}
			next.Capacity, next.ML = bottle.Undo.Capacity, bottle.Undo.ML
			if bottle.Undo.Entry != "" {
				entry, err := readWater[waterEntry](tx.QueryRow(`SELECT payload FROM water_entries WHERE id=?`, bottle.Undo.Entry))
				if err != nil {
					return change, err
				}
				if entry.ID == "" {
					return change, errors.New("水壶撤销缺少对应明细")
				}
				entry.Voided, entry.Version = true, c.ID
				change.Entry = &entry
			}
		default:
			if bottle.ML != c.From || c.ML > bottle.Capacity {
				return conflict("bottle_changed")
			}
			amount := c.ML - c.From
			if amount < 0 {
				amount = -amount
			}
			prior.Entry = c.ID
			next.ML, next.Undo = c.ML, &prior
			change.Entry = &waterEntry{ID: c.ID, Kind: c.Kind, At: c.At, ML: amount, Version: c.ID}
		}
		change.Bottle = &next
	}
	return change, nil
}

// 同一编号重试返回原日志项；命令、投影与核对状态同一次提交。
func applyWater(tx *sql.Tx, c waterCommand) (waterChange, error) {
	var change waterChange
	if !c.valid() {
		return change, errors.New("饮水操作格式无效")
	}
	command, _ := json.Marshal(c)
	var previous string
	var seq int64
	err := tx.QueryRow(`SELECT seq,command FROM water_log WHERE id=?`, c.ID).Scan(&seq, &previous)
	if err == nil {
		if previous != string(command) {
			return change, errors.New("饮水操作编号与先前内容不符")
		}
		change, err = readWater[waterChange](tx.QueryRow(`SELECT change FROM water_log WHERE id=?`, c.ID))
		change.Seq = seq
		return change, err
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return change, err
	}
	change, err = resolveWater(tx, c)
	if err != nil {
		return change, err
	}
	if change.Bottle != nil {
		raw, _ := json.Marshal(change.Bottle)
		if _, err = tx.Exec(`INSERT OR REPLACE INTO water_bottle VALUES(1,?)`, string(raw)); err != nil {
			return change, err
		}
	}
	if e := change.Entry; e != nil {
		raw, _ := json.Marshal(e)
		if _, err = tx.Exec(`INSERT OR REPLACE INTO water_entries VALUES(?,?,?,?,?,?)`, e.ID, e.At, e.ML, e.Kind, e.Voided, string(raw)); err != nil {
			return change, err
		}
	}
	if change.Resolved != "" {
		if _, err = tx.Exec(`UPDATE water_log SET resolved=1 WHERE id=?`, change.Resolved); err != nil {
			return change, err
		}
	}
	raw, _ := json.Marshal(change)
	result, err := tx.Exec(`INSERT INTO water_log(id,command,change,conflict) VALUES(?,?,?,?)`, c.ID, string(command), string(raw), change.Conflict != "")
	if err != nil {
		return change, err
	}
	change.Seq, err = result.LastInsertId()
	return change, err
}

func (s *Service) syncWater(ctx context.Context, request waterRequest) (waterResponse, error) {
	response := waterResponse{ServerID: s.id, Cursor: request.Cursor, Changes: []waterChange{}, Ack: map[string]int64{}}
	if request.ServerID != s.id || request.Cursor < 0 || len(request.Operations) > 50 {
		return response, errors.New("饮水同步身份或批次无效")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return response, err
	}
	defer tx.Rollback()
	var last int64
	if err = tx.QueryRow(`SELECT COALESCE(MAX(seq),0) FROM water_log`).Scan(&last); err != nil {
		return response, err
	}
	if request.Cursor > last {
		return response, errors.New("饮水游标超过服务器历史")
	}
	for _, command := range request.Operations {
		change, applyErr := applyWater(tx, command)
		if applyErr != nil {
			return response, applyErr
		}
		response.Ack[command.ID] = change.Seq
	}
	rows, err := tx.Query(`SELECT seq,change FROM water_log WHERE seq>? ORDER BY seq LIMIT 251`, request.Cursor)
	if err != nil {
		return response, err
	}
	defer rows.Close()
	for rows.Next() {
		if len(response.Changes) == 250 {
			response.More = true
			break
		}
		var seq int64
		var raw string
		if err = rows.Scan(&seq, &raw); err != nil {
			return response, err
		}
		var change waterChange
		if err = json.Unmarshal([]byte(raw), &change); err != nil {
			return response, err
		}
		change.Seq = seq
		response.Cursor = seq
		response.Changes = append(response.Changes, change)
	}
	if err = rows.Err(); err != nil {
		return response, err
	}
	if err = rows.Close(); err != nil {
		return response, err
	}
	return response, tx.Commit()
}

// 设备认证、停用状态和业务提交共用原账户锁。饮水路由无法选择任意账户。
func (s *Service) waterDevice(w http.ResponseWriter, r *http.Request) {
	s.catalog.gate.Lock()
	defer s.catalog.gate.Unlock()
	u, _, _, err := s.device(r)
	if err != nil {
		apiError(w, 401, "unauthorized")
		return
	}
	if r.Header.Get("X-Lyushi-Water") != "1" {
		apiError(w, 426, "water_upgrade_required")
		return
	}
	var request waterRequest
	if err = readJSON(w, r, &request, 128*1024); err != nil {
		apiError(w, 400, "invalid_request")
		return
	}
	store, err := s.userStore(u.ID)
	if err != nil {
		apiError(w, 500, "账户存储不可用")
		return
	}
	response, err := store.syncWater(r.Context(), request)
	if err != nil {
		apiError(w, 409, "饮水批次未提交，请核对本机账户及服务器身份")
		return
	}
	jsonReply(w, 200, response)
}

// 网页按北京时间查询一日明细及准确累计，分页不影响统计。
func (s *Service) waterWebHistory(w http.ResponseWriter, r *http.Request, target User) {
	store, err := s.userStore(target.ID)
	if err != nil {
		apiError(w, 500, "饮水存储不可用")
		return
	}
	zone := time.FixedZone("Beijing", 8*3600)
	day := r.URL.Query().Get("day")
	if day == "" {
		day = time.Now().In(zone).Format("2006-01-02")
	}
	start, err := time.ParseInLocation("2006-01-02", day, zone)
	if err != nil {
		apiError(w, 400, "日期无效")
		return
	}
	end := start.AddDate(0, 0, 1).UnixMilli()
	var count, total int
	if err = store.db.QueryRow(`SELECT COUNT(*),COALESCE(SUM(CASE WHEN kind IN ('drink','manual') AND voided=0 THEN ml ELSE 0 END),0) FROM water_entries WHERE at>=? AND at<?`, start.UnixMilli(), end).Scan(&count, &total); err != nil {
		apiError(w, 500, "无法读取饮水统计")
		return
	}
	bottle, err := readWater[waterBottle](store.db.QueryRow(`SELECT payload FROM water_bottle WHERE singleton=1`))
	if err != nil {
		apiError(w, 500, "无法读取水壶")
		return
	}
	rows, err := store.db.Query(`SELECT payload FROM water_entries WHERE at>=? AND at<? ORDER BY at DESC,id LIMIT 50 OFFSET ?`, start.UnixMilli(), end, pageNumber(r)*50)
	if err != nil {
		apiError(w, 500, "无法读取饮水明细")
		return
	}
	defer rows.Close()
	items := []waterEntry{}
	for rows.Next() {
		var raw string
		var e waterEntry
		if rows.Scan(&raw) != nil || json.Unmarshal([]byte(raw), &e) != nil {
			apiError(w, 500, "无法读取饮水明细")
			return
		}
		items = append(items, e)
	}
	if rows.Err() != nil {
		apiError(w, 500, "无法读取饮水明细")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	jsonReply(w, 200, map[string]any{"user": target, "day": day, "total": count, "drank": total, "bottle": bottle, "items": items})
}

// 网页只更正历史，不能构造物理命令。会话与目标账户已由 consoleAPI 验证。
func (s *Service) waterWebAction(r *http.Request, actor, target User, command WebCommand) error {
	if command.Water == nil {
		return errors.New("缺少饮水操作")
	}
	c := *command.Water
	if c.Kind != "manual" && c.Kind != "correct" && c.Kind != "void" {
		return errors.New("网页仅支持饮水补记、更正与作废")
	}
	store, err := s.userStore(target.ID)
	if err != nil {
		return err
	}
	tx, err := store.db.BeginTx(r.Context(), nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	change, err := applyWater(tx, c)
	if err != nil {
		return err
	}
	if change.Conflict != "" {
		return errors.New("饮水历史已变化，请刷新后重新操作")
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	object := c.Target
	if object == "" {
		object = c.ID
	}
	if err = s.audit(actor.ID, target.ID, "water_"+c.Kind, object); err != nil {
		return fmt.Errorf("饮水记录已保存，审计暂不可用")
	}
	return nil
}
