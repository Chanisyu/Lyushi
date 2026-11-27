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

<p><a href="README.md">简体中文</a> | <strong>English</strong></p>

</div>

# Lyushi · 柳时

Lyushi is a local-first time tracker for Android, Windows and Ubuntu. It provides separate ledgers, task tags, distraction intervals, task resumption and water tracking. A self-hosted service offers synchronization, isolated user accounts and a web management console.

The project's source and documentation use [Apache-2.0](LICENSE). Third-party components retain their licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md).

## Downloads and compatibility

[v1.0.0 stable release](https://github.com/Chanisyu/Lyushi/releases/tag/v1.0.0) · [All releases](https://github.com/Chanisyu/Lyushi/releases) · [Changelog](CHANGELOG.md)

Lyushi **1.0.0** supports standalone use and self-hosted synchronization. Time ledgers use synchronization protocol **4**, and water tracking uses protocol **1**. Install the Linux server to enable device synchronization and the web console.

| Platform | Download | Installation |
| --- | --- | --- |
| Windows 11 x64 | [Windows ZIP](https://github.com/Chanisyu/Lyushi/releases/download/v1.0.0/lyushi-windows-1.0.0-x64.zip) | Extract the whole archive and run `Lyushi-1.0.0/lyushi.exe` |
| Android 8.0 or later | [Android APK](https://github.com/Chanisyu/Lyushi/releases/download/v1.0.0/lyushi-android-1.0.0.apk) | Open the APK in the system file manager to install it |
| Ubuntu 26.04 LTS amd64 | [Ubuntu DEB](https://github.com/Chanisyu/Lyushi/releases/download/v1.0.0/lyushi_1.0.0_amd64.deb) | Run `sudo apt install ./lyushi_1.0.0_amd64.deb` |
| Linux amd64 server | [Server ZIP](https://github.com/Chanisyu/Lyushi/releases/download/v1.0.0/lyushi-server-1.0.0-linux-amd64.zip) | Extract the archive and follow the server setup instructions below |

Verify the downloads with [SHA256SUMS.txt](https://github.com/Chanisyu/Lyushi/releases/download/v1.0.0/SHA256SUMS.txt). Local build artifacts are generated in `dist/` or the platform's build output directory.

**Migrating from Android previews.** The stable APK uses a dedicated release signing key. Finish any active timer and export your time ledger and water-tracking data as separate JSON files. If you use synchronization, confirm that your pending changes have reached the server. Uninstall the debug-signed preview, install the stable APK, then restore the local backups or reconnect the synchronized accounts. Uninstalling removes the old app's local data and stored device keys.

## Tracking and distractions

Create a ledger, enter an activity, optionally select a tag, and start tracking. Stop the entry when finished. Closing the application does not end an active task. Ledger switching, account switching, details and file operations are unavailable while recording.

While tracking, select **修改** (Edit) next to the activity to change its name and existing tag, then select **保存** (Save). Tracking continues while the form is open. Focus edits apply to all focus intervals of the same task; distraction edits apply only to the current distraction. Record identity, ledger, timestamps and interval boundaries remain unchanged. Tag creation and deletion remain available only while idle.

If another device changes, stops or switches the task's phase, the editor retains its draft and disables stale saving. Android retains drafts across screen rotation, including the originally captured version. Cancelling or fully exiting discards unsaved input. See the [active-task editing specification](docs/active-task-editing.md) in Chinese.

Select “分心一下” to record a temporary distraction. The original task continues until the form is confirmed; cancellation makes no change. A blank name defaults to “分心”, with no tag. “结束分心并继续” immediately restores the original task and tag. “结束全部” finishes the whole task after confirmation. Repeated distractions are supported, with one nesting level.

Effective duration includes focus intervals only: 20 minutes of focus, 5 minutes of distraction and another 15 minutes of focus yield 35 effective minutes. History retains one task summary with editable intervals. Focus metadata is shared; distraction metadata is independent. Timestamps may contain gaps but cannot overlap or run backwards. See [Distraction rules](docs/distraction-and-resume.md).

Dates use Beijing Time (UTC+8); storage uses UTC milliseconds. Device clock changes can affect intervals and invalid backwards timestamps are rejected.

## Accounts and synchronization

1. Sign in to the web console and add a device under the intended account.
2. Enter the server address, such as `https://time.example.com/lyushi-api`, and the device key in the client settings.
3. Select “验证并连接账户”. The client verifies ownership before sending business data.
4. When connecting from standalone mode, explicitly choose whether to merge existing standalone data.
5. Use the saved-account selector to switch between isolated local databases. Switching requires an idle current account and works offline for previously saved accounts.

Changing a key within the same user preserves that user's local data. Different users or server identities use different databases, queues and credentials. Legacy single-user data is not uploaded automatically; export JSON in the old client and explicitly import it into the chosen account when needed.

Device keys use Windows DPAPI, Android Keystore or Linux Secret Service. Ubuntu requires `libsecret-tools` and an unlocked keyring. Appearance remains local.

## Web console

The console is served at `https://time.example.com/lyushi/`. Static HTML, CSS and JavaScript are embedded in the same Go executable; no additional frontend process or CDN is required.

| Operation | Administrator | Ordinary user |
| --- | --- | --- |
| Create, disable or delete ordinary accounts | All | No |
| Manage ledgers, tasks, tags and distraction intervals | Any selected user | Own account |
| Create, rotate and revoke device keys | Any selected user | Own devices |
| JSON import/export and CSV export | Per selected user | Own account |
| View conflict archives | Per selected user | Own account |
| View administrative audit logs | Yes | No |
| Change own password and display name | Yes | Yes |

A single administrator, `admin`, is initialized locally. Accounts are created by the administrator; public registration is unavailable. Usernames contain 3–32 letters, digits, dots, underscores or hyphens and are normalized to lowercase. Display names support Chinese.

Initial passwords must be changed at first login. Passwords require at least 12 characters and at most 256 bytes. Forgotten ordinary-user passwords are reset by the administrator; administrator passwords can be reset over SSH using the local command below.

Each account allows ten active devices. Device keys are displayed once, and rotation immediately invalidates the old key. Disabling an account preserves data while revoking devices and sessions; enabling it does not restore old keys. Permanent deletion requires username confirmation and removes the user's business databases, history and devices. Existing offline copies and backups are not remotely erased.

The web console manages completed entries and displays active tasks read-only. Start, distraction, resumption and stop operations remain in the native clients. Active entries and ledgers containing active entries cannot be deleted. Import/export requires no active tasks. Stale edits are rejected; conflicting versions are retained.

Audit metadata is retained for 180 days without plaintext passwords or device keys. See [Account management guide](docs/account-management.md), maintained in Chinese.

## Deployment

The service uses Go and SQLite, binds to `127.0.0.1:8093`, and is exposed through Caddy HTTPS. Docker and a separate database server are unnecessary. Business data is readable by the server; end-to-end encryption is not provided.

For a new deployment, extract the server ZIP and run the following commands from its root directory:

```bash
sudo useradd --system --home-dir /var/lib/lyushi --shell /usr/sbin/nologin lyushi
sudo install -d -m 0755 /opt/lyushi
sudo install -d -m 0700 -o lyushi -g lyushi /var/lib/lyushi
sudo install -m 0755 lyushi-server /opt/lyushi/lyushi-server
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --init-admin --password-file /var/lib/lyushi/admin-initial-password.txt
sudo install -m 0644 lyushi.service /etc/systemd/system/lyushi.service
sudo systemctl daemon-reload
sudo systemctl enable --now lyushi
```

The password file must not already exist and is created with mode 0600. Read it through a trusted terminal, change the password on first login, and remove the handoff file when appropriate. Never distribute credentials in source packages or issue reports.

Merge [the Caddy example](deploy/Caddyfile.example) into your site configuration and replace the example domain. Validate configuration before reloading. The browser uses `/lyushi/`; native clients use `/lyushi-api`. Health checks report `protocol=4`, `minimumClient=0.6.0`.

Administrator password reset:

```bash
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --reset-admin-password --password-file /var/lib/lyushi/admin-reset-password.txt
```

## Upgrades and backups

When migrating from protocol 3, update the server and all clients together. `deploy/upgrade-multiuser.sh` performs a one-time **blank initialization** from protocol 3: it creates a rollback backup, clears legacy business data and devices, creates an empty administrator and configures the console. Export legacy JSON first if it must be retained, then explicitly import it into the intended new account. The script refuses to reset an initialized protocol 4 service.

For an existing protocol 4 deployment using the default service paths, extract the server ZIP and run `sudo bash ./upgrade-server.sh "$PWD"` from its root directory. The script backs up the service and databases, then verifies account ownership, devices and history. After the upgrade, the health endpoint should report `protocol=4` and `waterProtocol=1`.

User JSON backups use format 3 and remain compatible with imports from formats 1 and 2. Merge imports retain conflicting content and do not restart archived timers or automatically revive deleted entries. Native import limits are 32 MiB; the web limit is 8 MiB. CSV contains one row per interval, including a shared task ID, interval index and type; filter by “专注” for effective time.

Complete server backup:

```bash
sudo -u lyushi /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite \
  --backup /var/lib/lyushi/backups/server-backup.zip
```

The destination must not exist. This archive contains the catalog and every user database. Keep an external copy. `scripts/restore-server.py` validates and restores an archive to a new directory, rotates server identities, and invalidates old sessions and devices. Re-login, password changes and new device keys are required after recovery. See [Recovery instructions](docs/account-management.md#完整服务器备份与恢复).

## Modules

| Module | Responsibility |
| --- | --- |
| Native client UI | Tracking, distraction, history and settings |
| `Profiles.kt`, `profiles.cpp` | Verified account ownership, independent databases and secure credentials |
| Client Store / SyncStore / SyncClient | Transactions, outbox, identity binding, pagination and conflicts |
| `server/accounts.go` | Users, devices, passwords, status and per-user SQLite databases |
| `server/accounts_http.go` | Sessions, same-origin checks, CSRF, login limits and authorization |
| `console_data.go`, `console_backup.go`, `catalog_backup.go` | Web changes, per-user exports and complete backups |
| `server/main.go`, `focus.go` | Immutable versions, synchronization and interval validation |
| `server/web/` | Lightweight Chinese console and responsive forms |

## Building from source

### Build requirements

| Component | Requirements or verified versions |
| --- | --- |
| Desktop | C++17, CMake 3.22+, Qt 6 Widgets / Sql / Network, and a compatible C++ compiler |
| Qt | 6.10.1 verified on Windows; 6.10.2 verified on Ubuntu |
| Android | JDK 17, Android SDK API 37, Gradle 9.6.0, Android Gradle Plugin 9.4.0 |
| Go service | Go 1.26+; Go 1.27.3 verified; dependencies pinned in `go.mod` and `go.sum` |

### Windows desktop

Install Qt 6, Ninja, CMake, and a compatible MinGW toolchain. Run in PowerShell, replacing the example paths with actual installation paths:

```powershell
./scripts/package-windows.ps1 -QtDirectory 'C:/Qt/6.10.1/mingw_64' -ToolchainDirectory 'C:/Qt/Tools/mingw1310_64'
```

The script uses MinGW's `objdump.exe` to inspect DLL dependencies recursively and `windeployqt.exe` to collect runtime libraries. Qt and the compiler must be compatible distributions. Paths can also be supplied through `LYUSHI_QT_DIR` and `LYUSHI_TOOLCHAIN_DIR`. This packaging script targets MinGW; MSVC builds require the matching Qt distribution and deployment tools.

### Ubuntu desktop

```bash
sudo apt update
sudo apt install build-essential cmake ninja-build qt6-base-dev \
  qt6-base-dev-tools libqt6sql6-sqlite qt6-qpa-plugins libsecret-tools
cmake -S apps/desktop -B build/desktop-linux -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/desktop-linux --parallel 2
./build/desktop-linux/lyushi
```

To produce a DEB package, run `bash scripts/package-linux.sh` on Ubuntu. It builds and packages the application without installing or replacing system programs.

### Android

Install the JDK, Gradle, and Android SDK versions listed above, set `ANDROID_HOME`, and run from the project root:

```bash
gradle -p apps/android :app:assembleDebug --console=plain
```

The output is `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Install the matching Gradle version or configure it through Android Studio. Use `:app:assembleRelease` for the release build and sign the APK with a private release key. Store signing keys and passwords outside the repository.

### Cross-compile the server

Build a Linux amd64 executable on Linux:

```bash
mkdir -p build
cd server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o ../build/lyushi-server-linux-amd64 .
```

On Windows, run `./scripts/package-server.ps1 -GoExecutable <path-to-go.exe> -Version 1.0.0` to cross-compile and package the Linux amd64 server. The ZIP includes deployment configuration, the upgrade script and licenses for the linked Go dependencies.

## Water tracking

Open **饮水记录** (Water tracking) from the main window during or between tasks. Set the bottle capacity, select refill, drink or pour, drag the blue waterline, and confirm. The preview shows the volume change and projected remaining volume. Drinking and manual additions count toward the daily total in Beijing time.

The native clients support calibration, undo, daily history, corrections, voids, JSON merge and CSV export. A stale offline water level enters review and may be recorded as historical intake after confirmation. The web console provides daily history and authorized account-level corrections. Version 0.8.0 introduces an independent water endpoint enabled by the corresponding server update. Time tracking continues to use protocol 4. See the [water tracking guide](docs/water-tracking.md) in Chinese.

## Running tests

The project includes server unit tests, desktop storage and recovery self-tests, local synchronization tests with two clients, and Android storage-protocol checks. Run tests with isolated databases and dedicated test devices.

Use `scripts/test-active-editor-android.ps1 -Serial <device-serial> -AndroidSdk <SDK-path>` with a debug APK to verify the editor, actual screen rotation and process restart. The test uses separate preferences and databases without reading regular account credentials.

Server unit tests:

```bash
cd server
go test ./...
```

Isolated desktop self-test on Linux:

```bash
QT_QPA_PLATFORM=offscreen ./build/desktop-linux/lyushi --self-test "$(mktemp -d)"
QT_QPA_PLATFORM=offscreen ./build/desktop-linux/lyushi --active-editor-test "$(mktemp -d)"
QT_QPA_PLATFORM=offscreen ./build/desktop-linux/lyushi --water-self-test "$(mktemp -d)"
```

For local Windows integration testing, first build `build/desktop-windows/lyushi.exe` and `build/lyushi-server.exe`, then run:

```powershell
./scripts/test-sync-windows.ps1 -QtDirectory 'C:/Qt/6.10.1/mingw_64' -ToolchainDirectory 'C:/Qt/Tools/mingw1310_64'
```

The script uses a random loopback port, an isolated database, and a temporary test key. It does not access the production server. Android checks use the debug APK and can be run with `scripts/check-android.ps1 -Serial <adb-device-id> -AndroidSdk <sdk-path>` on an authorized test device.

## Troubleshooting

**Does tracking continue after closing the application?**

The unfinished state is retained. Elapsed time is calculated from the saved start timestamp; a background process does not need to run every second.

**Why are ledger switching and details unavailable?**

Finish active entries first. Concurrent offline activity can produce multiple unfinished versions that require individual review.

**The application reports an invalid or revoked device key (`设备密钥无效或已撤销`).**

Check the server address, confirm that the complete key was entered rather than the device ID, and verify that the device has not been revoked.

**The application reports that data is saved locally and awaiting reconnection (`本机已保存，等待重连`).**

Entries remain on the device. Check connectivity, DNS, HTTPS certificates, and server status, then synchronize again. Clients do not ignore certificate errors to establish a connection.

**Ubuntu cannot save the key.**

Check that `secret-tool` is available and the desktop Secret Service is running with an unlocked keyring. The application does not fall back to plaintext storage.

**Importing JSON creates retained versions (`保留的不同版本`).**

The backup and local data contain differing versions, so both are preserved. Viewing conflicts neither overwrites an entry nor starts a historical timer.

**Can data be recovered after uninstalling?**

Recovery requires a saved JSON backup or an available synchronization service. Android uninstallations may remove local application data. Synchronization does not replace an independent backup.

## License and contributions

The project's own code, scripts, and documentation are licensed under the Apache License 2.0, allowing use, modification, and redistribution subject to its terms. See [LICENSE](LICENSE) for the complete terms and [NOTICE](NOTICE) for copyright information. Third-party dependencies are not relicensed under Apache-2.0; in particular, applicable LGPL and other redistribution requirements for Qt remain in force.

Issue reports should include the application version, OS version, reproduction steps, expected behavior, and sanitized error messages. Do not attach device keys, private ledgers, server databases, or credential-bearing configurations. Contributions should describe their scope and verification results. Chinese source comments must use UTF-8 encoding.
