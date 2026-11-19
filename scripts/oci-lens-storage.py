#!/usr/bin/env python3
"""Resolve Compose storage and make consistent SQLite backups for OCI Lens."""

import argparse
from contextlib import closing, contextmanager
import json
import shutil
import sqlite3
import stat
from pathlib import Path, PurePosixPath
import sys
import tempfile
import time


def compose_mount(service_name, default_database):
    config = json.load(sys.stdin)
    service = config["services"][service_name]
    mounts = [mount for mount in service.get("volumes", []) if mount["target"] == "/data"]
    if len(mounts) != 1:
        raise ValueError("服务必须有一个 /data 挂载。")
    mount = mounts[0]
    kind = mount["type"]
    if kind == "volume":
        source = config["volumes"][mount["source"]]["name"]
    elif kind == "bind":
        source = str(Path(mount["source"]).resolve())
    else:
        raise ValueError("/data 需要使用 Docker 数据卷或宿主机目录。")
    database = PurePosixPath((service.get("environment") or {}).get("OCI_MONITOR_DB") or default_database)
    if database.parent != PurePosixPath("/data") or not database.name:
        raise ValueError("SQLite 数据库应直接位于 /data 下。")
    secret = config.get("secrets", {}).get("admin_password", {}).get("file", "")
    if secret:
        secret = str(Path(secret).resolve())
    fields = (kind, source, str(database), service.get("image") or f"{config['name']}-{service_name}", secret)
    if any(any(character in value for character in "|\r\n") for value in fields) or "," in source:
        raise ValueError("部署路径包含 Docker 挂载或运维脚本不支持的分隔符。")
    print("|".join(fields))


@contextmanager
def open_source(database, offline):
    uri = database.absolute().as_uri() + "?mode=ro"
    if not offline:
        with closing(sqlite3.connect(uri, uri=True, timeout=5)) as source:
            yield source
        return

    # A closed WAL database may have no sidecars left and cannot create them
    # on a read-only bind. If recovery is needed, open a private copy instead.
    journals = [database.with_name(database.name + suffix) for suffix in ("-wal", "-journal")]
    present = [journal for journal in journals if journal.exists()]
    if not present:
        with closing(sqlite3.connect(uri + "&immutable=1", uri=True)) as source:
            yield source
        return

    with tempfile.TemporaryDirectory(prefix="oci-lens-recovery-") as workdir:
        copied = Path(workdir) / database.name
        shutil.copyfile(database, copied)
        for journal in present:
            shutil.copyfile(journal, Path(workdir) / journal.name)
        with closing(sqlite3.connect(copied)) as source:
            yield source


def inspect(database, offline):
    try:
        mode = database.stat().st_mode
    except FileNotFoundError:
        print("missing")
        return
    if not stat.S_ISREG(mode):
        raise ValueError("SQLite 路径需要指向文件。")
    with open_source(database, offline) as db:
        present = db.execute("SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'admin_user'").fetchone()
        if not present:
            if db.execute("SELECT 1 FROM sqlite_schema WHERE type = 'table' LIMIT 1").fetchone():
                raise ValueError("现有 SQLite 库缺少管理员表，无法判断安装状态。")
            print("uninitialized")
            return
        populated = db.execute("SELECT 1 FROM admin_user LIMIT 1").fetchone()
        print("initialized" if populated else "uninitialized")


def backup(database, offline):
    with tempfile.TemporaryDirectory(prefix="oci-lens-backup-") as workdir:
        snapshot = Path(workdir) / "snapshot.db"
        # A bound deadline avoids waiting indefinitely for a database writer.
        deadline = time.monotonic() + 30

        def progress(status, remaining, total):
            if time.monotonic() >= deadline:
                raise TimeoutError("SQLite 备份等待超时。")

        with open_source(database, offline) as source, closing(sqlite3.connect(snapshot)) as target:
            source.backup(target, pages=128, progress=progress, sleep=0.1)
            if target.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                raise ValueError("SQLite 备份未通过完整性检查。")
        with snapshot.open("rb") as saved:
            shutil.copyfileobj(saved, sys.stdout.buffer)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    mount = commands.add_parser("compose-mount")
    mount.add_argument("service")
    mount.add_argument("default_database")
    for command in ("inspect", "backup"):
        operation = commands.add_parser(command)
        operation.add_argument("--offline", action="store_true")
        operation.add_argument("database", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "compose-mount":
            compose_mount(args.service, args.default_database)
        elif args.command == "inspect":
            inspect(args.database, args.offline)
        else:
            backup(args.database, args.offline)
    except (KeyError, OSError, sqlite3.Error, TypeError, ValueError) as error:
        print(f"SQLite 运维失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
