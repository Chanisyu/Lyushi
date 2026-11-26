package main

import (
	"encoding/json"
	"reflect"
	"testing"
)

// 原生端元数据更正沿用协议 4；多设备可领取完整版本，网页仍不得修改活动任务。
func TestActiveMetadataSyncAndWebReadOnly(t *testing.T) {
	s := testService(t)
	u, key := testAccountDevice(t, s, "metadata", "user")
	cookie, csrf := loginTest(t, s, u.Username)
	initial := focusOp("initial", "", true)
	if r := deviceRequest(s, key, "POST", "/v1/sync", Request{Operations: []Operation{ledgerOp("book", "", "账本"), initial}}); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	edit := focusOp("renamed", "initial", true)
	edit.Entity["task"] = "英语复习"
	edit.Entity["tag"] = "学习"
	if r := deviceRequest(s, key, "POST", "/v1/sync", Request{Operations: []Operation{edit}}); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	// 起止与所有分段逐字段保持不变；服务端不能给元数据更正追加新的时间边界。
	store, _ := s.userStore(u.ID)
	rows, err := store.current("record", false)
	if err != nil || len(rows) != 1 {
		t.Fatal("missing active record", err)
	}
	row := rows[0]
	if row["task"] != "英语复习" || row["tag"] != "学习" || row["ended"] != nil || !reflect.DeepEqual(row["segments"], edit.Entity["segments"]) || row["started"] != edit.Entity["started"] {
		t.Fatal("metadata edit changed active timeline")
	}
	if r := browserRequest(s, cookie, csrf, "POST", "/console/api/action", WebCommand{Action: "save", Kind: "record", Entity: row, Expected: "renamed"}); r.Code == 200 {
		t.Fatal("web editor wrote active metadata")
	}
	// 同一父版本的离线编辑均保留；丢失响应后重试不得增加重复版本。
	branch := focusOp("offline", "initial", true)
	branch.Entity["task"] = "另一设备草稿"
	request := Request{Operations: []Operation{branch}}
	first := deviceRequest(s, key, "POST", "/v1/sync", request)
	if first.Code != 200 {
		t.Fatal(first.Body.String())
	}
	var reply Response
	if err := json.Unmarshal(first.Body.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	retry := deviceRequest(s, key, "POST", "/v1/sync", Request{ServerID: reply.ServerID, Cursor: reply.Cursor, Operations: []Operation{branch}})
	if retry.Code != 200 {
		t.Fatal(retry.Body.String())
	}
	var retried Response
	if err := json.Unmarshal(retry.Body.Bytes(), &retried); err != nil {
		t.Fatal(err)
	}
	if retried.Cursor != reply.Cursor || len(retried.Changes) != 0 {
		t.Fatal("duplicate active metadata operation")
	}
	heads := reply.Changes[len(reply.Changes)-1].Heads
	if len(heads) != 2 {
		t.Fatal("lost offline metadata branch")
	}
}
