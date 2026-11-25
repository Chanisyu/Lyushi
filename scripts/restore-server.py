#!/usr/bin/env python3
"""将可信完整备份恢复至新目录；校验条目、归属与数据库完整性，不覆盖现有数据。"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import zipfile

def restore(archive_path: Path, destination: Path) -> None:
    # 仅创建新的目标目录。既有目录即使为空也不复用，防止误覆盖运行中的服务。
    if destination.exists():
        raise ValueError("恢复目标必须为尚不存在的新目录")
    expected_parent=destination.parent.resolve()
    expected_path=expected_parent/destination.name
    if destination.resolve()!=expected_path or destination.is_symlink():
        raise ValueError("恢复目录解析结果无效")
    with zipfile.ZipFile(archive_path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        ids = manifest.get("accounts")
        if manifest.get("format") != "lyushi.server.backup" or manifest.get("version") != 1 or not isinstance(ids, list):
            raise ValueError("不支持的完整备份格式")
        if len(ids) != len(set(ids)) or not all(isinstance(uid,str) and re.fullmatch(r"[0-9a-f]{64}", uid) for uid in ids):
            raise ValueError("备份账户编号无效")
        expected = {"catalog.sqlite", "manifest.json"} | {f"accounts/{uid}.sqlite" for uid in ids}
        names = archive.namelist()
        if set(names) != expected or len(names) != len(expected):
            raise ValueError("备份包含重复、缺失或未经允许的文件")
        destination.mkdir(mode=0o700, parents=False)
        try:
            (destination / "accounts").mkdir(mode=0o700)
            for name in expected - {"manifest.json"}:
                target = destination / name
                # 文件名来自固定白名单及已验证的十六进制编号，不使用任意压缩包路径。
                with archive.open(name) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                os.chmod(target, 0o600)
                with sqlite3.connect(target) as db:
                    if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                        raise ValueError("数据库完整性检查失败")
                    db.execute("UPDATE meta SET value=? WHERE key='serverId'", (secrets.token_hex(32),))
            with sqlite3.connect(destination / "catalog.sqlite") as db:
                actual = {row[0] for row in db.execute("SELECT id FROM users WHERE deleted=0")}
                if actual != set(ids):
                    raise ValueError("账户归属与备份清单不一致")
                # 回退备份不应复活已撤销的凭证。恢复后重新登录、改密并创建设备。
                db.execute("DELETE FROM sessions")
                db.execute("UPDATE devices SET revoked=1")
                db.execute("UPDATE users SET must_change=1,auth_version=auth_version+1")
        except Exception:
            # 只移除本次新建且已解析验证的目标目录；不触及原服务目录或备份。
            if destination.resolve()==expected_path and not destination.is_symlink():
                shutil.rmtree(expected_path)
            raise

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    try:
        restore(arguments.archive, arguments.destination)
    except Exception as error:
        import sys
        print("恢复失败："+str(error),file=sys.stderr)
        raise SystemExit(1)
    print("完整备份已恢复；请检查权限、重新登录并创建设备密钥。")
