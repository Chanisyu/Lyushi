package main

import (
	"context"
	"encoding/json"
	"testing"
)

// 以固定时间线构造 20 分钟专注、5 分钟分心、15 分钟专注，所有时间保持 JSON 整数语义。
func focusOp(id, parent string, running bool) Operation {
	op := recordOp(id, parent, json.Number("1700002400000"))
	end := op.Entity["ended"]
	if running {
		end = nil
		op.Entity["ended"] = nil
	}
	op.Entity["segments"] = []any{
		map[string]any{"kind": "focus", "started": json.Number("1700000000000"), "ended": json.Number("1700001200000")},
		map[string]any{"kind": "distraction", "task": "点饭", "tag": "生活", "started": json.Number("1700001200000"), "ended": json.Number("1700001500000")},
		map[string]any{"kind": "focus", "started": json.Number("1700001500000"), "ended": end},
	}
	return op
}

// 单次状态切换只有一个记录版本；响应丢失重试与并发恢复均保留完整区间。
func TestFocusAtomicSyncAndConcurrentResume(t *testing.T) {
	s := testService(t)
	first := send(t, s, 0, ledgerOp("book-start", "", "账本"), focusOp("running", "", true))
	a := focusOp("finished-a", "running", false)
	one := send(t, s, first.Cursor, a)
	b := focusOp("finished-b", "running", false)
	b.Entity["segments"].([]any)[2].(map[string]any)["ended"] = json.Number("1700002500000")
	b.Entity["ended"] = json.Number("1700002500000")
	two := send(t, s, first.Cursor, b)
	heads := two.Changes[len(two.Changes)-1].Heads
	if len(heads) != 2 {
		t.Fatal("concurrent timeline lost")
	}
	for _, head := range heads {
		if len(head["segments"].([]any)) != 3 {
			t.Fatal("partial timeline exposed")
		}
	}
	if retry := send(t, s, two.Cursor, a); retry.Cursor != two.Cursor || len(retry.Changes) != 0 {
		t.Fatal("retry duplicated switch")
	}
	if one.Cursor <= first.Cursor {
		t.Fatal("missing atomic event")
	}
}

// 时间、字段和状态非法时整批回滚，不能留下前置合法账本或半条分心记录。
func TestFocusValidationRollback(t *testing.T) {
	mutations := map[string]func(map[string]any){
		"missing end": func(e map[string]any) { delete(e["segments"].([]any)[2].(map[string]any), "ended") },
		"overlap": func(e map[string]any) {
			e["segments"].([]any)[1].(map[string]any)["started"] = json.Number("1700001199999")
		},
		"wrong order":   func(e map[string]any) { e["segments"].([]any)[1].(map[string]any)["kind"] = "focus" },
		"nested":        func(e map[string]any) { e["segments"].([]any)[1].(map[string]any)["segments"] = []any{} },
		"early running": func(e map[string]any) { e["segments"].([]any)[0].(map[string]any)["ended"] = nil },
		"wrong bound":   func(e map[string]any) { e["ended"] = json.Number("1700002500000") },
		"wrong start":   func(e map[string]any) { e["started"] = json.Number("1699999999999") },
		"invalid name":  func(e map[string]any) { e["segments"].([]any)[1].(map[string]any)["task"] = "" },
		"empty":         func(e map[string]any) { e["segments"] = []any{} },
		"oversized":     func(e map[string]any) { e["segments"] = make([]any, 257) },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			s := testService(t)
			op := focusOp("invalid", "", false)
			mutate(op.Entity)
			if _, err := s.sync(context.Background(), "test", Request{Operations: []Operation{ledgerOp("book", "", "账本"), op}}); err == nil {
				t.Fatal("accepted invalid timeline")
			}
			if send(t, s, 0).Cursor != 0 {
				t.Fatal("partial invalid batch persisted")
			}
		})
	}
	// 人工编辑允许区间间存在空隙，结束全部允许最后区间为分心。
	gap := focusOp("gap", "", false)
	gap.Entity["segments"].([]any)[1].(map[string]any)["started"] = json.Number("1700001200001")
	if err := validate(gap); err != nil {
		t.Fatal(err)
	}
	all := focusOp("all", "", false)
	all.Entity["segments"] = all.Entity["segments"].([]any)[:2]
	all.Entity["ended"] = json.Number("1700001500000")
	if err := validate(all); err != nil {
		t.Fatal(err)
	}
}
