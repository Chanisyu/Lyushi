package main

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// 冲突导出同时包含当前并发分支与只读档案；归档中的活动状态不会重新启动计时。
func (s *Service) conflictContents() ([]map[string]any, error) {
	rows, err := s.db.Query(`SELECT h.kind,v.operation,(SELECT COUNT(*) FROM heads x WHERE x.kind=h.kind AND x.entity_id=h.entity_id) FROM heads h JOIN versions v ON v.id=h.version ORDER BY h.kind,h.entity_id,h.version`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []map[string]any{}
	for rows.Next() {
		var kind, raw string
		var count int
		if err = rows.Scan(&kind, &raw, &count); err != nil {
			return nil, err
		}
		var op Operation
		decoder := json.NewDecoder(strings.NewReader(raw))
		decoder.UseNumber()
		if err = decoder.Decode(&op); err != nil {
			return nil, err
		}
		if kind == "archive" {
			content := op.Entity["content"].(map[string]any)
			result = append(result, content)
		} else if count > 1 {
			result = append(result, map[string]any{"kind": kind, "entity": op.Entity, "archived": true})
		}
	}
	return result, rows.Err()
}
func (s *Service) exportWeb() (map[string]any, error) {
	backup := map[string]any{"format": "lyushi.backup", "formatVersion": 3}
	for _, kind := range []string{"ledger", "record", "tag"} {
		items, err := s.current(kind, true)
		if err != nil {
			return nil, err
		}
		key := map[string]string{"ledger": "ledgers", "record": "records", "tag": "tags"}[kind]
		backup[key] = items
	}
	conflicts, err := s.conflictContents()
	backup["conflicts"] = conflicts
	return backup, err
}
func (s *Service) webExport(w http.ResponseWriter, r *http.Request, u User) {
	store, err := s.userStore(u.ID)
	if err != nil {
		apiError(w, 500, "存储不可用")
		return
	}
	active, err := store.hasActive("")
	if err != nil || active {
		apiError(w, 409, "请先结束该账户正在记录的任务再导出")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	format := r.URL.Query().Get("format")
	if format == "csv" {
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="lyushi-records.csv"`)
		books, err := store.current("ledger", false)
		if err != nil {
			apiError(w, 500, "导出失败")
			return
		}
		records, err := store.current("record", false)
		if err != nil {
			apiError(w, 500, "导出失败")
			return
		}
		names := map[string]string{}
		for _, book := range books {
			names[book["id"].(string)] = book["name"].(string)
		}
		fmt.Fprint(w, "\uFEFF")
		writer := csv.NewWriter(w)
		writer.UseCRLF = true
		writer.Write([]string{"账本", "事项", "开始（北京时间）", "结束（北京时间）", "时长（秒）", "记录编号", "标签", "区间序号", "区间类型"})
		date := func(v any) string {
			n, _ := number(v)
			return time.UnixMilli(n).In(time.FixedZone("CST", 8*3600)).Format("2006-01-02 15:04:05.000")
		}
		for _, record := range records {
			book := record["ledgerId"].(string)
			if names[book] == "" || r.URL.Query().Get("ledgerId") != "" && r.URL.Query().Get("ledgerId") != book {
				continue
			}
			parts, ok := record["segments"].([]any)
			if !ok {
				parts = []any{map[string]any{"kind": "focus", "started": record["started"], "ended": record["ended"]}}
			}
			for i, value := range parts {
				part := value.(map[string]any)
				task, _ := record["task"].(string)
				tag, _ := record["tag"].(string)
				kind := "专注"
				if part["kind"] == "distraction" {
					task, _ = part["task"].(string)
					tag, _ = part["tag"].(string)
					kind = "分心"
				}
				start, _ := number(part["started"])
				end, _ := number(part["ended"])
				row := []string{names[book], task, date(part["started"]), date(part["ended"]), fmt.Sprintf("%.3f", float64(end-start)/1000), record["id"].(string), tag, fmt.Sprint(i + 1), kind}
				for j, text := range row {
					if text != "" && strings.ContainsRune("=+-@\t\r", rune(text[0])) {
						row[j] = "'" + text
					}
				}
				writer.Write(row)
			}
		}
		writer.Flush()
		return
	}
	if format != "json" {
		apiError(w, 400, "不支持的导出格式")
		return
	}
	backup, err := store.exportWeb()
	if err != nil {
		apiError(w, 500, "导出失败")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="lyushi-backup.json"`)
	json.NewEncoder(w).Encode(backup)
}

// JSON 导入先校验整份文件，再以同一事务提交。不同内容进入档案，不覆盖当前版本或复活删除项。
func (s *Service) importWeb(actor string, backup map[string]any) (map[string]int, error) {
	if backup == nil || backup["format"] != "lyushi.backup" {
		return nil, errors.New("无效备份格式")
	}
	version, ok := number(backup["formatVersion"])
	if !ok || version < 1 || version > 3 {
		return nil, errors.New("不支持的备份版本")
	}
	ledgerIDs := map[string]bool{}
	seen := map[string]bool{}
	all := []Operation{}
	added, duplicate, conflictCount := 0, 0, 0
	archive := func(kind string, original map[string]any) error {
		content := map[string]any{"kind": kind, "entity": original, "archived": true}
		raw, _ := json.Marshal(content)
		id := tokenHash(string(raw))
		heads, err := s.headsFor("archive", id)
		if err != nil {
			return err
		}
		if len(heads) > 0 {
			duplicate++
			return nil
		}
		opID := randomID()
		op := Operation{ID: opID, Kind: "archive", EntityID: id, Parents: []string{}, Entity: map[string]any{"id": id, "version": opID, "revision": json.Number("1"), "deleted": false, "content": content}}
		if err = validate(op); err != nil {
			return errors.New("备份冲突内容无效")
		}
		all = append(all, op)
		conflictCount++
		return nil
	}
	for _, kind := range []string{"ledger", "tag", "record"} {
		key := map[string]string{"ledger": "ledgers", "tag": "tags", "record": "records"}[kind]
		values, exists := backup[key].([]any)
		if !exists {
			if kind == "tag" && version == 1 {
				continue
			}
			return nil, errors.New("备份缺少有效数组")
		}
		for _, value := range values {
			entity, ok := value.(map[string]any)
			if !ok {
				return nil, errors.New("备份条目无效")
			}
			id, ok := entity["id"].(string)
			if !ok || seen[kind+":"+id] {
				return nil, errors.New("备份存在无效或重复编号")
			}
			seen[kind+":"+id] = true
			if kind == "ledger" {
				ledgerIDs[id] = true
			}
			if kind == "record" {
				book, _ := entity["ledgerId"].(string)
				if !ledgerIDs[book] || entity["ended"] == nil {
					return nil, errors.New("备份记录缺少账本或尚未结束")
				}
			}
			copy := map[string]any{}
			for k, v := range entity {
				copy[k] = v
			}
			opID := randomID()
			copy["version"] = opID
			op := Operation{ID: opID, Kind: kind, EntityID: id, Parents: []string{}, Entity: copy}
			if err := validate(op); err != nil {
				return nil, errors.New("备份字段或时间区间无效")
			}
			heads, err := s.headsFor(kind, id)
			if err != nil {
				return nil, err
			}
			equal := false
			for _, head := range heads {
				if businessContent(head) == businessContent(entity) {
					equal = true
				}
			}
			if equal {
				duplicate++
				continue
			}
			if len(heads) > 0 {
				if err = archive(kind, entity); err != nil {
					return nil, err
				}
				continue
			}
			all = append(all, op)
			added++
		}
	}
	values, ok := backup["conflicts"].([]any)
	if !ok {
		return nil, errors.New("备份缺少冲突数组")
	}
	for _, value := range values {
		content, ok := value.(map[string]any)
		if !ok {
			return nil, errors.New("无效冲突内容")
		}
		kind, _ := content["kind"].(string)
		entity, ok := content["entity"].(map[string]any)
		if !ok {
			return nil, errors.New("无效冲突内容")
		}
		if err := archive(kind, entity); err != nil {
			return nil, err
		}
	}
	// 同一文件内重复冲突只保留一个版本，避免尚未提交的档案被重复创建。
	unique := []Operation{}
	operations := map[string]bool{}
	for _, op := range all {
		key := op.Kind + ":" + op.EntityID
		if operations[key] {
			duplicate++
			continue
		}
		operations[key] = true
		unique = append(unique, op)
	}
	if err := s.applyWeb(actor, unique); err != nil {
		return nil, errors.New("导入未提交，原有数据保持不变")
	}
	return map[string]int{"added": added, "duplicate": duplicate, "conflicts": conflictCount}, nil
}
