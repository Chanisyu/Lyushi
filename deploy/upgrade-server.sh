#!/usr/bin/env bash
# 在既有服务器上更新程序与 systemd 单元；仅限管理员人工审核后以 root 执行。
# 参数为包含 lyushi-server 和 lyushi.service 的暂存目录，不初始化或覆盖业务数据库。
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Root privileges required.' >&2; exit 1; }
stage_dir=$(cd -- "${1:?Usage: upgrade-server.sh STAGING_DIRECTORY}" && pwd)
test -f "$stage_dir/lyushi-server"
test -f "$stage_dir/lyushi.service"
test -x /opt/lyushi/lyushi-server
test -f /var/lib/lyushi/sync.sqlite
stamp="tags-0.4.0-$(date -u +%Y%m%dT%H%M%S)-$$"
binary_backup="/opt/lyushi/lyushi-server.$stamp.previous"
unit_backup="/opt/lyushi/lyushi.$stamp.service.previous"
database_backup="/var/lib/lyushi/backups/$stamp.sqlite"

# 一致性备份由旧版本程序生成；先验证备份与服务单元，成功后才停止原服务。
install -d -m 0700 -o lyushi -g lyushi /var/lib/lyushi/backups
runuser -u lyushi -- /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite --backup "$database_backup"
cp -a /opt/lyushi/lyushi-server "$binary_backup"
cp -a /etc/systemd/system/lyushi.service "$unit_backup"
systemd-analyze verify "$stage_dir/lyushi.service"
rollback() {
    trap - ERR
    systemctl stop lyushi || true
    install -m 0755 "$binary_backup" /opt/lyushi/lyushi-server
    install -m 0644 "$unit_backup" /etc/systemd/system/lyushi.service
    systemctl daemon-reload
    systemctl restart lyushi
    echo 'Deployment failed; previous executable and unit restored. Database retained.' >&2
}
trap rollback ERR
systemctl stop lyushi
install -m 0755 "$stage_dir/lyushi-server" /opt/lyushi/lyushi-server
install -m 0644 "$stage_dir/lyushi.service" /etc/systemd/system/lyushi.service
systemctl daemon-reload
systemctl start lyushi
for attempt in {1..30}; do
    if curl --fail --silent http://127.0.0.1:8093/health > /dev/null; then break; fi
    sleep 0.2
done

# 核对全部既有不可变操作和设备摘要，不向日志输出任务正文或凭证。
python3 - "$database_backup" <<'PY'
import json
import sqlite3
import sys
from pathlib import Path
from urllib.request import urlopen

backup = sqlite3.connect(Path(sys.argv[1]).as_uri() + '?mode=ro', uri=True)
current = sqlite3.connect('file:/var/lib/lyushi/sync.sqlite?mode=ro', uri=True)
assert current.execute('PRAGMA quick_check').fetchone()[0] == 'ok'
identity = "SELECT value FROM meta WHERE key='serverId'"
assert backup.execute(identity).fetchone() == current.execute(identity).fetchone()
for entry_id, operation in backup.execute('SELECT id,operation FROM versions'):
    assert current.execute('SELECT operation FROM versions WHERE id=?', (entry_id,)).fetchone() == (operation,)
for device_id, digest, revoked in backup.execute('SELECT id,token_hash,revoked FROM devices'):
    assert current.execute('SELECT token_hash,revoked FROM devices WHERE id=?', (device_id,)).fetchone() == (digest, revoked)
with urlopen('http://127.0.0.1:8093/health', timeout=10) as response:
    health = json.load(response)
assert health['protocol'] == 2 and health['minimumClient'] == '0.4.0'
print(json.dumps({'passed': True, 'protocol': 2, 'databaseIntegrity': True,
                  'serverIdentityPreserved': True, 'immutableHistoryPreserved': True,
                  'deviceKeysPreserved': True}))
PY
trap - ERR
systemctl is-active lyushi
printf 'Database backup: %s\n' "$database_backup"
