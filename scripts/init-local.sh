#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

case "$#:${1:-}" in
  0:|1:--existing-admin) ;;
  *) printf '用法：%s [--existing-admin]\n' "$0" >&2; exit 2 ;;
esac

mkdir -p secrets/oci
chmod 700 secrets
if [[ ! -e secrets/admin-password ]]; then
  password_file=$(mktemp secrets/.admin-password.XXXXXX)
  trap 'rm -f -- "$password_file"' EXIT
  if [[ "${1:-}" != --existing-admin ]]; then
    command -v openssl >/dev/null || { printf '请安装 openssl。\n' >&2; exit 1; }
    openssl rand -hex 18 > "$password_file"
  fi
  # The container reads this mount from a private directory on the host.
  chmod 644 "$password_file"
  ln "$password_file" secrets/admin-password
  if [[ "${1:-}" == --existing-admin ]]; then
    printf '已恢复管理员初始化文件的挂载。\n'
  else
    printf '初始管理员密码文件：secrets/admin-password\n'
  fi
fi
if [[ ! -e .env ]]; then
  (set -o noclobber; cat .env.example > .env)
fi
printf '初始化文件已就绪。\n'
