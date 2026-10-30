package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

// 使用真正的文件数据库验证事务与重启后去重；测试密钥不关联任何生产设备。
func testService(t *testing.T) *Service {
	t.Helper()
	s, err := openService(filepath.Join(t.TempDir(), "sync.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.db.Close() })
	return s
}
func ledgerOp(id, parent, name string) Operation {
	parents := []string{}
	if parent != "" {
		parents = append(parents, parent)
	}
	return Operation{ID: id, Kind: "ledger", EntityID: "book", Parents: parents, Entity: map[string]any{"id": "book", "name": name, "revision": json.Number("1"), "deleted": false, "version": id}}
}
func recordOp(id, parent string, ended any) Operation {
	parents := []string{}
	if parent != "" {
		parents = append(parents, parent)
	}
	return Operation{ID: id, Kind: "record", EntityID: "entry", Parents: parents, Entity: map[string]any{"id": "entry", "ledgerId": "book", "task": "中文事项", "started": json.Number("1700000000000"), "ended": ended, "revision": json.Number("1"), "deleted": false, "version": id}}
}
func send(t *testing.T, s *Service, cursor int64, ops ...Operation) Response {
	t.Helper()
	r, e := s.sync(context.Background(), "test-device", Request{ServerID: s.id, Cursor: cursor, Operations: ops})
	if e != nil {
		t.Fatal(e)
	}
	return r
}

func TestConcurrentBranchesAndRetry(t *testing.T) {
	s := testService(t)
	first := send(t, s, 0, ledgerOp("a", "", "初始"), recordOp("r", "", nil))
	one := send(t, s, first.Cursor, recordOp("stop-a", "r", json.Number("1700000001000")))
	two := send(t, s, first.Cursor, recordOp("stop-b", "r", json.Number("1700000002000")))
	last := two.Changes[len(two.Changes)-1]
	if len(last.Heads) != 2 {
		t.Fatalf("lost concurrent version: %+v", last)
	}
	retried := send(t, s, two.Cursor, recordOp("stop-a", "r", json.Number("1700000001000")))
	if retried.Cursor != two.Cursor || len(retried.Changes) != 0 {
		t.Fatal("retry created event")
	}
	changed := recordOp("stop-a", "r", json.Number("1700000009999"))
	if _, err := s.sync(context.Background(), "x", Request{ServerID: s.id, Cursor: one.Cursor, Operations: []Operation{changed}}); err == nil {
		t.Fatal("reused operation accepted")
	}
}
func TestTransactionRollbackAndIdentity(t *testing.T) {
	s := testService(t)
	bad := recordOp("bad", "missing", nil)
	if _, e := s.sync(context.Background(), "x", Request{Operations: []Operation{ledgerOp("a", "", "账本"), bad}}); e == nil {
		t.Fatal("invalid batch accepted")
	}
	if response := send(t, s, 0); response.Cursor != 0 {
		t.Fatal("partial batch committed")
	}
	if _, e := s.sync(context.Background(), "x", Request{ServerID: "different"}); e == nil {
		t.Fatal("server replacement accepted")
	}
	if _, e := s.sync(context.Background(), "x", Request{ServerID: s.id, Cursor: 42}); e == nil {
		t.Fatal("future cursor accepted")
	}
}
func TestOfflineDeletionAndRestartDedup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sync.sqlite")
	s, err := openService(path)
	if err != nil {
		t.Fatal(err)
	}
	initial := send(t, s, 0, ledgerOp("a", "", "账本"), recordOp("r", "", nil))
	deletion := recordOp("delete", "r", json.Number("1700000001000"))
	deletion.Entity["deleted"] = true
	send(t, s, initial.Cursor, deletion)
	response := send(t, s, initial.Cursor, recordOp("offline-stop", "r", json.Number("1700000002000")))
	if len(response.Changes[len(response.Changes)-1].Heads) != 2 {
		t.Fatal("offline content discarded")
	}
	identity := s.id
	s.db.Close()
	s, err = openService(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	if s.id != identity {
		t.Fatal("server identity changed on restart")
	}
	after := send(t, s, response.Cursor, deletion)
	if len(after.Changes) != 0 {
		t.Fatal("dedup lost on restart")
	}
}

// 同一备份内容的不同编号等价，引用旧编号的后续修改仍然能正确接续。
func TestBackupEquivalentVersionsAndArchive(t *testing.T) {
	s := testService(t)
	first := send(t, s, 0, ledgerOp("a", "", "账本"), recordOp("r", "", json.Number("1700000001000")))
	same := send(t, s, first.Cursor, recordOp("restored", "", json.Number("1700000001000")))
	if len(same.Changes[0].Heads) != 1 {
		t.Fatal("equivalent restore created conflict")
	}
	changed := recordOp("edited", "r", json.Number("1700000002000"))
	next := send(t, s, same.Cursor, changed)
	if len(next.Changes[0].Heads) != 1 {
		t.Fatal("equivalent parent alias failed")
	}
	original := recordOp("old-running", "", nil).Entity
	archive := Operation{ID: "archive-version", Kind: "archive", EntityID: "archive-id", Parents: []string{}, Entity: map[string]any{"id": "archive-id", "version": "archive-version", "revision": json.Number("1"), "deleted": false, "content": map[string]any{"kind": "record", "entity": original, "archived": true}}}
	archived := send(t, s, next.Cursor, archive)
	if archived.Changes[0].Kind != "archive" {
		t.Fatal("history became live record")
	}
	tx, _ := s.db.Begin()
	heads, _ := readHeads(tx, "record", "entry")
	tx.Rollback()
	if len(heads) != 1 || heads[0]["ended"] == nil {
		t.Fatal("archive restarted timer")
	}
}

// 档案字段必须有界，防止来源文件的未知扩展或超长版本号绕过普通实体校验。
func TestArchiveInputBounds(t *testing.T) {
	original := recordOp("historical", "", nil).Entity
	op := Operation{ID: "archive-version", Kind: "archive", EntityID: "archive-id", Parents: []string{}, Entity: map[string]any{"id": "archive-id", "version": "archive-version", "revision": json.Number("1"), "deleted": false, "content": map[string]any{"kind": "record", "entity": original, "archived": true}}}
	original["version"] = string(make([]byte, 129))
	if validate(op) == nil {
		t.Fatal("oversized historical version accepted")
	}
	original["version"] = "historical"
	op.Entity["content"].(map[string]any)["unknown"] = "unsupported"
	if validate(op) == nil {
		t.Fatal("unknown archive field accepted")
	}
}

func TestPaginationAndAuthentication(t *testing.T) {
	s := testService(t)
	last := ""
	cursor := int64(0)
	for i := 0; i < 210; i++ {
		id := randomID()
		send(t, s, cursor, ledgerOp(id, last, "改名"))
		last = id
	}
	page := send(t, s, 0)
	if len(page.Changes) != 200 || !page.More {
		t.Fatal("pagination failed")
	}
	next := send(t, s, page.Cursor)
	if len(next.Changes) != 10 || next.More {
		t.Fatal("pagination continuation failed")
	}
	key := randomID()
	hash := sha256.Sum256([]byte(key))
	s.db.Exec("INSERT INTO devices(id,label,token_hash) VALUES('test','test',?)", hex.EncodeToString(hash[:]))
	body, _ := json.Marshal(Request{Operations: []Operation{}})
	serve := func(token string) int {
		r := httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		s.handler().ServeHTTP(w, r)
		return w.Code
	}
	if serve(randomID()) != 401 || serve(key) != 200 {
		t.Fatal("authentication failed")
	}
	s.db.Exec("UPDATE devices SET revoked=1")
	if serve(key) != 401 {
		t.Fatal("revoked key accepted")
	}
}
