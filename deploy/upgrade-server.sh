#!/usr/bin/env bash
# 已初始化多用户服务的常规升级：保留所有账户、设备及数据，不执行清空或重新初始化。
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Root privileges required.' >&2; exit 1; }
stage_dir=$(cd -- "${1:?Usage: upgrade-server.sh STAGING_DIRECTORY}" && pwd)
test -f "$stage_dir/lyushi-server"
test -f "$stage_dir/lyushi.service"
python3 - <<'PY'
import json
from urllib.request import urlopen
with urlopen('http://127.0.0.1:8093/health',timeout=10) as response:
    assert json.load(response)['protocol']==4, 'Use the explicit single-user migration procedure for older protocols.'
PY
stamp="multiuser-update-$(date -u +%Y%m%dT%H%M%S)-$$"
binary_backup="/opt/lyushi/lyushi-server.$stamp.previous"
unit_backup="/opt/lyushi/lyushi.$stamp.service.previous"
database_backup="/var/lib/lyushi/backups/$stamp.zip"
install -d -m 0700 -o lyushi -g lyushi /var/lib/lyushi/backups
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
    echo 'Upgrade failed; previous executable restored. User databases retained.' >&2
}
trap rollback ERR
systemctl stop lyushi
runuser -u lyushi -- /opt/lyushi/lyushi-server --db /var/lib/lyushi/sync.sqlite --backup "$database_backup"
install -m 0755 "$stage_dir/lyushi-server" /opt/lyushi/lyushi-server
install -m 0644 "$stage_dir/lyushi.service" /etc/systemd/system/lyushi.service
systemctl daemon-reload
systemctl start lyushi
for attempt in {1..30}; do
    if curl --fail --silent http://127.0.0.1:8093/health > /dev/null; then break; fi
    sleep 0.2
done
# 逐库检查快照中的不可变历史及用户、密钥归属；不打印业务正文、摘要或会话令牌。
python3 - "$database_backup" <<'PY'
import json,sqlite3,sys,tempfile,zipfile
from pathlib import Path
from urllib.request import urlopen
with tempfile.TemporaryDirectory(prefix='lyushi-upgrade-check-') as temporary:
    directory=Path(temporary)
    with zipfile.ZipFile(sys.argv[1]) as archive:
        manifest=json.loads(archive.read('manifest.json'))
        for name in ['catalog.sqlite']+['accounts/'+uid+'.sqlite' for uid in manifest['accounts']]:
            assert name=='catalog.sqlite' or (len(Path(name).stem)==64 and all(c in '0123456789abcdef' for c in Path(name).stem))
            old_path=directory/name
            old_path.parent.mkdir(parents=True,exist_ok=True)
            old_path.write_bytes(archive.read(name))
            current_path=Path('/var/lib/lyushi/sync.sqlite') if name=='catalog.sqlite' else Path('/var/lib/lyushi')/name
            old=sqlite3.connect(old_path)
            current=sqlite3.connect(current_path.as_uri()+'?mode=ro',uri=True)
            assert current.execute('PRAGMA quick_check').fetchone()[0]=='ok'
            assert old.execute("SELECT value FROM meta WHERE key='serverId'").fetchone()==current.execute("SELECT value FROM meta WHERE key='serverId'").fetchone()
            for version,operation in old.execute('SELECT id,operation FROM versions'):
                assert current.execute('SELECT operation FROM versions WHERE id=?',(version,)).fetchone()==(operation,)
            # 饮水仍存于用户库。只验证升级前已存在的日志，不读取或打印内容。
            if old.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='water_log'").fetchone():
                for operation_id,command,change in old.execute('SELECT id,command,change FROM water_log'):
                    assert current.execute('SELECT command,change FROM water_log WHERE id=?',(operation_id,)).fetchone()==(command,change)
            if name=='catalog.sqlite':
                for user in old.execute('SELECT id,username,role FROM users'):
                    assert current.execute('SELECT username,role FROM users WHERE id=?',(user[0],)).fetchone()==user[1:]
                for device in old.execute('SELECT id,user_id,token_hash,revoked FROM devices'):
                    assert current.execute('SELECT user_id,token_hash,revoked FROM devices WHERE id=?',(device[0],)).fetchone()==device[1:]
            old.close();current.close()
with urlopen('http://127.0.0.1:8093/health',timeout=10) as response:
    health=json.load(response)
assert health['protocol']==4 and health['minimumClient']=='0.6.0' and health['waterProtocol']==1
print(json.dumps({'passed':True,'protocol':4,'waterProtocol':1,'allAccountDatabasesVerified':True,'identitiesAndDevicesPreserved':True}))
PY
trap - ERR
systemctl is-active lyushi
printf 'Complete database backup: %s\n' "$database_backup"
