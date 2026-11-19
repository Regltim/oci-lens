#!/usr/bin/env bash
set -euo pipefail

fail() { printf '错误：%s\n' "$1" >&2; exit 1; }

usage() {
  cat <<'HELP'
用法：bash oci-cloud-shell-setup.sh --tenancy-id OCID --instance-id OCID \
      --compartment-id OCID [--network-compartment-id OCID] [--with-cost] [--with-volumes] \
      [--allow-tenancy-scope] [--apply]

在租户主区域的 OCI Cloud Shell 中执行。默认仅预览，--apply 创建只读授权。
--with-cost 增加计费租户的账单读取权限。--with-volumes 单独添加云盘和区域读取策略。主网卡或子网在其他 Compartment 时，
通过 --network-compartment-id 指定其范围。授权根 Compartment 需要显式添加
--allow-tenancy-scope，因为此时实例和指标权限会覆盖整个租户。
HELP
}

tenancy_id=""
instance_id=""
compartment_id=""
network_compartment_id=""
with_cost=false
with_volumes=false
allow_tenancy_scope=false
apply=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tenancy-id|--instance-id|--compartment-id|--network-compartment-id)
      [ "$#" -ge 2 ] || fail "$1 缺少 OCID。"
      case "$1" in
        --tenancy-id) tenancy_id="$2" ;;
        --instance-id) instance_id="$2" ;;
        --compartment-id) compartment_id="$2" ;;
        --network-compartment-id) network_compartment_id="$2" ;;
      esac
      shift 2 ;;
    --with-cost) with_cost=true; shift ;;
    --with-volumes) with_volumes=true; shift ;;
    --allow-tenancy-scope) allow_tenancy_scope=true; shift ;;
    --apply) apply=true; shift ;;
    --dry-run) apply=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "未知选项。运行 --help 查看参数。" ;;
  esac
done

is_ocid() { [[ "$2" =~ ^ocid1\.${1}\.[[:alnum:]][[:alnum:]._-]*\.[[:alnum:]][[:alnum:]_-]*$ ]]; }
is_ocid tenancy "$tenancy_id" || fail "请输入有效的租户 OCID。"
is_ocid instance "$instance_id" || fail "请输入有效的部署主机实例 OCID。"
network_compartment_id="${network_compartment_id:-$compartment_id}"

scope_for() {
  if [[ "$1" == "$tenancy_id" ]]; then
    "$allow_tenancy_scope" || fail "此范围覆盖整个租户。确认后添加 --allow-tenancy-scope。"
    printf 'in tenancy'
  else
    is_ocid compartment "$1" || fail "请输入有效的 Compartment OCID。"
    printf 'in compartment id %s' "$1"
  fi
}

compute_scope="$(scope_for "$compartment_id")"
network_scope="$(scope_for "$network_compartment_id")"
if command -v sha256sum >/dev/null; then
  digest="$(printf '%s' "$instance_id" | sha256sum)"
else
  digest="$(printf '%s' "$instance_id" | shasum -a 256)"
fi
group_name="oci-lens-${digest:0:16}"
policy_name="${group_name}-read"
volume_policy_name="${group_name}-volumes"
matching_rule="instance.id = '$instance_id'"

matches_instance_only() {
  local rule=$1
  local wrapper='^[[:space:]]*([Aa][Nn][Yy]|[Aa][Ll][Ll])[[:space:]]*[{](.*)[}][[:space:]]*$'
  local condition="^[[:space:]]*instance[.]id[[:space:]]*=[[:space:]]*'([^']+)'[[:space:]]*$"

  # A one-condition wrapper has the same membership as the bare condition.
  if [[ "$rule" =~ $wrapper ]]; then
    rule=${BASH_REMATCH[2]}
  fi
  [[ "$rule" =~ $condition && "${BASH_REMATCH[1]}" == "$instance_id" ]]
}

statements() {
  local subject="$1"
  printf 'Allow %s to read instance-family %s\n' "$subject" "$compute_scope"
  printf 'Allow %s to read virtual-network-family %s\n' "$subject" "$network_scope"
  if [[ "$compute_scope" == "$network_scope" ]]; then
    printf 'Allow %s to read metrics %s where any {target.metrics.namespace = '\''oci_computeagent'\'', target.metrics.namespace = '\''oci_vcn'\''}\n' "$subject" "$compute_scope"
  else
    printf 'Allow %s to read metrics %s where target.metrics.namespace = '\''oci_computeagent'\''\n' "$subject" "$compute_scope"
    printf 'Allow %s to read metrics %s where target.metrics.namespace = '\''oci_vcn'\''\n' "$subject" "$network_scope"
  fi
  if "$with_cost"; then
    printf 'Allow %s to read usage-report in tenancy\n' "$subject"
  fi
}

volume_statements() {
  local subject="$1"
  printf 'Allow %s to inspect volumes %s\n' "$subject" "$compute_scope"
  printf 'Allow %s to inspect volume-attachments %s\n' "$subject" "$compute_scope"
  printf "Allow %s to inspect compartments in tenancy where request.operation = 'ListAvailabilityDomains'\n" "$subject"
  printf "Allow %s to inspect tenancies in tenancy where request.operation = 'ListRegionSubscriptions'\n" "$subject"
}

printf '动态组 %s\n匹配规则 %s\n策略 %s\n' "$group_name" "$matching_rule" "$policy_name"
statements "dynamic-group $group_name"
if "$with_volumes"; then
  printf '云盘策略 %s\n' "$volume_policy_name"
  volume_statements "dynamic-group $group_name"
fi
if ! "$apply"; then
  printf '\n预览完成。确认授权范围后添加 --apply。\n'
  exit 0
fi

command -v oci >/dev/null || fail "请在 OCI Cloud Shell 中运行。"
command -v jq >/dev/null || fail "此脚本需要 jq。"

lookup() {
  oci iam "$1" list --compartment-id "$tenancy_id" --all --output json |
    jq -c --arg name "$2" '[.data[] | select(.name == $name and .["lifecycle-state"] != "DELETED")] | if length > 1 then error("Duplicate IAM objects") else .[0] end'
}

group="$(lookup dynamic-group "$group_name")"
policy="$(lookup policy "$policy_name")"

if [[ "$group" == null ]]; then
  [[ "$policy" == null ]] || fail "发现同名策略，但动态组不存在。请核对已有 IAM 配置。"
  group_id="$(oci iam dynamic-group create --compartment-id "$tenancy_id" --name "$group_name" \
    --description "OCI Lens instance principal" --matching-rule "$matching_rule" \
    --wait-for-state ACTIVE --wait-interval-seconds 5 --max-wait-seconds 120 --query 'data.id' --raw-output)"
else
  group_state="$(jq -r '."lifecycle-state" // "UNKNOWN"' <<< "$group")"
  [[ "$group_state" == ACTIVE ]] ||
    fail "同名动态组当前状态为 ${group_state}，请在 IAM 中确认状态达到 ACTIVE 后再试。"
  existing_rule="$(jq -er '."matching-rule" | strings' <<< "$group")" ||
    fail "OCI 未返回同名动态组的匹配规则，请在 IAM 中核对动态组。"
  matches_instance_only "$existing_rule" ||
    fail "同名动态组的规则与当前部署实例的单实例匹配范围不一致，请在 IAM 中核对匹配规则。"
  group_id="$(jq -r '.id' <<< "$group")"
fi

is_ocid dynamicgroup "$group_id" || fail "OCI 未返回有效的动态组 OCID。"
wanted="$(statements "dynamic-group id $group_id" | jq -Rsc 'split("\n")[:-1]')"
if [[ "$policy" == null ]]; then
  oci iam policy create --compartment-id "$tenancy_id" --name "$policy_name" \
    --description "Read-only access for OCI Lens" --statements "$wanted" \
    --wait-for-state ACTIVE --wait-interval-seconds 5 --max-wait-seconds 120 >/dev/null
else
  jq -e --argjson wanted "$wanted" '."lifecycle-state" == "ACTIVE" and ((.statements | sort) == ($wanted | sort))' <<< "$policy" >/dev/null ||
    fail "同名策略处于非活跃状态或授权范围不同。请在 IAM 中核对该策略。"
fi

if "$with_volumes"; then
  volume_policy="$(lookup policy "$volume_policy_name")"
  volume_wanted="$(volume_statements "dynamic-group id $group_id" | jq -Rsc 'split("\n")[:-1]')"
  if [[ "$volume_policy" == null ]]; then
    oci iam policy create --compartment-id "$tenancy_id" --name "$volume_policy_name" \
      --description "Read-only volume inventory for OCI Lens" --statements "$volume_wanted" \
      --wait-for-state ACTIVE --wait-interval-seconds 5 --max-wait-seconds 120 >/dev/null
  else
    jq -e --argjson wanted "$volume_wanted" '."lifecycle-state" == "ACTIVE" and ((.statements | sort) == ($wanted | sort))' <<< "$volume_policy" >/dev/null ||
      fail "同名云盘策略处于非活跃状态或授权范围不同。请在 IAM 中核对该策略。"
  fi
fi

printf '\n只读授权已配置。IAM 生效后，在 OCI Lens 执行连接检查。\n'
