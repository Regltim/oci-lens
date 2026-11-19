# 从 OCI ARM Monitor 迁移

迁移工具直接读取旧站 SQLite，默认只预检。预检逐表列出源库和目标库条数、日期范围、待导入、冲突和排除数量。加 `--apply` 后先备份新库，再用一个事务导入。使用同一来源与主机标签重跑会跳过重复记录。

| 数据 | 迁移行为 |
| --- | --- |
| 手工费用 | 按记录 ID 导入，可通过 `--manual-only` 单独迁移 |
| 云实例及历史指标 | 要求新旧 OCI 区域和 Compartment 一致。保留新站已有指标，旧实例规格在历史实例中可查 |
| OCI 历史账单 | 保留原币种。已有账单或完成核对的日期沿用新站数据，旧账单记录不会标记为新站已核对 |
| 同步记录 | 导入已结束的记录和计数，使用统一的迁移说明 |
| 旧站网卡流量 | 按原采集窗口归档。在「用量与费用 → 流量 → 旧站采样」查看。旧记录可能包含不完整的一天 |
| 旧站主机采样 | 按旧主机标签归档。在「监控 → 本机 → 历史主机采样」选择来源及日期 |
| 管理账号、OCI 凭据、通知配置、公开日报链接、采集配额与同步计划 | 由新站独立配置 |

## 本地数据库文件

准备旧站 SQLite 的一致性备份，以及已用新版 OCI Lens 启动初始化的目标数据库。两站使用同一 OCI 租户时，先让新站以相同区域和 Compartment 启动一次，以便记录采集范围。正式写入前暂停新站后端。

在 OCI Lens 项目目录执行以下命令，将路径换成自己的数据库文件。工具使用 Python 3.9 或更新版本的标准库。

```sh
python3 scripts/migrate-legacy-data.py /path/old.db /path/oci-lens.db
python3 scripts/migrate-legacy-data.py /path/old.db /path/oci-lens.db --apply
```

OCI 采集范围有变化，或旧库没有足够的实例信息供工具核对时，可在两条命令后加 `--manual-only`。已有迁移可用同一对数据库重跑，预检会列出仍可导入的记录。也可用 `--host-only --host-label 原主机名` 单独归档旧主机记录。导入后重启 OCI Lens，在历史实例和两类历史采样中选择有记录的日期，在费用页选择有记录的月份。迁移前的备份保存在目标库旁，文件名包含 UTC 时间。需要回滚时，先停止新站后端，另存当前数据库及现有的 `-wal`、`-shm` 文件，然后将备份恢复为目标文件名，确认应用用户有读写权限后再启动。

## 同一主机上的 Docker Compose

把 `OLD_DIR` 设为旧站 Compose 所在目录。从新站目录运行，先读取两个实际卷名，再暂停后端。旧站的数据库文件名是 `oci-arm-cost-monitor.db`，新站是 `oci-lens.db`。

```sh
OLD_DIR=/path/to/oci-arm-monitor
OLD_CONTAINER=$(docker compose --project-directory "$OLD_DIR" ps -a -q oci-arm-monitor-server)
LENS_CONTAINER=$(docker compose ps -a -q server)
OLD_VOLUME=$(docker inspect "$OLD_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')
LENS_VOLUME=$(docker inspect "$LENS_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')
docker compose --project-directory "$OLD_DIR" stop oci-arm-monitor-server
docker compose stop server

run_import() {
  docker run --rm --network none --user 10001:10001 \
    --mount "type=volume,source=$OLD_VOLUME,target=/legacy,readonly" \
    --mount "type=volume,source=$LENS_VOLUME,target=/lens" \
    --mount "type=bind,source=$PWD/scripts/migrate-legacy-data.py,target=/import.py,readonly" \
    python:3.12-alpine python /import.py /legacy/oci-arm-cost-monitor.db /lens/oci-lens.db "$@"
}

run_import
```

确认预检数字后，执行 `run_import --apply`，再执行 `docker compose start server`。若旧站仍需要提供服务，可以用 `docker compose --project-directory "$OLD_DIR" start oci-arm-monitor-server` 恢复。两个项目默认使用 UID/GID 10001，如果构建时自定义过用户，请使用能够读取旧卷并写入新卷的实际身份。
