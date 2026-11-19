#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
storage_helper="$root/scripts/oci-lens-storage.py"
target_db=/data/oci-lens.db
legacy_db=/data/oci-arm-cost-monitor.db
helper_image=python:3.12-alpine
wait_seconds=180
lock_path="$root/.runtime/oci-lens-ops.lock"
resume_server=false
temporary_file=
legacy_snapshot=

usage() {
  cat <<'HELP'
用法：bash scripts/oci-lens.sh <命令>

  init                                准备初始化文件
  start                               使用已有镜像启动并等待健康
  rebuild [--no-cache]                构建镜像、备份已有数据库并启动
  stop                                停止服务，保留容器和数据卷
  status                              查看服务及健康状态
  logs [server|web]                   查看最近 100 行日志
  doctor                              检查 Docker、配置与数据挂载
  backup                              备份现有 SQLite 到 backups/
  migrate-legacy --old-dir 目录 [--apply] [--host-label 标签]
                [--manual-only | --host-only]
                                      默认只预检旧 OCI ARM Monitor 数据
  reset-password [--username 当前账号名] [--generate]
                                      备份后重置已有管理员密码
HELP
}

fail() { printf '错误：%s\n' "$1" >&2; exit 1; }

compose_in() {
  local directory=$1
  shift
  docker compose --project-directory "$directory" "$@"
}

compose() { compose_in "$root" "$@"; }

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$resume_server" == true ]]; then
    if ! compose start --wait --wait-timeout "$wait_seconds" server; then
      printf '错误：后端未恢复健康，请查看服务状态和日志。\n' >&2
      status=1
    fi
  fi
  if [[ -n "$temporary_file" ]]; then rm -f -- "$temporary_file"; fi
  if [[ -n "$legacy_snapshot" ]]; then rm -f -- "$legacy_snapshot"; fi
  rmdir -- "$lock_path" || status=1
  exit "$status"
}

lock_operations() {
  mkdir -p -- "$root/.runtime"
  mkdir -- "$lock_path" 2>/dev/null ||
    fail "已有运维操作使用此目录。确认没有前一次运维任务后再移除空锁目录 $lock_path。"
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

require_docker() {
  command -v docker >/dev/null || fail "请安装 Docker 和 Compose。"
  docker compose version >/dev/null || fail "需要可用的 Docker Compose。"
  docker info --format '{{.ServerVersion}}' >/dev/null || fail "无法访问 Docker 引擎。"
}

select_storage() {
  local directory=$1 service=$2 default_database=$3 model actual
  command -v python3 >/dev/null || fail "需要 Python 3 读取 Compose 的 SQLite 挂载配置。"
  model=$(compose_in "$directory" config --format json |
    python3 "$storage_helper" compose-mount "$service" "$default_database") ||
    fail "无法解析 $service 的数据挂载。"
  IFS='|' read -r storage_type storage_source storage_db storage_image storage_secret <<< "$model"
  storage_container=$(compose_in "$directory" ps -a -q "$service") || fail "无法定位 $service 容器。"

  if [[ -n "$storage_container" ]]; then
    [[ "$storage_container" != *$'\n'* ]] || fail "当前脚本适用于单后端容器部署。"
    actual=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}}|{{if eq .Type "volume"}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$storage_container")
    [[ "$actual" == "$storage_type|$storage_source" ]] ||
      fail "$service 现有 /data 挂载与 Compose 配置不一致，请先核对数据卷。"
    storage_image=$(docker inspect --format '{{.Image}}' "$storage_container")
  fi
  storage_mount="type=$storage_type,source=$storage_source,target=/data"
}

storage_exists() {
  local volumes
  if [[ "$storage_type" == bind ]]; then
    [[ -d "$storage_source" ]] || fail "/data 对应的宿主机目录不存在。"
    return 0
  fi
  volumes=$(docker volume ls --filter "name=$storage_source" --format '{{.Name}}') ||
    fail "无法检查 SQLite 数据卷。"
  grep -Fxq -- "$storage_source" <<< "$volumes"
}

storage_uid() {
  if docker image inspect "$storage_image" >/dev/null 2>&1; then
    storage_user=$(docker run --rm --network none --entrypoint /bin/sh "$storage_image" \
      -c 'printf "%s:%s\n" "$(id -u)" "$(id -g)"')
  else
    # Default UID/GID in both the OCI Lens and legacy image Dockerfiles.
    storage_user=10001:10001
  fi
  [[ "$storage_user" =~ ^[0-9]+:[0-9]+$ ]] || fail "无法识别 SQLite 数据卷的运行用户。"
}

sqlite_helper() {
  if ! storage_running; then set -- "$@" --offline; fi
  docker run --rm --network none --user "$storage_user" \
    --mount "$storage_mount,readonly" \
    --mount "type=bind,source=$storage_helper,target=/maintenance.py,readonly" \
    "$helper_image" python /maintenance.py "$@" "$storage_db"
}

target_storage() { select_storage "$root" server "$target_db"; }

init_project() {
  local admin_state
  target_storage
  if [[ "$storage_secret" != "$root/secrets/admin-password" ]]; then
    [[ -f "$storage_secret" ]] || fail "自定义管理员初始化文件不存在，请先恢复该文件。"
    if [[ ! -e "$root/.env" ]]; then
      (set -o noclobber; cat "$root/.env.example" > "$root/.env")
    fi
    mkdir -p -- "$root/secrets/oci"
    chmod 700 "$root/secrets"
    printf '已使用自定义的管理员初始化文件。\n'
    return
  fi
  if [[ ! -e "$storage_secret" ]] && storage_exists; then
    storage_uid
    admin_state=$(sqlite_helper inspect)
    if [[ "$admin_state" == initialized ]]; then
      bash "$root/scripts/init-local.sh" --existing-admin
      return
    fi
    [[ "$admin_state" == missing || "$admin_state" == uninitialized ]] || fail "无法判断管理员初始化状态。"
  fi
  bash "$root/scripts/init-local.sh"
}

backup_sqlite() {
  local label=$1 destination stamp
  mkdir -p -- "$root/backups"
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  temporary_file=$(mktemp "$root/backups/$label-$stamp.XXXXXX")
  if ! sqlite_helper backup > "$temporary_file"; then
    fail "SQLite 备份未完成。"
  fi
  destination="$temporary_file.sqlite3"
  mv -- "$temporary_file" "$destination"
  temporary_file=
  printf 'SQLite 备份：%s\n' "$destination"
}

start_services() {
  if ! compose up -d --no-build --wait --wait-timeout "$wait_seconds"; then
    compose ps >&2 || true
    fail "服务未通过健康检查，运行 logs 查看启动日志。"
  fi
  compose ps
}

storage_running() {
  local running
  if [[ -z "$storage_container" ]]; then return 1; fi
  running=$(docker inspect --format '{{.State.Running}}' "$storage_container") || fail "无法检查后端容器运行状态。"
  case "$running" in
    true) return 0 ;;
    false) return 1 ;;
    *) fail "Docker 返回了未知的后端容器状态。" ;;
  esac
}

pause_server() {
  if storage_running; then
    resume_server=true
    compose stop server
  fi
}

reset_password() {
  local generated=false username= password confirmation reset_file capability
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --generate) generated=true; shift ;;
      --username)
        [[ $# -ge 2 && -n "$2" ]] || fail "--username 需要当前账号名。"
        username=$2; shift 2 ;;
      *) fail "reset-password 的选项无效，请查看 --help。" ;;
    esac
  done
  target_storage
  storage_exists || fail "找不到已部署的 SQLite 数据卷。"
  capability=$(docker image inspect --format '{{index .Config.Labels "org.oci-lens.admin-password-reset"}}' "$storage_image") ||
    fail "找不到部署使用的后端镜像，请先 rebuild。"
  [[ "$capability" == 1 ]] || fail "当前镜像需要先执行新版 rebuild 才能重置密码。"
  storage_uid
  [[ "$(sqlite_helper inspect)" == initialized ]] || fail "目标库中没有已初始化的管理员。"
  if [[ "$generated" == true ]]; then
    command -v openssl >/dev/null || fail "请安装 openssl。"
    password=$(openssl rand -hex 18)
    mkdir -p -- "$root/secrets"
    chmod 700 "$root/secrets"
    reset_file=$(mktemp "$root/secrets/admin-password-reset.XXXXXX")
    chmod 600 "$reset_file"
    printf '%s\n' "$password" > "$reset_file"
    printf '新密码文件：%s\n' "$reset_file"
  else
    [[ -t 0 ]] || fail "请输入终端密码，或用 --generate 生成密码文件。"
    IFS= read -r -s -p '新密码：' password
    printf '\n' >&2
    IFS= read -r -s -p '确认新密码：' confirmation
    printf '\n' >&2
    [[ "$password" == "$confirmation" ]] || fail "两次输入的新密码不一致。"
    [[ "${#password}" -ge 12 && "${#password}" -le 128 ]] || fail "新密码需在 12 到 128 位之间。"
    unset confirmation
  fi
  pause_server
  backup_sqlite before-password-reset

  set -- reset-admin-password
  if [[ -n "$username" ]]; then set -- "$@" --username "$username"; fi
  printf '%s\n' "$password" | docker run --rm -i --network none \
    --mount "$storage_mount" -e "OCI_MONITOR_DB=$storage_db" "$storage_image" "$@"
  unset password
}

migrate_legacy() {
  local old_dir= apply=false host_label='旧站主机' subset= target_mount
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --old-dir|--host-label)
        [[ $# -ge 2 && -n "$2" ]] || fail "$1 需要参数。"
        if [[ "$1" == --old-dir ]]; then old_dir=$2; else host_label=$2; fi
        shift 2 ;;
      --apply) apply=true; shift ;;
      --manual-only|--host-only)
        [[ -z "$subset" ]] || fail "请选择一个迁移范围。"
        subset=$1; shift ;;
      *) fail "migrate-legacy 的选项无效，请查看 --help。" ;;
    esac
  done
  [[ -n "$old_dir" && -d "$old_dir" ]] || fail "请通过 --old-dir 指定旧站 Compose 目录。"
  old_dir=$(cd "$old_dir" && pwd -P)
  target_storage
  storage_exists || fail "请先启动新版 OCI Lens，初始化目标数据库。"
  target_mount=$storage_mount

  select_storage "$old_dir" oci-arm-monitor-server "$legacy_db"
  storage_exists || fail "找不到旧站 SQLite 数据卷。"
  [[ "$storage_mount" != "$target_mount" ]] || fail "新旧项目不能使用同一个数据挂载。"
  storage_uid
  legacy_snapshot=$(mktemp "$root/.runtime/legacy.XXXXXX")
  sqlite_helper backup > "$legacy_snapshot"

  target_storage
  [[ "$storage_mount" == "$target_mount" ]] || fail "目标数据挂载在操作期间发生了变化。"
  storage_uid
  set -- --host-label "$host_label"
  if [[ -n "$subset" ]]; then set -- "$@" "$subset"; fi
  run_import readonly "$@"
  if [[ "$apply" == true ]]; then
    pause_server
    run_import writable "$@" --apply
  fi
}

run_import() {
  local mode=$1 mount_options=$storage_mount offline=
  shift
  if [[ "$mode" == readonly ]]; then
    mount_options="$mount_options,readonly"
    if ! storage_running; then offline=--offline; fi
  fi
  docker run --rm -i --network none --user "$storage_user" \
    --mount "$mount_options" \
    --mount "type=bind,source=$storage_helper,target=/maintenance.py,readonly" \
    --mount "type=bind,source=$root/scripts/migrate-legacy-data.py,target=/import.py,readonly" \
    "$helper_image" sh -ec '
      umask 077
      cat > /tmp/legacy.db
      mode=$1
      target=$2
      offline=$3
      shift 3
      if [ "$mode" = readonly ]; then
        if [ -n "$offline" ]; then
          python /maintenance.py backup --offline "$target" > /tmp/destination.db
        else
          python /maintenance.py backup "$target" > /tmp/destination.db
        fi
        target=/tmp/destination.db
      fi
      exec python /import.py /tmp/legacy.db "$target" "$@"
    ' import "$mode" "$storage_db" "$offline" "$@" < "$legacy_snapshot"
}

command_name=${1:-help}
case "$command_name" in
  -h|--help|help) usage; exit 0 ;;
  init|start|rebuild|stop|status|logs|doctor|backup|migrate-legacy|reset-password) shift ;;
  *) usage >&2; fail "未知命令 $command_name。" ;;
esac

case "$command_name" in
  rebuild)
    [[ $# -eq 0 || ( $# -eq 1 && "$1" == --no-cache ) ]] || fail "rebuild 仅支持 --no-cache。"
    ;;
  logs)
    [[ $# -eq 0 || ( $# -eq 1 && ( "$1" == server || "$1" == web ) ) ]] || fail "日志服务请选择 server 或 web。"
    ;;
  migrate-legacy|reset-password) ;;
  *) [[ $# -eq 0 ]] || fail "$command_name 不接收额外参数。" ;;
esac

require_docker
case "$command_name" in
  status) compose ps -a ;;
  logs) compose logs --tail 100 "$@" ;;
  doctor)
    compose config --quiet
    target_storage
    printf 'SQLite 数据挂载：%s (%s)，数据库：%s\n' "$storage_source" "$storage_type" "$storage_db"
    if ! storage_exists; then printf '当前还没有 SQLite 数据卷。\n'; fi
    if [[ ! -f "$storage_secret" ]]; then printf '初始化文件缺失，请执行 init。\n'; fi
    compose ps -a
    ;;
  *)
    lock_operations
    case "$command_name" in
      init) init_project ;;
      start) init_project; start_services ;;
      rebuild)
        init_project
        local_storage="$storage_type|$storage_source|$storage_db"
        compose config --quiet
        compose build "$@"
        target_storage
        [[ "$storage_type|$storage_source|$storage_db" == "$local_storage" ]] || fail "构建期间数据挂载发生变化。"
        if storage_exists; then
          storage_uid
          state=$(sqlite_helper inspect)
          case "$state" in
            initialized|uninitialized) backup_sqlite before-rebuild ;;
            missing) ;;
            *) fail "无法判断现有数据库状态。" ;;
          esac
        fi
        start_services
        ;;
      stop) compose stop ;;
      backup)
        target_storage
        storage_exists || fail "找不到 SQLite 数据卷。"
        storage_uid
        backup_sqlite oci-lens
        ;;
      migrate-legacy) migrate_legacy "$@" ;;
      reset-password) reset_password "$@" ;;
    esac
    ;;
esac
