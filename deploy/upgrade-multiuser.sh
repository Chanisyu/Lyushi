#!/usr/bin/env bash
# 一次性将协议 3 单用户服务升级为协议 4。明确清空旧个人业务数据，初始化空白管理员。
# 升级前保留旧程序、服务单元、Caddy 配置和一致性数据库备份；失败恢复旧服务。
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Root privileges required.' >&2; exit 1; }
stage=$(cd -- "${1:?Usage: upgrade-multiuser.sh STAGING_DIRECTORY}" && pwd)
test -f "$stage/lyushi-server"
test -f "$stage/lyushi.service"
python3 - <<'PY'
import json
from urllib.request import urlopen
from pathlib import Path
with urlopen('http://127.0.0.1:8093/health',timeout=10) as response:
    assert json.load(response)['protocol']==3, 'Only the existing protocol 3 single-user service may be reset.'
assert Path('/etc/caddy/Caddyfile').read_text().count('handle_path /lyushi-api/*')==1
PY
stamp="multiuser-0.6.0-$(date -u +%Y%m%dT%H%M%S)-$$"
backup="/var/lib/lyushi/backups/$stamp.sqlite"
password="/var/lib/lyushi/admin-initial-$stamp.txt"
old_binary="/opt/lyushi/lyushi-server.$stamp.previous"
old_unit="/opt/lyushi/lyushi.$stamp.service.previous"
old_caddy="/opt/lyushi/Caddyfile.$stamp.previous"
install -d -m 0700 -o lyushi -g lyushi /var/lib/lyushi/backups
runuser -u lyushi -- /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite --backup "$backup"
cp -a /opt/lyushi/lyushi-server "$old_binary"
cp -a /etc/systemd/system/lyushi.service "$old_unit"
cp -a /etc/caddy/Caddyfile "$old_caddy"
systemd-analyze verify "$stage/lyushi.service"
rollback() {
    trap - ERR
    systemctl stop lyushi || true
    # 路径全部为固定服务文件，只恢复本次备份，不删除其他数据库或历史备份。
    rm -f /var/lib/lyushi/sync.sqlite-wal /var/lib/lyushi/sync.sqlite-shm
    install -o lyushi -g lyushi -m 0600 "$backup" /var/lib/lyushi/sync.sqlite
    install -m 0755 "$old_binary" /opt/lyushi/lyushi-server
    install -m 0644 "$old_unit" /etc/systemd/system/lyushi.service
    cp -a "$old_caddy" /etc/caddy/Caddyfile
    systemctl daemon-reload
    systemctl restart lyushi
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && systemctl reload caddy
    echo 'Upgrade failed; previous database, executable and configuration restored.' >&2
}
trap rollback ERR
systemctl stop lyushi
install -m 0755 "$stage/lyushi-server" /opt/lyushi/lyushi-server
runuser -u lyushi -- /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite --init-admin --reset-personal-data --password-file "$password"
install -m 0644 "$stage/lyushi.service" /etc/systemd/system/lyushi.service
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/caddy/Caddyfile')
s=p.read_text()
needle='handle_path /lyushi-api/*'
assert s.count(needle)==1
prefix='''handle /lyushi {
        redir /lyushi/ 308
    }
    handle_path /lyushi/* {
        reverse_proxy 127.0.0.1:8093
    }
    '''
p.write_text(s.replace(needle,prefix+needle,1))
PY
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl daemon-reload
systemctl start lyushi
for attempt in {1..30}; do
    if curl --fail --silent http://127.0.0.1:8093/health > /dev/null; then break; fi
    sleep 0.2
done
python3 - "$backup" <<'PY'
import json,sqlite3,sys
from urllib.request import urlopen
old=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True)
new=sqlite3.connect('file:/var/lib/lyushi/sync.sqlite?mode=ro',uri=True)
assert new.execute('PRAGMA quick_check').fetchone()[0]=='ok'
assert old.execute("SELECT value FROM meta WHERE key='serverId'").fetchone()!=new.execute("SELECT value FROM meta WHERE key='serverId'").fetchone()
for table in ('versions','heads','changes','devices'):
    assert new.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]==0
assert new.execute('SELECT username,role,enabled,must_change FROM users').fetchall()==[('admin','admin',1,1)]
with urlopen('http://127.0.0.1:8093/health',timeout=10) as response:
    health=json.load(response)
assert health['protocol']==4 and health['minimumClient']=='0.6.0'
print(json.dumps({'passed':True,'legacyPersonalDataCleared':True,'legacyDeviceKeysInvalidated':True,'blankAdminInitialized':True,'protocol':4}))
PY
systemctl reload caddy
trap - ERR
systemctl is-active lyushi
printf 'Rollback backup: %s\nAdministrator temporary password file: %s\n' "$backup" "$password"
