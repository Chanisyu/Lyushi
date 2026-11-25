package main

import (
	"encoding/json"
	"errors"
)

// 分段作为同一记录的不可分割聚合保存，避免分页和网络重试暴露半次切换。
func validateFocusSegments(record map[string]any) error {
	value, exists := record["segments"]
	if !exists {
		return nil
	}
	parts, ok := value.([]any)
	raw, _ := json.Marshal(record)
	if !ok || len(parts) < 2 || len(parts) > 256 || len(raw) > 512*1024 {
		return errors.New("invalid segments size")
	}
	previousEnd, _ := number(record["started"])
	for index, value := range parts {
		part, ok := value.(map[string]any)
		if !ok {
			return errors.New("invalid segment object")
		}
		// 起止字段必须显式存在，缺失结束字段不能被解释为仍在计时。
		if _, exists := part["ended"]; !exists {
			return errors.New("missing segment end")
		}
		kind := "focus"
		if index%2 == 1 {
			kind = "distraction"
		}
		if part["kind"] != kind {
			return errors.New("invalid segment order")
		}
		for key := range part {
			if key != "kind" && key != "started" && key != "ended" && !(kind == "distraction" && (key == "task" || key == "tag")) {
				return errors.New("unknown segment field")
			}
		}
		copy := map[string]any{}
		for key, item := range record {
			if key != "segments" {
				copy[key] = item
			}
		}
		copy["started"], copy["ended"] = part["started"], part["ended"]
		if kind == "distraction" {
			copy["task"] = part["task"]
			delete(copy, "tag")
			if tag, exists := part["tag"]; exists {
				copy["tag"] = tag
			}
		}
		if part["ended"] == nil && (index != len(parts)-1 || record["ended"] != nil) {
			return errors.New("unfinished historical segment")
		}
		id, _ := record["version"].(string)
		entityID, _ := record["id"].(string)
		if err := validate(Operation{ID: id, Kind: "record", EntityID: entityID, Entity: copy}); err != nil {
			return err
		}
		start, _ := number(part["started"])
		if start < previousEnd || (index == 0 && start != previousEnd) {
			return errors.New("overlapping segments")
		}
		previousEnd, _ = number(part["ended"])
		if index == len(parts)-1 {
			a, _ := json.Marshal(part["ended"])
			b, _ := json.Marshal(record["ended"])
			if string(a) != string(b) {
				return errors.New("inconsistent task end")
			}
		}
	}
	return nil
}
