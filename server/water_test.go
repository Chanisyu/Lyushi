package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func waterDeviceRequest(s *Service, key string, request waterRequest) *httptest.ResponseRecorder {
	body, _ := json.Marshal(request)
	r := httptest.NewRequest(http.MethodPost, "https://test.local/v1/water", bytes.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+key)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Lyushi-Water", "1")
	w := httptest.NewRecorder()
	s.handler().ServeHTTP(w, r)
	return w
}

// 测试实际认证路由和 SQLite 事务。断线并发只改变一次水位，补记和网页编辑仅改变历史。
func TestWaterSyncHistoryAndAccountIsolation(t *testing.T) {
	s := testService(t)
	u, key := testAccountDevice(t, s, "water-first", "user")
	other, foreignKey := testAccountDevice(t, s, "water-other", "user")
	admin, _ := testAccountDevice(t, s, "water-admin", "admin")
	second, err := s.createDevice(u.ID, "第二台设备")
	if err != nil {
		t.Fatal(err)
	}
	keyTwo := second["key"].(string)
	store, err := s.userStore(u.ID)
	if err != nil {
		t.Fatal(err)
	}
	day := time.Date(2026, 11, 26, 0, 0, 0, 0, time.FixedZone("Beijing", 28800)).UnixMilli()
	cmd := func(kind string) waterCommand { return waterCommand{ID: randomID(), Kind: kind, At: day} }
	post := func(token string, cursor int64, ops ...waterCommand) waterResponse {
		t.Helper()
		w := waterDeviceRequest(s, token, waterRequest{ServerID: store.id, Cursor: cursor, Operations: ops})
		var response waterResponse
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &response) != nil {
			t.Fatalf("水位响应无效：%d %s", w.Code, w.Body.String())
		}
		return response
	}
	setup := cmd("setup")
	setup.Capacity = 750
	fill := cmd("fill")
	fill.Base = setup.ID
	fill.ML = 600
	start := post(key, 0, setup, fill)
	if start.Cursor != 2 || start.Changes[1].Bottle.ML != 600 || start.Changes[1].Entry.ML != 600 {
		t.Fatal("水壶初始化或加水失败")
	}
	drink := cmd("drink")
	drink.Base = fill.ID
	drink.From = 600
	drink.ML = 450
	first := post(key, start.Cursor, drink)
	stale := cmd("drink")
	stale.Base = fill.ID
	stale.From = 600
	stale.ML = 400
	secondReply := post(keyTwo, start.Cursor, stale)
	conflict := secondReply.Changes[len(secondReply.Changes)-1]
	if conflict.Conflict != "bottle_changed" || conflict.Entry != nil || conflict.Bottle != nil {
		t.Fatal("过期水位操作被计入饮水")
	}
	if retry := post(key, secondReply.Cursor, drink); len(retry.Changes) != 0 || retry.Ack[drink.ID] != first.Cursor {
		t.Fatal("已确认重试重复记账或缺少回执")
	}
	recover := cmd("recover")
	recover.Target = stale.ID
	resolved := post(keyTwo, secondReply.Cursor, recover)
	if e := resolved.Changes[0]; e.Resolved != stale.ID || e.Bottle != nil || e.Entry.ML != 200 {
		t.Fatal("冲突补记错误")
	}
	again := cmd("recover")
	again.Target = stale.ID
	if e := post(key, resolved.Cursor, again).Changes[0]; e.Entry != nil {
		t.Fatal("同一冲突可被重复补记")
	}
	if w := waterDeviceRequest(s, foreignKey, waterRequest{ServerID: store.id, Operations: []waterCommand{cmd("setup")}}); w.Code != 409 {
		t.Fatalf("跨账户身份被接受：%d", w.Code)
	}
	if w := waterDeviceRequest(s, key, waterRequest{ServerID: store.id, Cursor: 999}); w.Code != 409 {
		t.Fatal("接受未来游标")
	}
	if w := waterDeviceRequest(s, key, waterRequest{ServerID: store.id, Operations: []waterCommand{cmd("manual"), setup}}); w.Code != 409 {
		t.Fatal("非法批次提交成功")
	}
	userCookie, userCSRF := loginTest(t, s, u.Username)
	adminCookie, adminCSRF := loginTest(t, s, admin.Username)
	path := "/console/api/water?day=2026-11-26&userId=" + u.ID
	readTotal := func(w *httptest.ResponseRecorder, want int) {
		t.Helper()
		var body struct {
			Drank  int         `json:"drank"`
			Bottle waterBottle `json:"bottle"`
		}
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &body) != nil || body.Drank != want || body.Bottle.ML != 450 {
			t.Fatalf("统计或当前水位错误：%d %s", w.Code, w.Body.String())
		}
	}
	readTotal(browserRequest(s, userCookie, userCSRF, "GET", path, nil), 350)
	if w := browserRequest(s, userCookie, userCSRF, "GET", "/console/api/water?userId="+other.ID, nil); w.Code != 403 {
		t.Fatal("普通用户读取其他账户")
	}
	adjust := cmd("correct")
	adjust.Target = drink.ID
	adjust.Base = drink.ID
	adjust.ML = 125
	if w := browserRequest(s, adminCookie, adminCSRF, "POST", "/console/api/water", WebCommand{Action: "water", UserID: u.ID, Water: &adjust}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	readTotal(browserRequest(s, userCookie, userCSRF, "GET", path, nil), 325)
	readTotal(browserRequest(s, adminCookie, adminCSRF, "GET", path, nil), 325)
	voided := cmd("void")
	voided.Target = drink.ID
	voided.Base = drink.ID
	if w := browserRequest(s, userCookie, userCSRF, "POST", "/console/api/water", WebCommand{Action: "water", UserID: u.ID, Water: &voided}); w.Code != 409 {
		t.Fatal("旧版历史编辑成功")
	}
	voided.Base = adjust.ID
	if w := browserRequest(s, userCookie, userCSRF, "POST", "/console/api/water", WebCommand{Action: "water", UserID: u.ID, Water: &voided}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	readTotal(browserRequest(s, userCookie, userCSRF, "GET", path, nil), 200)
	if w := browserRequest(s, adminCookie, adminCSRF, "POST", "/console/api/water", WebCommand{Action: "water", UserID: u.ID, Water: &fill}); w.Code != 409 {
		t.Fatal("网页可执行实时水位操作")
	}
	// 固定北京时间的午夜边界：前一毫秒与午夜属于不同统计日期。
	prev := cmd("manual")
	prev.At = day - 1
	prev.ML = 90
	post(key, 0, prev)
	readTotal(browserRequest(s, userCookie, userCSRF, "GET", path, nil), 200)
	readTotal(browserRequest(s, userCookie, userCSRF, "GET", "/console/api/water?day=2026-11-25", nil), 90)
	if w := deviceRequest(s, key, "POST", "/v1/sync", Request{}); w.Code != 200 {
		t.Fatal("饮水破坏原协议 4 同步")
	}
}

// 校验容量、校准、撤销与导入去重。同步超过一页时，确认值只在对应游标之后清队列。
func TestWaterCapacityRestoreAndPages(t *testing.T) {
	s := testService(t)
	_, key := testAccountDevice(t, s, "water-history", "user")
	u, _ := s.userByName("water-history")
	store, _ := s.userStore(u.ID)
	command := func(kind string) waterCommand {
		return waterCommand{ID: randomID(), Kind: kind, At: time.Now().UnixMilli()}
	}
	send := func(cursor int64, operations ...waterCommand) waterResponse {
		t.Helper()
		w := waterDeviceRequest(s, key, waterRequest{ServerID: store.id, Cursor: cursor, Operations: operations})
		var response waterResponse
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &response) != nil {
			t.Fatal(w.Body.String())
		}
		return response
	}
	setup := command("setup")
	setup.Capacity = 500
	fill := command("fill")
	fill.Base = setup.ID
	fill.ML = 400
	small := command("capacity")
	small.Base = fill.ID
	small.Capacity = 399
	calibration := command("calibrate")
	calibration.Base = fill.ID
	calibration.ML = 300
	drink := command("drink")
	drink.Base = calibration.ID
	drink.From = 300
	drink.ML = 225
	undo := command("undo")
	undo.Base = drink.ID
	result := send(0, setup, fill, small, calibration, drink, undo)
	if len(result.Changes) != 6 || result.Changes[2].Conflict != "capacity_too_small" || result.Changes[3].Entry != nil || result.Changes[5].Bottle.ML != 300 || !result.Changes[5].Entry.Voided {
		t.Fatal("水壶修正或撤销错误")
	}
	restore := command("restore-entry")
	restore.Target = drink.ID
	restore.ML = 75
	restore.At = drink.At
	restore.Entry = "drink"
	restore.Voided = true
	if match := send(result.Cursor, restore).Changes[0]; match.Entry != nil || match.Conflict != "" {
		t.Fatal("导入未按内容去重")
	}
	badRestore := restore
	badRestore.ID = randomID()
	badRestore.ML = 100
	if conflict := send(result.Cursor, badRestore).Changes[1]; conflict.Conflict != "history_differs" {
		t.Fatal("导入不同历史被覆盖")
	}
	var last waterCommand
	for i := 0; i < 6; i++ {
		batch := []waterCommand{}
		for j := 0; j < 50; j++ {
			last = command("manual")
			last.ML = 1
			batch = append(batch, last)
		}
		send(0, batch...)
	}
	page := send(0, last)
	if !page.More || page.Cursor != 250 || page.Ack[last.ID] <= page.Cursor {
		t.Fatal("分页提前确认")
	}
	remaining := send(page.Cursor, last)
	if remaining.More || remaining.Ack[last.ID] != remaining.Cursor {
		t.Fatal("分页未收到最终确认")
	}
}
