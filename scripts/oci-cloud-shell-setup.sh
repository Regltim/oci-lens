#!/usr/bin/env bash
set -euo pipefail

fail() { printf '错误：%s\n' "$1" >&2; exit 1; }

usage() {
  cat <<'HELP'
用法：bash oci-cloud-shell-setup.sh --tenancy-id OCID --instance-id OCID \
      --compartment-id OCID [--network-compartment-id OCID] [--with-cost] [--with-volumes | --volumes-only] \
      [--allow-tenancy-scope] [--apply]

在租户主区域的 OCI Cloud Shell 中执行。默认仅预览，--apply 创建只读授权。
--with-cost 增加计费租户的账单读取权限。--with-volumes 单独添加云盘和区域读取策略。主网卡或子网在其他 Compartment 时，
通过 --network-compartment-id 指定其范围。授权根 Compartment 需要显式添加
--allow-tenancy-scope，因为此时所选资源的权限覆盖整个租户。
--volumes-only 复用已有且只匹配当前实例的动态组，仅补充独立的云盘只读策略。
HELP
}

tenancy_id=""
instance_id=""
compartment_id=""
network_compartment_id=""
with_cost=false
with_volumes=false
volumes_only=false
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
    --volumes-only) volumes_only=true; with_volumes=true; shift ;;
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
if "$volumes_only" && { "$with_cost" || [[ -n "$network_compartment_id" ]]; }; then
  fail "--volumes-only 仅处理云盘权限，请移除 --with-cost 和 --network-compartment-id。"
fi

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
network_scope=""
if ! "$volumes_only"; then
  network_scope="$(scope_for "${network_compartment_id:-$compartment_id}")"
fi
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

printf '动态组 %s\n匹配规则 %s\n' "$group_name" "$matching_rule"
if ! "$volumes_only"; then
  printf '基础策略 %s\n' "$policy_name"
  statements "dynamic-group $group_name"
fi
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
policy=null
if "$volumes_only"; then
  [[ "$group" != null ]] || fail "找不到已有动态组，请先完成 OCI 实例接入。"
else
  policy="$(lookup policy "$policy_name")"
fi

if [[ "$group" == null ]]; then
  [[ "$policy" == null ]] || fail "发现同名策略，但动态组不存在。请核对已有 IAM 配置。"
  group_id="$(oci iam dynamic-group create --compartment-id "$tenancy_id" --name "$group_name" \
    --description "OCI Lens instance principal" --matching-rule "$matching_rule" \
    --wait-for-state ACTIVE --wait-interval-seconds 5 --max-wait-seconds 120 --query 'data.id' --raw-output)"
else
  group_id="$(jq -r '.id' <<< "$group")"
  is_ocid dynamicgroup "$group_id" || fail "OCI 未返回有效的动态组 OCID。"
  if ! jq -e '."matching-rule" | strings | select(test("\\S"))' <<< "$group" >/dev/null; then
    detail="$(oci iam dynamic-group get --dynamic-group-id "$group_id" --output json)" ||
      fail "动态组列表未返回匹配规则，且 OCI 无法读取该动态组的详情。请核对当前账号是否可查看该动态组。"
    group="$(jq -ec --arg id "$group_id" '.data | select(.id == $id)' <<< "$detail")" ||
      fail "OCI 返回的动态组详情与列表中的动态组不一致。"
  fi
  group_state="$(jq -r '."lifecycle-state" // "UNKNOWN"' <<< "$group")"
  [[ "$group_state" == ACTIVE ]] ||
    fail "同名动态组当前状态为 ${group_state}，请在 IAM 中确认状态达到 ACTIVE 后再试。"
  existing_rule="$(jq -er '."matching-rule" | strings | select(test("\\S"))' <<< "$group")" ||
    fail "OCI 动态组详情仍未返回匹配规则，请在 IAM 控制台核对动态组。"
  matches_instance_only "$existing_rule" ||
    fail "同名动态组的规则与当前部署实例的单实例匹配范围不一致，请在 IAM 中核对匹配规则。"
fi

is_ocid dynamicgroup "$group_id" || fail "OCI 未返回有效的动态组 OCID。"
if ! "$volumes_only"; then
  wanted="$(statements "dynamic-group id $group_id" | jq -Rsc 'split("\n")[:-1]')"
  if [[ "$policy" == null ]]; then
    oci iam policy create --compartment-id "$tenancy_id" --name "$policy_name" \
      --description "Read-only access for OCI Lens" --statements "$wanted" \
      --wait-for-state ACTIVE --wait-interval-seconds 5 --max-wait-seconds 120 >/dev/null
  else
    policy_state="$(jq -r '."lifecycle-state" // "UNKNOWN"' <<< "$policy")"
    [[ "$policy_state" == ACTIVE ]] ||
      fail "已有基础策略 ${policy_name} 的状态为 ${policy_state}，请在 IAM 中核对。仅补云盘时可使用 --volumes-only。"
    jq -e --argjson wanted "$wanted" '(.statements | sort) == ($wanted | sort)' <<< "$policy" >/dev/null ||
      fail "已有基础策略 ${policy_name} 的语句与本次请求不一致。仅补云盘时可使用 --volumes-only。"
  fi
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

if "$volumes_only"; then
  printf '\n云盘只读授权已配置。IAM 生效后，在 OCI Lens 执行同步。\n'
else
  printf '\n只读授权已配置。IAM 生效后，在 OCI Lens 执行连接检查。\n'
fi
