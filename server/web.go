package main

import (
	"embed"
	"net/http"
	"strings"
)

// 静态页面随程序分发，不访问 CDN，不需要额外网页构建服务。
//
//go:embed web/*
var consoleFiles embed.FS

func serveConsole(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/")
	if name == "" || name == "console/" {
		name = "index.html"
	}
	if name != "index.html" && name != "app.js" && name != "style.css" {
		http.NotFound(w, r)
		return
	}
	raw, err := consoleFiles.ReadFile("web/" + name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	mime := map[string]string{"index.html": "text/html; charset=utf-8", "app.js": "application/javascript; charset=utf-8", "style.css": "text/css; charset=utf-8"}
	w.Header().Set("Content-Type", mime[name])
	w.Header().Set("Cache-Control", "no-store")
	w.Write(raw)
}
