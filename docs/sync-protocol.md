# 同步协议与实现说明

本文供维护代码时查阅；日常使用和模块逻辑见[项目说明](../README.md)。协议版本为 1，采用 HTTPS JSON 请求，固定北京时间仅影响显示，起止保存 UTC 毫秒时间点。

## 数据与版本

账本和记录保留稳定 id。每次修改产生随机操作 id，该值也是新的 version；revision 为本地递增值，不作为多设备胜出依据。parents 指明本次修改基于哪些已知版本。服务器保存不可变 versions、当前 heads 和 changes。

ledger 包含 id、name、deleted、revision、version。record 包含 id、ledgerId、task、started、ended、deleted、revision、version；ended 为 null 表示未结束。archive 包含公共身份字段和 content，content 为保留的账本或记录版本。archive 不参与正在计时判断，也不会因备份导入重新启动活动。

客户端删除时声明当前版本及所有已知分支为父版本。尚未知晓的离线新分支不会被提前删除。业务内容等价比较忽略 version 与 revision，但包括归属、文字、时间及删除状态；相同内容的不同备份编号不制造额外冲突，其操作历史仍保留，等价父编号仍可被后续修改引用。

## 请求与响应

POST 基础地址下的 /v1/sync，Authorization 为 Bearer 设备密钥。请求含 serverId、cursor、operations；每个 operation 含 id、kind、entityId、parents、entity。单批最多 50 个操作，请求上限 2 MiB；客户端按真实 UTF-8 字节数在约 1.5 MiB 拆批，为信封字段留余量。备份中的实体只接受当前协议字段及有界版本号，防止本机接纳服务器无法处理的数据。

响应含 serverId、cursor、ack、changes、more。每个 change 含 seq、kind、id、heads，heads 是该时刻的完整版本头快照。每页至多 200 个变更，并控制累计正文大小；客户端响应读取上限为 8 MiB。

操作编号重复时核对完整规范化操作内容，相同则确认，不同则拒绝。单批操作、版本头和变更事件在服务器同一事务提交；任何一项无效则整批回滚。设备时钟不决定覆盖次序。

首次连接 serverId 为空且 cursor 为零；服务器返回持久化身份。后续身份不一致或游标超出当前数据库范围则拒绝，不自动把另一台服务器的数据覆盖到本机。切换服务器或重建服务器身份需要另行设计明确的迁移流程；当前版本保护性停止。

## 客户端提交边界

实体和 outbox 一起保存。上传批次内容不变，响应丢失后按同一操作身份重试。响应应用时核对完整 ack，与删除已确认 outbox、更新 sync_heads、更新界面实体及推进游标一起事务提交。

sync_awaiting 保存已确认上传但下载分页尚未追上的版本。看到该版本对应变更之前不以较旧分页覆盖本机新内容。仍有未上传操作的实体同样保留本机状态。此规则由本地 Windows 联测和安卓存储协议检查覆盖。

并发 heads 保存到只读冲突档案，当前界面优先保留本机已显示分支或未结束分支。历史档案通过 archive 类型传播，不能当作活动重新开始。已删除账本后来收到的离线记录也保留在冲突档案中。

## 认证与部署

设备密钥为 32 字节随机数的小写十六进制字符串，每台设备独立。服务器保存 SHA256 摘要及撤销状态；不提供公开注册。管理命令在服务器本机运行，创建密钥时写入不存在的 0600 文件，不把密钥放到进程参数或日志。

HTTPS 使用操作系统默认的证书与主机校验。客户端不跟随请求重定向，不通过忽略证书错误解决连接问题。服务器仅监听 127.0.0.1，由反向代理去掉配置的公开路径前缀并转发。Caddy 配置在热加载前验证，依据[官方命令文档](https://caddyserver.com/docs/command-line#caddy-validate)。

服务端 ReadHeaderTimeout 为 5 秒，读写超时为 20 秒，最多并行处理 8 个同步请求。SQLite 单连接串行事务、WAL 和 FULL 同步。systemd 使用独立用户和受限写目录，并设置 192 MiB 内存上限。

## 代码位置

| 文件 | 负责逻辑 |
| --- | --- |
| server/main.go | SQLite 模型、版本分支、内容等价、HTTP、设备管理和一致性备份 |
| server/main_test.go | 服务器行为及认证测试 |
| apps/desktop/store.cpp | 桌面实体事务、旧数据库转换及导入档案 |
| apps/desktop/syncstore.cpp | 桌面上传批次、响应事务、分页保护及分支镜像 |
| apps/desktop/syncclient.cpp | Qt 异步 HTTPS 与退避 |
| apps/android/.../SyncStore.kt | 安卓迁移、队列读取、响应应用和分页保护 |
| apps/android/.../SyncClient.kt | 安卓系统 HTTPS、工作线程及前台调度 |
| apps/android/.../SyncChecks.kt | 不访问网络的安卓 SQLite 协议场景检查 |
| scripts/test-sync-windows.ps1 | 两个真实 Qt 客户端与独立 Go 进程联测 |
| deploy/lyushi.service | 自托管 systemd 服务配置 |
| deploy/Caddyfile.example | HTTPS 路径与反向代理示例 |

编译 Go 服务使用 Go 1.27.3，当前 go.mod 的最低工具版本由已固定依赖提升为 1.26.0。SQLite 驱动固定 modernc.org/sqlite v1.61.0，模块校验记录在 go.sum；Linux amd64 采用 CGO_ENABLED=0 构建，服务器不依赖已安装的 Go 1.22.2 编译工具。


每台设备密钥由部署者创建并私下交付。维护者未提供公共同步服务；服务器初始化、设备撤销及备份恢复步骤见 README。2026 年 10 月 31 日项目使用者已确认三端与实际服务器同步成功，此记录为人工验收。
