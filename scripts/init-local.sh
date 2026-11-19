#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets/oci
chmod 700 secrets
if [[ ! -e secrets/admin-password ]]; then
  command -v openssl >/dev/null || { printf '请安装 openssl。\n' >&2; exit 1; }
  (set -o noclobber; openssl rand -hex 18 > secrets/admin-password)
fi
# Docker mounts this one file into the application container. The parent on the host stays private.
chmod 644 secrets/admin-password
if [[ ! -e .env ]]; then
  cp .env.example .env
fi
printf '初始化已完成。管理员密码保存在 secrets/admin-password。\n'
