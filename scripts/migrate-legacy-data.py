#!/usr/bin/env python3
"""Import compatible historical records from an OCI ARM Monitor SQLite database."""

from __future__ import annotations

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
    source_table: str | None = None
    date_column: str = ""

    @property
    def source(self):
        return self.source_table or self.table



MANUAL = Dataset("manual_cost", (
    "id", "cost_name", "category", "amount", "currency", "occurred_on", "note", "created_at",
), ("id",), date_column="occurred_on")

HOST = Dataset("legacy_host_snapshot", ("host_label", "sampled_at", "cpu_usage_percent", "memory_usage_percent",
    "disk_usage_percent", "network_rx_bytes_per_second", "network_tx_bytes_per_second"),
    ("host_label", "sampled_at"), source_table="server_status_snapshot", date_column="sampled_at")

CLOUD = (
    Dataset("instance_history", ("id", "display_name", "region", "shape", "lifecycle_state", "ocpus", "memory_gb", "boot_volume_gb"),
            ("id",), source_table="cloud_instance"),
    Dataset("legacy_traffic_daily", ("instance_id", "stat_date", "ingress_gb", "egress_gb"),
            ("instance_id", "stat_date"), source_table="traffic_daily", date_column="stat_date"),
    Dataset("metric_point", ("instance_id", "metric_name", "metric_value", "unit", "sampled_at"),
            ("instance_id", "metric_name", "sampled_at"), date_column="sampled_at"),
    Dataset("cost_daily", ("service_name", "resource_id", "stat_date", "usage_amount", "usage_unit", "cost_amount", "currency"),
            ("service_name", "resource_id", "stat_date", "usage_unit", "currency"), date_column="stat_date"),
    Dataset("sync_run", ("id", "sync_type", "status", "message", "started_at", "finished_at", "instance_count",
                         "metric_count", "traffic_count", "cost_count"), ("id",),
            "status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at <> ''", date_column="finished_at"),
)


def uri(path, mode):
    return f"{path.as_uri()}?mode={mode}"


def require_table(db, schema, table, columns):
    row = db.execute(f"SELECT type FROM {schema}.sqlite_master WHERE name = ?", (table,)).fetchone()
    present = {row[1] for row in db.execute(f"PRAGMA {schema}.table_info({table})")} if row == ("table",) else set()
    missing = set(columns) - present
    if missing:
        raise ValueError(f"{schema}.{table} 缺少迁移字段 {', '.join(sorted(missing))}")


def check_scope(db, datasets):
    if not any(db.execute(f"SELECT EXISTS (SELECT 1 FROM legacy.{dataset.source})").fetchone()[0] for dataset in datasets):
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
        "legacy_host_snapshot": {"host_label": "?"},
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
        if table == "instance_history":
            available += " AND NOT EXISTS (SELECT 1 FROM main.cloud_instance t WHERE t.id = s.id)"
    return f"""
      SELECT {', '.join('s.' + col for col in dataset.columns)}
      FROM (
        SELECT incoming.*, MIN(legacy_row) AS first_row FROM (
          SELECT rowid AS legacy_row, {columns} FROM legacy.{dataset.source} WHERE {dataset.filter}
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


def date_range(db, table, date_column, condition="1", params=()):
    if not date_column:
        return ""
    first, last = db.execute(
        f"SELECT MIN(date({date_column})), MAX(date({date_column})) FROM {table} WHERE {condition}", params
    ).fetchone()
    return f"（{first} 至 {last}）" if first else ""


def migrate(source, target, apply, manual_only, host_only=False, host_label="旧站主机"):
    if not 1 <= len(host_label) <= 80 or not host_label.isprintable():
        raise ValueError("旧站主机标签须为 1 至 80 个可打印字符。")
    source, target = source.resolve(strict=True), target.resolve(strict=True)
    if source.samefile(target):
        raise ValueError("源数据库和目标数据库必须是两个文件。")

    datasets = (HOST,) if host_only else (MANUAL,) if manual_only else (MANUAL, *CLOUD, HOST)
    with closing(sqlite3.connect(uri(target, "rw" if apply else "ro"), uri=True)) as db, db:
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA trusted_schema = OFF")
        db.execute("ATTACH DATABASE ? AS legacy", (uri(source, "ro"),))
        db.execute("BEGIN IMMEDIATE" if apply else "BEGIN")
        require_table(db, "main", "oci_target", ("id", "scope_key"))
        require_table(db, "main", "source_coverage", ("source", "stat_date", "status"))
        if not host_only and not manual_only:
            # Some legacy versions did not collect host or traffic samples.
            known = {row[0] for row in db.execute("SELECT name FROM legacy.sqlite_master WHERE type = 'table'")}
            absent = [item for item in (HOST, CLOUD[1]) if item.source not in known]
            for item in absent:
                print(f"{item.source}: 源库无此数据表，跳过")
            datasets = tuple(item for item in datasets if item not in absent)
        for dataset in datasets:
            require_table(db, "legacy", dataset.source, tuple(col for col in dataset.columns if col != "host_label"))
            require_table(db, "main", dataset.table, dataset.columns)
        if not manual_only and not host_only:
            check_scope(db, tuple(item for item in datasets if item in CLOUD))

        planned = {}
        for dataset in datasets:
            query = candidates(dataset)
            total = db.execute(f"SELECT COUNT(*) FROM legacy.{dataset.source}").fetchone()[0]
            eligible = db.execute(f"SELECT COUNT(*) FROM legacy.{dataset.source} WHERE {dataset.filter}").fetchone()[0]
            params = (host_label,) if dataset == HOST else ()
            pending = db.execute(f"SELECT COUNT(*) FROM ({query})", params).fetchone()[0]
            planned[dataset.table] = pending
            target_filter = "host_label = ?" if dataset == HOST else "1"
            existing = db.execute(f"SELECT COUNT(*) FROM main.{dataset.table} WHERE {target_filter}", params).fetchone()[0]
            source_dates = date_range(db, f"legacy.{dataset.source}", dataset.date_column)
            target_dates = date_range(db, f"main.{dataset.table}", dataset.date_column, target_filter, params)
            print(f"{dataset.source} → {dataset.table}: 源库 {total}{source_dates}，目标库 {existing}{target_dates}，"
                  f"待导入 {pending}，已有键或被新账单覆盖 {eligible - pending}，排除 {total - eligible}")

        if not apply:
            print("预检完成。")
        elif any(planned.values()):
            backup = backup_database(target)
            print(f"目标库备份 {backup}", flush=True)
            for dataset in datasets:
                if not planned[dataset.table]:
                    continue
                params = (host_label,) if dataset == HOST else ()
                result = db.execute(
                    f"INSERT INTO main.{dataset.table} ({', '.join(dataset.columns)}) {candidates(dataset)}", params
                )
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
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--manual-only", action="store_true", help="仅导入手工费用")
    scope.add_argument("--host-only", action="store_true", help="仅归档旧站主机采样")
    parser.add_argument("--host-label", default="旧站主机", help="旧站主机归档标签，同一旧主机重跑请使用同一标签")
    parser.add_argument("--apply", action="store_true", help="备份并写入目标库，省略时只预检")
    args = parser.parse_args()
    try:
        migrate(args.source, args.target, args.apply, args.manual_only, args.host_only, args.host_label.strip())
        return 0
    except (OSError, sqlite3.Error, ValueError) as error:
        print(f"迁移中止：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
