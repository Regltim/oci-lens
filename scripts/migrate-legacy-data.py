#!/usr/bin/env python3
"""Import compatible historical records from an OCI ARM Monitor SQLite database."""

import argparse
import os
import sqlite3
import sys
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Dataset:
    table: str
    columns: tuple[str, ...]
    key: tuple[str, ...]
    filter: str = "1"


MANUAL = Dataset("manual_cost", (
    "id", "cost_name", "category", "amount", "currency", "occurred_on", "note", "created_at",
), ("id",))

CLOUD = (
    Dataset("cloud_instance", (
        "id", "display_name", "region", "compartment_id", "shape", "lifecycle_state", "ocpus",
        "memory_gb", "boot_volume_gb", "public_ip", "private_ip", "created_at", "updated_at",
    ), ("id",)),
    Dataset("metric_point", ("instance_id", "metric_name", "metric_value", "unit", "sampled_at"),
            ("instance_id", "metric_name", "sampled_at")),
    Dataset("cost_daily", ("service_name", "resource_id", "stat_date", "usage_amount", "usage_unit", "cost_amount", "currency"),
            ("service_name", "resource_id", "stat_date", "usage_unit", "currency")),
    Dataset("sync_run", ("id", "sync_type", "status", "message", "started_at", "finished_at", "instance_count",
                         "metric_count", "traffic_count", "cost_count"), ("id",),
            "status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at <> ''"),
)


def uri(path, mode):
    return f"{path.as_uri()}?mode={mode}"


def require_table(db, schema, table, columns):
    row = db.execute(f"SELECT type FROM {schema}.sqlite_master WHERE name = ?", (table,)).fetchone()
    present = {row[1] for row in db.execute(f"PRAGMA {schema}.table_info({table})")} if row == ("table",) else set()
    missing = set(columns) - present
    if missing:
        raise ValueError(f"{schema}.{table} 缺少迁移字段 {', '.join(sorted(missing))}")


def check_scope(db):
    if not any(db.execute(f"SELECT EXISTS (SELECT 1 FROM legacy.{dataset.table})").fetchone()[0] for dataset in CLOUD):
        return
    source = db.execute("SELECT DISTINCT region, compartment_id FROM legacy.cloud_instance").fetchall()
    row = db.execute("SELECT scope_key FROM main.oci_target WHERE id = 1").fetchone()
    scope = row[0].split("|") if row and isinstance(row[0], str) else []
    if len(scope) != 4 or not all(scope[:3]) or len(source) != 1 or source[0] != tuple(scope[1:3]):
        raise ValueError("无法确认两站使用相同的 OCI 区域和 Compartment。先配置同一采集范围，或使用 --manual-only 导入手工费用。")


def candidates(dataset):
    table = dataset.table
    projection = {
        "cost_daily": {"resource_id": "COALESCE(NULLIF(resource_id, ''), '-')", "cost_amount": "CAST(cost_amount AS TEXT)"},
        "sync_run": {"message": "'从旧站导入的同步记录'"},
    }.get(table, {})
    columns = ", ".join(f"{projection.get(col, col)} AS {col}" for col in dataset.columns)
    keys = ", ".join(dataset.key)
    if table == "cost_daily":
        # Existing cost rows or an already collected zero-cost day both take precedence.
        available = """s.stat_date NOT IN (SELECT stat_date FROM main.cost_daily) AND NOT EXISTS (
          SELECT 1 FROM main.source_coverage c WHERE c.source = 'cost' AND c.stat_date = s.stat_date AND c.status = 'COVERED'
        )"""
    else:
        matches = " AND ".join(f"t.{col} IS s.{col}" for col in dataset.key)
        available = f"NOT EXISTS (SELECT 1 FROM main.{table} t WHERE {matches})"
    return f"""
      SELECT {', '.join('s.' + col for col in dataset.columns)}
      FROM (
        SELECT incoming.*, MIN(legacy_row) AS first_row FROM (
          SELECT rowid AS legacy_row, {columns} FROM legacy.{table} WHERE {dataset.filter}
        ) incoming GROUP BY {keys}
      ) s
      WHERE {available}
    """


def backup_database(target):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = target.with_name(f"{target.name}.before-import-{stamp}.bak")
    fd = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    try:
        with closing(sqlite3.connect(uri(target, "ro"), uri=True)) as original, closing(sqlite3.connect(backup)) as copy:
            original.backup(copy)
    except (OSError, sqlite3.Error):
        backup.unlink(missing_ok=True)
        raise
    return backup


def migrate(source, target, apply, manual_only):
    source, target = source.resolve(strict=True), target.resolve(strict=True)
    if source.samefile(target):
        raise ValueError("源数据库和目标数据库必须是两个文件。")

    datasets = (MANUAL,) if manual_only else (MANUAL, *CLOUD)
    with closing(sqlite3.connect(uri(target, "rw" if apply else "ro"), uri=True)) as db, db:
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA trusted_schema = OFF")
        db.execute("ATTACH DATABASE ? AS legacy", (uri(source, "ro"),))
        db.execute("BEGIN IMMEDIATE" if apply else "BEGIN")
        require_table(db, "main", "oci_target", ("id", "scope_key"))
        require_table(db, "main", "source_coverage", ("source", "stat_date", "status"))
        for dataset in datasets:
            for schema in ("legacy", "main"):
                require_table(db, schema, dataset.table, dataset.columns)
        if not manual_only:
            check_scope(db)

        planned = {}
        for dataset in datasets:
            query = candidates(dataset)
            total = db.execute(f"SELECT COUNT(*) FROM legacy.{dataset.table}").fetchone()[0]
            eligible = db.execute(f"SELECT COUNT(*) FROM legacy.{dataset.table} WHERE {dataset.filter}").fetchone()[0]
            pending = db.execute(f"SELECT COUNT(*) FROM ({query})").fetchone()[0]
            planned[dataset.table] = pending
            print(f"{dataset.table}: 待导入 {pending}，重复或冲突 {eligible - pending}，排除 {total - eligible}")

        if not apply:
            print("预检完成。")
        elif any(planned.values()):
            backup = backup_database(target)
            print(f"目标库备份 {backup}", flush=True)
            for dataset in datasets:
                if not planned[dataset.table]:
                    continue
                result = db.execute(f"INSERT INTO main.{dataset.table} ({', '.join(dataset.columns)}) {candidates(dataset)}")
                if result.rowcount != planned[dataset.table]:
                    raise ValueError(f"{dataset.table} 的导入数量与预检不同。")
            db.commit()
            print(f"导入完成，共 {sum(planned.values())} 条。")
        else:
            print("没有新记录需要导入。")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="旧站数据库 .db 文件")
    parser.add_argument("target", type=Path, help="已初始化的 OCI Lens .db 文件")
    parser.add_argument("--manual-only", action="store_true", help="仅导入手工费用")
    parser.add_argument("--apply", action="store_true", help="备份并写入目标库，省略时只预检")
    args = parser.parse_args()
    try:
        migrate(args.source, args.target, args.apply, args.manual_only)
        return 0
    except (OSError, sqlite3.Error, ValueError) as error:
        print(f"迁移中止：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
