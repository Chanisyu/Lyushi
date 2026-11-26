<div align="center">

<table align="center">
  <tr>
    <td>
<pre>
██       ██    ██  ██    ██   ██████   ██    ██  ██████
██        ██  ██   ██    ██  ██        ██    ██    ██
██         ████    ██    ██   ██████   ████████    ██
██          ██     ██    ██        ██  ██    ██    ██
███████     ██      ██████    ██████   ██    ██  ██████
</pre>
    </td>
  </tr>
</table>

<p><strong>简体中文</strong> | <a href="README.en.md">English</a></p>

</div>

# 柳时 · Lyushi

柳时是一款支持 Android、Windows 和 Ubuntu 的时间记录工具。应用以独立账本组织任务，支持分心计时、任务恢复、离线记录及自托管多用户同步。每位用户拥有独立数据，管理员可通过网页管理账户、设备和业务记录。

项目自身源码与文档采用 [Apache License 2.0](LICENSE)。第三方组件保留各自许可证，参见[第三方依赖说明](THIRD_PARTY_NOTICES.md)。

## 下载与版本

[已发布版本](https://github.com/Chanisyu/Lyushi/releases) · [更新记录](CHANGELOG.md) · [English](README.en.md)

当前源码版本为 **0.7.0**，同步协议为 **4**。已发布的 0.4.0 安装包不包含分心、多用户及记录期间编辑功能，也不能连接协议 4 服务端。0.7.0 可按下文从源码构建，生成文件如下：

| 平台 | 文件 | 使用方式 |
| --- | --- | --- |
| Windows 11 x64 | `lyushi-windows-0.7.0-beta.zip` | 完整解压，运行 `Lyushi-0.7.0/lyushi.exe`，保留同目录运行库 |
| Android | `lyushi-android-0.7.0-beta.apk` | 在系统文件管理器安装；当前采用调试签名 |
| Ubuntu 26.04 LTS amd64 | `lyushi_0.7.0_amd64.deb` | 执行 `sudo apt install ./lyushi_0.7.0_amd64.deb` |
| Linux amd64 服务端 | `lyushi-server-linux-amd64` | 配合 `deploy/` 中的 systemd 与 Caddy 配置部署 |

构建产物位于 `dist/`，不纳入源码版本控制。Android 最低要求为 Android 8.0（API 26），当前验证环境为 Android 17。不同签名的 APK 通常不能直接覆盖安装；迁移前应导出 JSON 备份。桌面数据与程序文件分别保存，替换安装包不应覆盖数据目录。

## 功能

| 功能 | 说明 |
| --- | --- |
| 独立账本 | 新建、选择、改名及确认删除；各账本分别管理记录 |
| 任务标签 | 选择一个可选标签，新建和删除候选项；历史快照继续保留 |
| 开始与结束 | 保存实际起止时刻并显示读秒，关闭应用不自动结束记录 |
| 记录期间编辑 | 保存当前事项名称与标签，计时继续，任务身份与时间边界保持不变 |
| 分心与恢复 | 临时记录其他事项，一键恢复原任务及标签，分心不计入有效时长 |
| 明细管理 | 补记、修改已结束任务、编辑分段起止时间及删除整条任务 |
| 备份与表格 | JSON 合并恢复并保留不同版本；CSV 按专注与分心区间导出 |
| 多用户同步 | 每位用户独立账本、标签、记录、版本历史、设备和同步游标 |
| 网页后台 | 管理员管理全部用户；普通用户只管理本人数据及设备 |
| 账户切换 | 客户端按账户保存独立本地数据库和密钥，可离线切换已保存账户 |
| 外观 | 简体中文，白天、黑夜与跟随系统 |

所有日期固定按北京时间（UTC+8）显示，内部保存 UTC 毫秒时间点。计时取自设备系统时钟，不提供独立网络校时。设备时钟回拨造成时间逆序时，应用保留原状态并拒绝提交。

## 客户端使用

### 单机记录

1. 新建或选择账本，填写正在进行的事项，选择标签或“无标签”。
2. 点击“开始记录”，应用立即保存开始时刻。
3. 点击“结束记录”，保存结束时刻并进入账本明细。

记录期间不能切换账本、切换账户、查看账本明细或导入导出。关闭应用、退出窗口及设备重启均不会自动结束记录。

### 分心与任务恢复

原任务计时期间点击“分心一下”，填写临时事项和可选标签，确认后开始分心计时。填写表单期间原任务继续计时，取消不更改记录。空事项默认为“分心”，标签默认“无标签”。

点击“结束分心并继续”立即恢复原事项与原标签；点击“结束全部”并确认则结束整个任务。任务可以多次分心，限制为一层。

例如，专注 20 分钟、分心 5 分钟、再专注 15 分钟，原任务有效时长为 35 分钟。明细保留一条任务汇总，通过分段明细查看各区间。专注事项与标签统一修改，分心事项与标签分别修改；各段时间可独立修改，但不能重叠或逆序。删除任务同时删除全部区间。

完整规则见[分心与任务恢复](docs/distraction-and-resume.md)。

### 记录期间修改事项与标签

计时期间点击事项右侧的“修改”，调整名称或选择已有标签／“无标签”，再点击“保存”。表单打开期间继续计时，取消不改变记录。专注阶段的更正应用于本次任务全部专注区间；分心阶段只更正当前分心，恢复后保留原任务及标签。

其他设备修改、结束或切换阶段后，表单保留草稿并提示核对，旧表单不能覆盖最新状态。Android 屏幕旋转保留草稿；取消或完全退出丢弃未保存输入。新增与删除标签、账本管理和导入导出仍限非记录状态。完整规则及模块职责见[活动编辑说明](docs/active-task-editing.md)。

### 配置同步与切换账户

1. 在网页后台为本人账户添加设备，保存本次生成的设备密钥。
2. 打开客户端“设置”，填写服务器地址，例如 `https://time.example.com/lyushi-api`，以及本设备密钥。
3. 点击“验证并连接账户”。应用先读取账户身份，核对后才上传或领取业务数据。
4. 首次从单机模式连接时，可明确选择是否合并当前单机数据；选择不合并时，两份数据分别保留。
5. 此后可在“已保存账户”中选择账户并切换。切换仅允许在非记录状态下进行，已有账户可离线打开。

同一用户更换设备密钥继续使用同一份本地数据；不同用户及不同服务器身份分别使用独立数据库。原有单用户版本的本地库不会自动上传到新账户。需要保留旧记录时，可在旧版导出 JSON，随后在目标账户明确导入。

Windows 使用 DPAPI 保存密钥；Android 使用 Keystore；Ubuntu 使用 Secret Service，需安装 `libsecret-tools` 并解锁系统密钥环。外观设置保存在本机，不参与账户同步。

### 导入与导出

JSON 用于完整备份和合并恢复，当前格式为 **3**，继续读取格式 **1、2**。编号及内容相同的记录去重，不同版本保存为只读冲突档案；导入不会清空本机、自动复活已删除数据或重新启动历史计时。

CSV 每个专注或分心区间各占一行，同一任务共用记录编号，包含账本、事项、起止时间、时长、标签、区间序号与类型。按“专注”筛选并求和得到有效时长；不另加重复计时的汇总行。CSV 不用于完整恢复。

客户端 JSON 导入上限为 32 MiB；网页导入上限为 8 MiB。网页处理较大备份时，应改用对应账户的客户端导入。格式错误、未知字段或非法时间会使整次导入回滚。

## 网页管理后台

后台地址示例：`https://time.example.com/lyushi/`。网页与同步服务在同一个 Go 程序中运行，不需要另行启动 Node.js、数据库服务器或前端构建服务。页面资源内嵌，无外部 CDN 依赖。

| 操作 | 管理员 | 普通用户 |
| --- | --- | --- |
| 创建、停用、删除普通账户 | 允许 | 不允许 |
| 查看和修改账本、任务、标签及分心区间 | 所有账户 | 本人账户 |
| 添加设备、轮换密钥、撤销权限 | 所有账户 | 本人设备 |
| JSON 导入导出及 CSV 导出 | 按所选用户 | 本人账户 |
| 查看冲突档案 | 按所选用户 | 本人账户 |
| 查看后台操作日志 | 允许 | 不允许 |
| 修改本人密码与显示名称 | 允许 | 允许 |

初始管理员用户名为 `admin`，首期仅设一个管理员，不能停用或删除自身。普通账户由管理员创建，用户名为 3—32 位字母、数字、点、下划线或连字符，统一转为小写；显示名称支持中文。

新用户获得一次性临时密码，首次登录必须修改。密码至少 12 个字符且不超过 256 字节。后台不展示密码原文；普通用户忘记密码由管理员重置，管理员密码可通过服务器本地命令重置。

每个账户最多十台有效设备，已撤销设备不占额度。设备密钥仅在创建或轮换时完整显示一次；遗失后重新生成，旧密钥立即失效。停用账户会撤销全部设备并结束网页会话，重新启用不会复活旧密钥。

“停用”保留数据；“彻底删除”需输入用户名确认，清除该账户的业务数据库、版本历史和设备。离线设备上的副本和既有服务器备份不随该操作远程清除。

网页管理已结束任务，可随时查看进行中状态，但不承担开始、分心、恢复或结束计时。进行中的任务不可修改或删除，含活动任务的账本不能删除；导入导出须在该账户无活动任务时进行。网页保存检查版本，过期表单需刷新核对，不强制覆盖其他设备修改。

操作日志记录近 180 天的登录、账户、设备及业务变更元信息，不包含密码或明文设备密钥。详见[账户与后台说明](docs/account-management.md)。

## 服务器部署

要求：具备 systemd 的 Linux 主机、有效域名及 HTTPS。服务仅监听 `127.0.0.1:8093`，通过现有 Caddy 转发，无需 Docker。服务器可读取业务内容，不提供端到端加密。

### 安装与初始化

以下命令用于全新目录。已有单用户或多用户数据时，应使用后文升级流程。

```bash
sudo useradd --system --home-dir /var/lib/lyushi --shell /usr/sbin/nologin lyushi
sudo install -d -m 0755 /opt/lyushi
sudo install -d -m 0700 -o lyushi -g lyushi /var/lib/lyushi
sudo install -m 0755 build/lyushi-server-linux-amd64 /opt/lyushi/lyushi-server
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --init-admin --password-file /var/lib/lyushi/admin-initial-password.txt
sudo install -m 0644 deploy/lyushi.service /etc/systemd/system/lyushi.service
sudo systemctl daemon-reload
sudo systemctl enable --now lyushi
curl --fail http://127.0.0.1:8093/health
```

临时密码仅写入指定的 0600 文件，文件必须尚不存在。通过可信终端读取并完成首次改密后，可删除该交接文件。密码和设备密钥不得提交源码、放入安装包或公开问题报告。

将 [Caddy 配置示例](deploy/Caddyfile.example)合并至已有站点，替换实际域名。网页使用 `/lyushi/`，客户端服务器地址使用 `/lyushi-api`。配置变更后先执行 `caddy validate`，再重新加载 Caddy。

健康检查返回 `protocol=4`、`minimumClient=0.6.0`。登录后台后即可创建普通用户，并在相应用户下添加设备。管理员本地重置命令：

```bash
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --reset-admin-password --password-file /var/lib/lyushi/admin-reset-password.txt
```

### 升级至 0.6.0

服务端与三端客户端需要共同升级。旧客户端无法使用协议 4，旧本地文件继续保留，但不会自动成为新账户数据。

`deploy/upgrade-multiuser.sh` 是从协议 3 单用户服务进行**空白初始化**的一次性脚本：先保留回退备份，随后清空旧业务数据和设备密钥，创建空白管理员并配置网页入口。需要保留历史记录时，应先从旧客户端导出 JSON，升级后按用户导入。脚本会拒绝对已初始化的协议 4 服务重复清空。

已运行多用户服务的常规更新使用 `deploy/upgrade-server.sh`，不会重新初始化账户。升级前生成包含全部用户数据库的完整 ZIP 备份，更新后核对身份、设备归属及历史，失败回退程序。

0.7.0 的记录期间编辑保持协议 4 与备份格式 3，兼容现有 0.6.0 服务端。升级客户端即可使用，不要求更换服务器程序、迁移数据或重置设备密钥。

## 同步、备份与恢复

设备密钥只访问其所属用户。每次同步包含认证、完整批次确认、分页领取及本地事务提交，失败重试不会重复创建相同操作。前台通常每五秒轮询，失败后退避；Android 后台不保证即时同步。

离线并发保留双方版本，不按设备时间静默覆盖。网页修改同样产生正常同步版本；冲突档案只读，不会重新启动计时。服务器身份变化或游标不匹配时停止同步并保护本地数据。完整协议见[同步协议](docs/sync-protocol.md)。

JSON 备份只包含所选用户的业务数据，不包含登录会话、密码或设备密钥。服务器完整备份同时包含账户目录和各用户数据库：

```bash
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --backup /var/lib/lyushi/backups/server-backup.zip
```

备份目标必须尚不存在，父目录须允许 `lyushi` 用户写入。备份应另存服务器外，不能仅依赖同步或同机副本。

`scripts/restore-server.py` 将完整 ZIP 恢复到**尚不存在的新目录**，校验文件白名单、用户归属和 SQLite 完整性，不覆盖原服务目录。恢复后更新服务器身份、撤销旧设备并使旧会话失效，需重新登录、改密和创建设备。完整恢复步骤见[账户与后台说明](docs/account-management.md#完整服务器备份与恢复)。

## 模块职责

| 模块 | 功能与代码位置 |
| --- | --- |
| 客户端界面与计时 | 原生 Android 与 Qt 桌面页面，记录状态、分心及明细 |
| 账户与本地数据 | `Profiles.kt`、`profiles.cpp`，验证密钥归属、选择独立数据库及系统密钥存储 |
| 本地存储与同步 | `Store`、`SyncStore`、`SyncClient`，事务、待同步队列、身份绑定、分页及冲突 |
| 账户目录 | `server/accounts.go`，用户、设备、密码、状态及每用户 SQLite 归属 |
| 网页认证 | `server/accounts_http.go`，会话、同源与 CSRF 校验、登录限制、权限检查 |
| 业务管理与备份 | `console_data.go`、`console_backup.go`、`catalog_backup.go`，网页操作、用户导入导出及完整备份 |
| 同步版本与分段 | `server/main.go`、`focus.go`，不可变版本、增量同步及时间线校验 |
| 网页资源 | `server/web/`，简体中文轻量界面、分页、表单及响应式布局 |

本机工具、凭证、业务数据库和生成产物不属于公开源码。中文源码注释使用 UTF-8，第三方依赖保留原许可证。

## 从源码构建

### 构建环境

| 组件 | 要求或已验证版本 |
| --- | --- |
| 桌面 | C++17、CMake 3.22+、Qt 6 Widgets / Sql / Network、匹配的 C++ 编译器 |
| Qt | Windows 已验证 6.10.1，Ubuntu 已验证 6.10.2 |
| Android | JDK 17、Android SDK API 37、Gradle 9.6.0、Android Gradle Plugin 9.4.0 |
| Go 服务 | Go 1.26+；已验证 Go 1.27.3；依赖固定于 `go.mod` 与 `go.sum` |

### Windows 桌面端

安装 Qt 6、Ninja、CMake 及匹配的 MinGW 工具链。在 PowerShell 中执行，示例路径须替换为实际安装位置：

```powershell
./scripts/package-windows.ps1 -QtDirectory 'C:/Qt/6.10.1/mingw_64' -ToolchainDirectory 'C:/Qt/Tools/mingw1310_64'
```

脚本依赖 MinGW 的 `objdump.exe` 递归检查 DLL，并使用 `windeployqt.exe` 收集运行库。Qt 与编译器必须使用兼容的发行版本。也可通过 `LYUSHI_QT_DIR`、`LYUSHI_TOOLCHAIN_DIR` 环境变量指定目录。此脚本针对 MinGW 发行版；使用 MSVC 时应自行采用相应 Qt 包的构建与部署工具。

### Ubuntu 桌面端

```bash
sudo apt update
sudo apt install build-essential cmake ninja-build qt6-base-dev \
  qt6-base-dev-tools libqt6sql6-sqlite qt6-qpa-plugins libsecret-tools
cmake -S apps/desktop -B build/desktop-linux -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/desktop-linux --parallel 2
./build/desktop-linux/lyushi
```

生成 DEB 包时，在 Ubuntu 上运行 `bash scripts/package-linux.sh`。该脚本只整理文件和生成安装包，不安装或替换系统中的程序。

### Android

安装上表中的 JDK、Gradle 与 Android SDK，设置 `ANDROID_HOME` 后，从项目根目录执行：

```bash
gradle -p apps/android :app:assembleDebug --console=plain
```

输出为 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。仓库当前未附 Gradle Wrapper，需自行安装匹配版本 Gradle，或通过 Android Studio 配置对应版本。发布签名和密钥文件不得提交。

### 服务端交叉构建

在 Linux 上构建 amd64 服务器：

```bash
mkdir -p build
cd server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o ../build/lyushi-server-linux-amd64 .
```

Windows 开发者可在 PowerShell 中设置同名环境变量后执行 `go build`。`scripts/package-server.ps1 -GoExecutable <go.exe路径>` 收集 Go 依赖许可证并整理已经构建的 Linux 服务文件；运行前须生成 `build/lyushi-server-linux-amd64`。

## 运行测试

项目提供服务端单元测试、桌面存储与恢复自检、本地双客户端同步测试及 Android 存储协议检查。测试应使用独立数据库和测试设备。

服务器单元测试：

```bash
cd server
go test ./...
```

桌面隔离自检示例（Linux）：

```bash
QT_QPA_PLATFORM=offscreen ./build/desktop-linux/lyushi --self-test "$(mktemp -d)"
QT_QPA_PLATFORM=offscreen ./build/desktop-linux/lyushi --active-editor-test "$(mktemp -d)"
```

Windows 本机双客户端联测需先构建 `build/desktop-windows/lyushi.exe` 与 `build/lyushi-server.exe`，再运行：

```powershell
./scripts/test-sync-windows.ps1 -QtDirectory 'C:/Qt/6.10.1/mingw_64' -ToolchainDirectory 'C:/Qt/Tools/mingw1310_64'
```

该脚本使用随机回环端口、独立测试数据库和临时测试密钥，不访问线上服务器。Android 检查使用调试 APK，可通过 `scripts/check-android.ps1 -Serial <adb设备编号> -AndroidSdk <SDK路径>` 执行；仅在已授权的测试设备上运行。

Android 编辑表单与旋转恢复使用 `scripts/test-active-editor-android.ps1 -Serial <adb设备编号> -AndroidSdk <SDK路径>` 验证，使用单独偏好及数据库，不读取日常账户密钥。

## 常见问题

**关闭应用后是否继续计时？**

继续保留未结束状态。显示的经过时间由已保存的开始时刻计算，不要求程序每秒常驻运行。

**无法切换账本或进入明细？**

先结束正在进行的记录。离线并发可能存在多个未结束版本，应逐条核对。

**显示“设备密钥无效或已撤销”？**

核对服务器地址、密钥是否完整、设备编号是否被误填为密钥，以及该设备是否已撤销。

**显示“本机已保存，等待重连”？**

记录已留在本机。检查网络、域名解析、HTTPS 证书及服务器状态，恢复后再次同步。客户端不以忽略证书错误的方式建立连接。

**Ubuntu 无法保存密钥？**

检查 `secret-tool` 是否可用以及当前桌面的 Secret Service 是否启动、密钥环是否解锁。应用不会回退到明文保存。

**导入 JSON 后出现“保留的不同版本”？**

备份与本机存在不同内容，应用保留双方版本。查看冲突不会自动覆盖记录，也不会启动历史计时。

**卸载后能否恢复？**

依赖已保存的 JSON 备份或仍可用的同步服务。Android 卸载可能删除本机应用数据；同步不能替代独立备份。

## 许可证与贡献

项目自身代码、脚本和文档采用 Apache License 2.0，允许在遵守许可证条件的前提下使用、修改及分发。完整条款以 [LICENSE](LICENSE) 为准，版权说明见 [NOTICE](NOTICE)。第三方依赖不因本项目授权而改用 Apache-2.0，特别是桌面 Qt 运行库的 LGPL 等分发义务仍需独立履行。

问题报告应提供应用版本、系统版本、复现步骤、预期结果及脱敏后的错误信息。不得附带设备密钥、私人账本、服务器数据库或含凭证的配置。提交修改时应说明影响范围和验证结果；中文源码注释统一使用 UTF-8。
