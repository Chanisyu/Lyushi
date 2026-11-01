package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 标签与账本使用相同版本外壳，历史任务只持有名称快照，不依赖候选项仍然存在。
func tagOp(id, parent, name string, deleted bool) Operation {
	parents := []string{}
	if parent != "" {
		parents = append(parents, parent)
	}
	return Operation{ID: id, Kind: "tag", EntityID: "tag-id", Parents: parents, Entity: map[string]any{
		"id": "tag-id", "version": id, "name": name, "deleted": deleted, "revision": json.Number("1"),
	}}
}

// 验证标签增删、任务快照、重试去重及旧客户端继续修改无标签记录时的兼容读取。
func TestTagLifecycleAndRecordSnapshot(t *testing.T) {
	s := testService(t)
	entry := recordOp("record-tagged", "", json.Number("1700000001000"))
	entry.Entity["tag"] = "学习"
	first := send(t, s, 0, ledgerOp("ledger", "", "账本"), tagOp("tag-created", "", "学习", false), entry)
	deleted := tagOp("tag-deleted", "tag-created", "学习", true)
	next := send(t, s, first.Cursor, deleted)
	if next.Changes[0].Kind != "tag" || next.Changes[0].Heads[0]["deleted"] != true {
		t.Fatal("tag deletion was not propagated")
	}
	if retried := send(t, s, next.Cursor, deleted); len(retried.Changes) != 0 {
		t.Fatal("retry created duplicate tag event")
	}
	tx, _ := s.db.Begin()
	heads, err := readHeads(tx, "record", "entry")
	tx.Rollback()
	if err != nil || len(heads) != 1 || heads[0]["tag"] != "学习" {
		t.Fatal("tag deletion changed historical record")
	}
	// 无标签旧实体和旧队列仍可由新版客户端上传，不要求改写全部历史记录。
	legacy := recordOp("legacy", "record-tagged", json.Number("1700000002000"))
	send(t, s, next.Cursor, legacy)
}

// 非法标签和归档扩展必须整批回滚，不能将前置合法标签留在数据库中。
func TestTagBoundsAndAtomicRollback(t *testing.T) {
	s := testService(t)
	for _, value := range []any{nil, false, "", "  ", strings.Repeat("字", 201)} {
		entry := recordOp(randomID(), "", nil)
		entry.Entity["tag"] = value
		if _, err := s.sync(context.Background(), "test", Request{Operations: []Operation{tagOp(randomID(), "", "工作", false), ledgerOp(randomID(), "", "账本"), entry}}); err == nil {
			t.Fatalf("accepted invalid record tag type %T", value)
		}
		if send(t, s, 0).Cursor != 0 {
			t.Fatal("partial invalid batch persisted")
		}
	}
	archive := Operation{ID: "archive-version", Kind: "archive", EntityID: "archive-id", Parents: []string{}, Entity: map[string]any{
		"id": "archive-id", "version": "archive-version", "revision": json.Number("1"), "deleted": false,
		"content": map[string]any{"kind": "tag", "entity": tagOp("old", "", "历史标签", false).Entity, "archived": true},
	}}
	if validate(archive) != nil {
		t.Fatal("tag archive rejected")
	}
}

// 旧客户端在任何写事务前收到明确升级响应；错误密钥仍保持认证拒绝语义。
func TestProtocolUpgradeGate(t *testing.T) {
	s := testService(t)
	key := randomID()
	hash := sha256.Sum256([]byte(key))
	if _, err := s.db.Exec("INSERT INTO devices(id,label,token_hash) VALUES('upgrade-test','test',?)", hex.EncodeToString(hash[:])); err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(Request{Operations: []Operation{ledgerOp("rejected-ledger", "", "不应写入")}})
	for _, version := range []string{"", "1", "3"} {
		r := httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+key)
		r.Header.Set("X-Lyushi-Protocol", version)
		w := httptest.NewRecorder()
		s.handler().ServeHTTP(w, r)
		if w.Code != http.StatusUpgradeRequired || !strings.Contains(w.Body.String(), "client_upgrade_required") {
			t.Fatal("legacy client did not receive upgrade response")
		}
	}
	if send(t, s, 0).Cursor != 0 {
		t.Fatal("legacy client wrote data before rejection")
	}
}
