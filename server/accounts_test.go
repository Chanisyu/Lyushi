package main

import (
	"archive/zip"
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testPassword = "test-password-123456"

// 测试账户独立初始化，不读取生产文件；仅通用存储测试跳过首次改密流程。
func testAccountDevice(t *testing.T, s *Service, name, role string) (User, string) {
	t.Helper()
	u, err := s.createUser(name, name, role, testPassword)
	if err != nil {
		t.Fatal(err)
	}
	s.db.Exec(`UPDATE users SET must_change=0 WHERE id=?`, u.ID)
	device, err := s.createDevice(u.ID, "test device")
	if err != nil {
		t.Fatal(err)
	}
	return u, device["key"].(string)
}
func deviceRequest(s *Service, key, method, path string, value any) *httptest.ResponseRecorder {
	body, _ := json.Marshal(value)
	r := httptest.NewRequest(method, "https://test.local"+path, bytes.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+key)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Lyushi-Protocol", "4")
	w := httptest.NewRecorder()
	s.handler().ServeHTTP(w, r)
	return w
}
func browserRequest(s *Service, cookie *http.Cookie, csrf, method, path string, value any) *httptest.ResponseRecorder {
	body, _ := json.Marshal(value)
	r := httptest.NewRequest(method, "https://test.local"+path, bytes.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", "https://test.local")
	r.Header.Set("X-CSRF-Token", csrf)
	if cookie != nil {
		r.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	s.handler().ServeHTTP(w, r)
	return w
}
func loginTest(t *testing.T, s *Service, name string, passwords ...string) (*http.Cookie, string) {
	t.Helper()
	password := testPassword
	if len(passwords) > 0 {
		password = passwords[0]
	}
	w := browserRequest(s, nil, "", "POST", "/console/api/login", map[string]string{"username": name, "password": password})
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var reply struct {
		CSRF string `json:"csrf"`
	}
	json.Unmarshal(w.Body.Bytes(), &reply)
	cookie := w.Result().Cookies()[0]
	if !cookie.Secure || !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatal("unsafe session cookie")
	}
	return cookie, reply.CSRF
}

// 相同实体与操作编号在不同用户之间独立；伪造其他用户游标和服务器身份不能越权。
func TestAccountSyncIsolationAndRevocation(t *testing.T) {
	s := testService(t)
	a, ka := testAccountDevice(t, s, "alice", "user")
	b, kb := testAccountDevice(t, s, "bravo", "user")
	for _, key := range []string{ka, kb} {
		w := deviceRequest(s, key, "POST", "/v1/sync", Request{Operations: []Operation{ledgerOp("same-op", "", "owned")}})
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	ia := deviceRequest(s, ka, "GET", "/v1/device", nil)
	ib := deviceRequest(s, kb, "GET", "/v1/device", nil)
	var xa, xb map[string]any
	json.Unmarshal(ia.Body.Bytes(), &xa)
	json.Unmarshal(ib.Body.Bytes(), &xb)
	if xa["userId"] == xb["userId"] || xa["serverId"] == xb["serverId"] {
		t.Fatal("shared account identity")
	}
	if w := deviceRequest(s, kb, "POST", "/v1/sync", Request{ServerID: xa["serverId"].(string)}); w.Code != 409 {
		t.Fatal("foreign server identity accepted")
	}
	storeA, _ := s.userStore(a.ID)
	storeB, _ := s.userStore(b.ID)
	booksA, _ := storeA.current("ledger", false)
	booksB, _ := storeB.current("ledger", false)
	if len(booksA) != 1 || len(booksB) != 1 {
		t.Fatal("account data leaked")
	}
	s.db.Exec(`UPDATE users SET enabled=0 WHERE id=?`, a.ID)
	if deviceRequest(s, ka, "GET", "/v1/device", nil).Code != 401 {
		t.Fatal("disabled user accepted")
	}
	if deviceRequest(s, kb, "GET", "/v1/device", nil).Code != 200 {
		t.Fatal("unrelated user affected")
	}
}

// 普通用户不能通过用户参数、业务编号、设备编号或管理动作读取和修改其他账户。
func TestConsoleAuthorizationAndCSRF(t *testing.T) {
	s := testService(t)
	a, _ := testAccountDevice(t, s, "alice", "user")
	b, _ := testAccountDevice(t, s, "bravo", "user")
	cookie, csrf := loginTest(t, s, a.Username)
	for _, path := range []string{"/console/api/users", "/console/api/audit", "/console/api/state?userId=" + b.ID, "/console/api/export?userId=" + b.ID + "&format=json"} {
		if w := browserRequest(s, cookie, csrf, "GET", path, nil); w.Code != 403 {
			t.Fatal(path, w.Code)
		}
	}
	for _, action := range []WebCommand{{Action: "create_user", Username: "other", DisplayName: "other"}, {Action: "delete_user", UserID: b.ID, Confirmation: b.Username}, {Action: "create_device", UserID: b.ID, Label: "forged"}} {
		if w := browserRequest(s, cookie, csrf, "POST", "/console/api/action", action); w.Code == 200 {
			t.Fatal("privilege escalation", action.Action)
		}
	}
	if browserRequest(s, cookie, "wrong", "POST", "/console/api/action", WebCommand{Action: "create_device", Label: "x"}).Code != 403 {
		t.Fatal("CSRF accepted")
	}
	if browserRequest(s, cookie, csrf, "GET", "/console/api/state", nil).Code != 200 {
		t.Fatal("own state inaccessible")
	}
}

// 初始密码只允许改密；改密及重置使旧会话立即失效，管理员不能删除或停用自己。
func TestPasswordLifecycleAndAdminProtection(t *testing.T) {
	s := testService(t)
	u, err := s.createUser("admin", "管理员", "admin", testPassword)
	if err != nil {
		t.Fatal(err)
	}
	cookie, csrf := loginTest(t, s, "admin")
	if browserRequest(s, cookie, csrf, "GET", "/console/api/state", nil).Code != 403 {
		t.Fatal("initial password gained access")
	}
	changed := browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "password", OldPassword: testPassword, Password: testPassword + "-changed"})
	if changed.Code != 200 {
		t.Fatal(changed.Body.String())
	}
	if browserRequest(s, cookie, csrf, "GET", "/console/api/me", nil).Code != 401 {
		t.Fatal("old session survived password change")
	}
	cookie, csrf = loginTest(t, s, "admin", testPassword+"-changed")
	no := false
	for _, command := range []WebCommand{{Action: "delete_user", Confirmation: "admin"}, {Action: "user_status", Enabled: &no}} {
		if browserRequest(s, cookie, csrf, "POST", "/console/api/action", command).Code == 200 {
			t.Fatal("admin removed")
		}
	}
	if _, err = s.createUser("otheradmin", "other", "admin", testPassword); err == nil {
		t.Fatal("second admin accepted")
	}
	current, _ := s.user(u.ID)
	if current.MustChange {
		t.Fatal("password not changed")
	}
}

// 网页修改通过正常版本流传播，旧版本表单不覆盖新数据，活动任务不可被网页修改。
func TestWebChangesAndActiveProtection(t *testing.T) {
	s := testService(t)
	u, key := testAccountDevice(t, s, "alice", "user")
	cookie, csrf := loginTest(t, s, u.Username)
	first := deviceRequest(s, key, "POST", "/v1/sync", Request{Operations: []Operation{ledgerOp("book-first", "", "初始"), recordOp("active", "", nil)}})
	if first.Code != 200 {
		t.Fatal(first.Body.String())
	}
	store, _ := s.userStore(u.ID)
	records, _ := store.current("record", false)
	record := records[0]
	w := browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "save", Kind: "record", Entity: record, Expected: "active"})
	if w.Code == 200 {
		t.Fatal("edited active task")
	}
	end := recordOp("finished", "active", json.Number("1700000001000"))
	response := deviceRequest(s, key, "POST", "/v1/sync", Request{Operations: []Operation{end}})
	if response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	record = end.Entity
	record["task"] = "网页修改"
	w = browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "save", Kind: "record", Entity: record, Expected: "finished"})
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "save", Kind: "record", Entity: record, Expected: "finished"}).Code == 200 {
		t.Fatal("stale form accepted")
	}
	downloaded := deviceRequest(s, key, "POST", "/v1/sync", Request{})
	if !strings.Contains(downloaded.Body.String(), "网页修改") {
		t.Fatal("web changes absent from sync")
	}
}

// 停用撤销设备，重新启用不复活旧密钥；彻底删除清理对应用户文件而不影响其他用户。
func TestUserDeletionDeviceLimitsAndLegacyReset(t *testing.T) {
	s := testService(t)
	admin, _ := testAccountDevice(t, s, "admin", "admin")
	user, key := testAccountDevice(t, s, "alice", "user")
	cookie, csrf := loginTest(t, s, admin.Username)
	store, _ := s.userStore(user.ID)
	send(t, store, 0, ledgerOp("book", "", "private"))
	path, _ := s.accountPath(user.ID)
	for i := 0; i < 9; i++ {
		if _, err := s.createDevice(user.ID, "device"); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.createDevice(user.ID, "excess"); err == nil {
		t.Fatal("unlimited devices")
	}
	enabled := false
	if w := browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "user_status", UserID: user.ID, Enabled: &enabled}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	enabled = true
	browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "user_status", UserID: user.ID, Enabled: &enabled})
	if deviceRequest(s, key, "GET", "/v1/device", nil).Code != 401 {
		t.Fatal("old device revived")
	}
	if w := browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "delete_user", UserID: user.ID, Confirmation: user.Username}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("user database retained")
	}
	if _, err := s.user(admin.ID); err != nil {
		t.Fatal("admin affected")
	}
	legacy := testService(t)
	send(t, legacy, 0, ledgerOp("legacy", "", "old"))
	before := legacy.id
	if err := legacy.clearLegacy(); err != nil {
		t.Fatal(err)
	}
	if legacy.id == before || send(t, legacy, 0).Cursor != 0 {
		t.Fatal("legacy data survived reset")
	}
}

// 完整备份必须包含目录及每个账户库，保留归属而不遗漏记录；备份失败不覆盖已有文件。
func TestCatalogBackupIncludesAccountData(t *testing.T) {
	s := testService(t)
	u, _ := testAccountDevice(t, s, "alice", "user")
	store, _ := s.userStore(u.ID)
	send(t, store, 0, ledgerOp("book", "", "账本"), recordOp("entry", "", json.Number("1700000001000")))
	destination := filepath.Join(t.TempDir(), "backup.zip")
	if err := s.backupCatalog(destination); err != nil {
		t.Fatal(err)
	}
	if err := s.backupCatalog(destination); err == nil {
		t.Fatal("overwrote existing backup")
	}
	archive, err := zip.OpenReader(destination)
	if err != nil {
		t.Fatal(err)
	}
	defer archive.Close()
	found := map[string]bool{}
	for _, file := range archive.File {
		found[file.Name] = true
		if file.Name == "accounts/"+u.ID+".sqlite" {
			data, err := file.Open()
			if err != nil {
				t.Fatal(err)
			}
			var buffer bytes.Buffer
			buffer.ReadFrom(data)
			data.Close()
			path := filepath.Join(t.TempDir(), "account.sqlite")
			os.WriteFile(path, buffer.Bytes(), 0600)
			db, err := sql.Open("sqlite", path)
			if err != nil {
				t.Fatal(err)
			}
			var count int
			err = db.QueryRow(`SELECT COUNT(*) FROM versions`).Scan(&count)
			db.Close()
			if err != nil || count != 2 {
				t.Fatal("account content missing from backup", count, err)
			}
		}
	}
	if !found["catalog.sqlite"] || !found["manifest.json"] || !found["accounts/"+u.ID+".sqlite"] {
		t.Fatal("incomplete backup")
	}
}

// 网页备份与客户端格式兼容，重复导入不增生记录，损坏文件不得留下部分新账本。
func TestWebImportExportAndRollback(t *testing.T) {
	source := testService(t)
	send(t, source, 0, ledgerOp("book", "", "账本"), focusOp("entry", "", false))
	backup, err := source.exportWeb()
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(backup)
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var imported map[string]any
	decoder.Decode(&imported)
	target := testService(t)
	result, err := target.importWeb("admin", imported)
	if err != nil || result["added"] != 2 {
		t.Fatal(result, err)
	}
	result, err = target.importWeb("admin", imported)
	if err != nil || result["added"] != 0 || result["conflicts"] != 0 {
		t.Fatal("duplicate import changed data", result, err)
	}
	damaged := testService(t)
	records := imported["records"].([]any)
	records[0].(map[string]any)["ended"] = json.Number("1")
	if _, err = damaged.importWeb("admin", imported); err == nil {
		t.Fatal("damaged import accepted")
	}
	if send(t, damaged, 0).Cursor != 0 {
		t.Fatal("partial import committed")
	}
}

// 同一份备份可在普通记录和旧冲突列表中携带相同内容，回执应与实际写入一致。
func TestWebImportCountsRepeatedConflictOnce(t *testing.T) {
	target := testService(t)
	ledger := ledgerOp("book-version", "", "账本")
	local := recordOp("existing", "", json.Number("1700000001000"))
	send(t, target, 0, ledger, local)
	incoming := recordOp("incoming", "", json.Number("1700000001000"))
	incoming.Entity["task"] = "备份中的不同版本"
	conflict := map[string]any{"kind": "record", "entity": incoming.Entity, "archived": true}
	backup := map[string]any{
		"format": "lyushi.backup", "formatVersion": json.Number("3"),
		"ledgers": []any{ledger.Entity}, "tags": []any{}, "records": []any{incoming.Entity},
		"conflicts": []any{conflict, conflict},
	}
	result, err := target.importWeb("admin", backup)
	if err != nil || result["added"] != 0 || result["duplicate"] != 3 || result["conflicts"] != 1 {
		t.Fatalf("第一次导入统计错误：%v, %v", result, err)
	}
	archives, err := target.current("archive", false)
	if err != nil || len(archives) != 1 {
		t.Fatalf("应只保存一个冲突档案，实际 %d：%v", len(archives), err)
	}
	result, err = target.importWeb("admin", backup)
	if err != nil || result["added"] != 0 || result["duplicate"] != 4 || result["conflicts"] != 0 {
		t.Fatalf("重复导入统计错误：%v, %v", result, err)
	}
}
