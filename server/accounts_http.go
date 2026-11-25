package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 所有错误仅暴露稳定业务信息；响应禁止缓存，避免浏览器保留密钥、备份或用户数据。
func jsonReply(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(value)
}
func apiError(w http.ResponseWriter, status int, message string) {
	jsonReply(w, status, map[string]any{"error": message})
}
func tokenHash(value string) string {
	digest := sha256.Sum256([]byte(value))
	return hex.EncodeToString(digest[:])
}
func readJSON(w http.ResponseWriter, r *http.Request, value any, limit int64) error {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		return errors.New("请求须使用 JSON")
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	decoder.UseNumber()
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return errors.New("请求内容无效或超过大小限制")
	}
	if decoder.Decode(new(any)) != io.EOF {
		return errors.New("请求只能包含一个 JSON 对象")
	}
	return nil
}

// 浏览器写请求必须来自同一 HTTPS 来源；反向代理仅向本机端口转发，原始 Host 保留。
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	return origin != "" && origin == "https://"+r.Host
}

// 浏览器会话与设备密钥使用不同凭证；会话同时绑定密码版本及账户状态。
func (s *Service) session(r *http.Request) (User, string, error) {
	cookie, err := r.Cookie("__Host-lyushi_session")
	if err != nil || len(cookie.Value) != 64 {
		return User{}, "", errors.New("请先登录")
	}
	var id, csrf string
	var epoch int
	err = s.db.QueryRow(`SELECT user_id,csrf,auth_version FROM sessions WHERE token_hash=? AND expires>?`, tokenHash(cookie.Value), time.Now().Unix()).Scan(&id, &csrf, &epoch)
	if err != nil {
		return User{}, "", errors.New("登录已失效，请重新登录")
	}
	u, err := s.user(id)
	var current int
	if err != nil || !u.Enabled || u.Deleted {
		return User{}, "", errors.New("账户已停用")
	}
	if err = s.db.QueryRow(`SELECT auth_version FROM users WHERE id=?`, id).Scan(&current); err != nil || epoch != current {
		return User{}, "", errors.New("登录已失效")
	}
	return u, csrf, nil
}
func sessionCookie(w http.ResponseWriter, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{Name: "__Host-lyushi_session", Value: value, Path: "/", HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode, MaxAge: maxAge})
}

// 登录失败按来源地址与用户名限制，未知用户也执行同等成本派生，避免账户枚举与无限验证。
func (s *Service) login(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r) {
		apiError(w, 403, "来源校验失败")
		return
	}
	var input struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := readJSON(w, r, &input, 4096); err != nil {
		apiError(w, 400, err.Error())
		return
	}
	input.Username = strings.ToLower(strings.TrimSpace(input.Username))
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	key := host + ":" + input.Username
	now := time.Now().Unix()
	s.catalog.rateMu.Lock()
	if len(s.catalog.attempts) > 10000 {
		for k, v := range s.catalog.attempts {
			if v.Until < now {
				delete(s.catalog.attempts, k)
			}
		}
	}
	entry := s.catalog.attempts[key]
	if entry.Until < now {
		entry = loginAttempt{Until: now + 900}
	}
	if entry.Count >= 5 || len(s.catalog.attempts) > 10000 {
		s.catalog.rateMu.Unlock()
		apiError(w, 429, "尝试次数过多，请在十五分钟后重试")
		return
	}
	entry.Count++
	s.catalog.attempts[key] = entry
	s.catalog.rateMu.Unlock()
	select {
	case s.catalog.loginSlots <- struct{}{}:
		defer func() { <-s.catalog.loginSlots }()
	default:
		apiError(w, 429, "登录验证繁忙，请稍后重试")
		return
	}
	s.catalog.gate.Lock()
	u, lookup := s.userByName(input.Username)
	salt, expected := strings.Repeat("0", 64), strings.Repeat("0", 64)
	epoch := 0
	if lookup == nil {
		s.db.QueryRow(`SELECT salt,password_hash,auth_version FROM users WHERE id=?`, u.ID).Scan(&salt, &expected, &epoch)
	}
	s.catalog.gate.Unlock()
	matched := passwordMatches(input.Password, salt, expected)
	s.catalog.gate.Lock()
	defer s.catalog.gate.Unlock()
	current, err := s.user(u.ID)
	var currentEpoch int
	if lookup != nil || !matched || err != nil || !current.Enabled || current.Deleted {
		s.audit("", u.ID, "login_failed", "")
		apiError(w, 401, "用户名或密码错误，或账户不可用")
		return
	}
	if err = s.db.QueryRow(`SELECT auth_version FROM users WHERE id=?`, u.ID).Scan(&currentEpoch); err != nil || epoch != currentEpoch {
		apiError(w, 401, "账户状态已变化，请重新登录")
		return
	}
	token, csrf := randomID(), randomID()
	if _, err = s.db.Exec(`INSERT INTO sessions(token_hash,user_id,csrf,expires,auth_version) VALUES(?,?,?,?,?)`, tokenHash(token), u.ID, csrf, now+7*86400, epoch); err != nil {
		apiError(w, 500, "登录保存失败")
		return
	}
	s.catalog.rateMu.Lock()
	delete(s.catalog.attempts, key)
	s.catalog.rateMu.Unlock()
	sessionCookie(w, token, 7*86400)
	s.audit(u.ID, u.ID, "login", "")
	jsonReply(w, 200, map[string]any{"user": current, "csrf": csrf})
}

// 设备归属在同一临界区内验证并完成同步，账户停用或设备撤销后不接受后续写入。
func (s *Service) device(r *http.Request) (User, string, string, error) {
	authorization := r.Header.Get("Authorization")
	key := strings.TrimPrefix(authorization, "Bearer ")
	if !strings.HasPrefix(authorization, "Bearer ") || !accountIDPattern.MatchString(key) {
		return User{}, "", "", errors.New("unauthorized")
	}
	var id, userID, label string
	err := s.db.QueryRow(`SELECT id,user_id,label FROM devices WHERE token_hash=? AND revoked=0`, tokenHash(key)).Scan(&id, &userID, &label)
	if err != nil {
		return User{}, "", "", errors.New("unauthorized")
	}
	u, err := s.user(userID)
	if err != nil || !u.Enabled || u.Deleted {
		return User{}, "", "", errors.New("unauthorized")
	}
	return u, id, label, nil
}
func (s *Service) deviceEndpoint(w http.ResponseWriter, r *http.Request) {
	s.catalog.gate.Lock()
	defer s.catalog.gate.Unlock()
	u, id, label, err := s.device(r)
	if err != nil {
		apiError(w, 401, "unauthorized")
		return
	}
	if r.Header.Get("X-Lyushi-Protocol") != "4" {
		jsonReply(w, 426, map[string]any{"error": "client_upgrade_required", "minimumClient": "0.6.0"})
		return
	}
	if r.Method == http.MethodGet {
		identity, err := s.accountIdentity(u, id, label)
		if err != nil {
			apiError(w, 500, "账户存储不可用")
			return
		}
		jsonReply(w, 200, identity)
		return
	}
	var request Request
	if err = readJSON(w, r, &request, 2*1024*1024); err != nil {
		apiError(w, 400, "invalid_request")
		return
	}
	store, err := s.userStore(u.ID)
	if err != nil {
		apiError(w, 500, "账户存储不可用")
		return
	}
	response, err := store.sync(r.Context(), id, request)
	if err != nil {
		apiError(w, 409, "sync_not_committed")
		return
	}
	if _, err = s.db.Exec(`UPDATE devices SET last_sync=? WHERE id=?`, time.Now().UnixMilli(), id); err != nil {
		apiError(w, 500, "同步状态保存失败")
		return
	}
	jsonReply(w, 200, response)
}

// 网页与原有同步入口共用一个程序；页面静态资源通过相对路径兼容两个反向代理前缀。
func (s *Service) accountHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		jsonReply(w, 200, map[string]any{"status": "ok", "protocol": 4, "minimumClient": "0.6.0"})
	})
	slots := make(chan struct{}, 8)
	endpoint := func(w http.ResponseWriter, r *http.Request) {
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
			s.deviceEndpoint(w, r)
		default:
			apiError(w, 503, "busy")
		}
	}
	mux.HandleFunc("GET /v1/device", endpoint)
	mux.HandleFunc("POST /v1/sync", endpoint)
	mux.HandleFunc("POST /console/api/login", s.login)
	mux.HandleFunc("GET /console/api/me", func(w http.ResponseWriter, r *http.Request) {
		s.catalog.gate.Lock()
		defer s.catalog.gate.Unlock()
		u, csrf, err := s.session(r)
		if err != nil {
			apiError(w, 401, err.Error())
			return
		}
		jsonReply(w, 200, map[string]any{"user": u, "csrf": csrf})
	})
	mux.HandleFunc("GET /console/api/", s.consoleAPI)
	mux.HandleFunc("POST /console/api/", s.consoleAPI)
	mux.HandleFunc("GET /", serveConsole)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "same-origin")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
		mux.ServeHTTP(w, r)
	})
}

// 管理入口集中校验会话、CSRF、初始密码状态及目标账户；查询参数不是权限依据。
func (s *Service) consoleAPI(w http.ResponseWriter, r *http.Request) {
	s.catalog.gate.Lock()
	defer s.catalog.gate.Unlock()
	actor, csrf, err := s.session(r)
	if err != nil {
		apiError(w, 401, err.Error())
		return
	}
	if r.Method != http.MethodGet && (!sameOrigin(r) || r.Header.Get("X-CSRF-Token") != csrf) {
		apiError(w, 403, "请求校验失败，请刷新页面")
		return
	}
	action := ""
	var command WebCommand
	if r.Method == http.MethodPost {
		if err = readJSON(w, r, &command, 8*1024*1024); err != nil {
			apiError(w, 400, err.Error())
			return
		}
		action = command.Action
	}
	if actor.MustChange && action != "password" && action != "logout" {
		apiError(w, 403, "请先修改初始密码")
		return
	}
	now := time.Now().Unix()
	if now-s.catalog.cleaned > 3600 {
		s.db.Exec(`DELETE FROM sessions WHERE expires<?`, now)
		s.db.Exec(`DELETE FROM audit WHERE at<?`, time.Now().AddDate(0, 0, -180).UnixMilli())
		s.catalog.cleaned = now
	}
	targetID := r.URL.Query().Get("userId")
	if command.UserID != "" {
		targetID = command.UserID
	}
	if targetID == "" {
		targetID = actor.ID
	}
	if targetID != actor.ID && actor.Role != "admin" {
		apiError(w, 403, "无权访问其他账户")
		return
	}
	target, err := s.user(targetID)
	if err != nil {
		apiError(w, 404, "账户不存在")
		return
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/console/api/users":
		if actor.Role != "admin" {
			apiError(w, 403, "需要管理员权限")
			return
		}
		s.webUsers(w)
	case r.Method == http.MethodGet && r.URL.Path == "/console/api/state":
		s.webState(w, r, target)
	case r.Method == http.MethodGet && r.URL.Path == "/console/api/export":
		s.webExport(w, r, target)
	case r.Method == http.MethodGet && r.URL.Path == "/console/api/audit":
		if actor.Role != "admin" {
			apiError(w, 403, "需要管理员权限")
			return
		}
		s.webAudit(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/console/api/action":
		result, err := s.webAction(actor, target, command, r)
		if err != nil {
			apiError(w, 409, err.Error())
			return
		}
		if action == "logout" || action == "password" {
			sessionCookie(w, "", -1)
		}
		jsonReply(w, 200, result)
	default:
		apiError(w, 404, "页面或操作不存在")
	}
}
func pageNumber(r *http.Request) int {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 0 {
		return 0
	}
	if page > 100000 {
		return 100000
	}
	return page
}
