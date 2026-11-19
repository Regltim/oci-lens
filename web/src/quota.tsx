import { Link } from "react-router-dom"
import { useRead, type CostSummary, type InstanceTrafficMonth, type QuotaItem, type QuotaSummary, type VolumeFailure } from "./api"
import { formatNumber, formatTime, money, Panel, State, utcMonth } from "./ui"

export const sourceState: Record<string, string> = {
  AVAILABLE: "已同步",
  PENDING: "等待首次同步",
  NOT_CONFIGURED: "等待 OCI 接入",
  DISABLED: "用量读取未接入",
  NO_DATA: "暂无可用计量",
  SCOPED: "采集范围内统计",
  OUTSIDE_HOME: "当前采集区域在主区域之外",
  DENIED: "权限或读取范围不足",
  PAUSED: "应用请求额度已用尽",
  ERROR: "同步失败",
  NOT_APPLICABLE: "存储仅显示当前快照",
}

export function VolumeReadHint({failure}: {failure?: VolumeFailure | null}) {
  const operation = failure ? `${failure.operation}${failure.httpStatus ? `（HTTP ${failure.httpStatus}）` : ""}` : ""
  const scopeHint = failure?.httpStatus === 401 ? "检查 OCI 认证和实例身份。"
    : failure?.httpStatus === 404 ? "核对卷所在区域、Compartment 和权限。"
    : "如未授予云盘读权限，请补充授权后重新同步。"
  return <p className="quota-note">
    {operation && <><code>{operation}</code> · </>}{scopeHint} <Link to="/settings/oci">OCI 接入</Link>
  </p>
}

function UsageLine({item, compact, failure}: {item: QuotaItem; compact: boolean; failure?: VolumeFailure | null}) {
  const label = item.key === "egress" ? "OCI 出站计量" : item.name
  return <li className="quota-line">
    <div className="quota-value">
      <strong>{label}</strong>
      <span>{item.used == null ? "—" : `${formatNumber(item.used, 2)} ${item.unit}`}</span>
    </div>
    <p className="quota-note">{sourceState[item.status] ?? item.status}
      {item.scope && ` · ${item.scope}`}{item.updatedAt && ` · ${formatTime(item.updatedAt)}`}</p>
    {item.key === "volume" && item.status === "DENIED" && <VolumeReadHint failure={failure} />}
    {!compact && item.requestedThrough && <p className="quota-note">采集窗口至 {item.requestedThrough} UTC，计量可能延迟。</p>}
  </li>
}

function MonthlyNetwork({traffic}: {traffic?: InstanceTrafficMonth | null}) {
  if (!traffic) return <State empty="等待识别本机对应的 OCI 实例。" />
  const hasData = traffic.observedDays > 0
  return <div className="monthly-network">
    <div className="monthly-network-name"><strong>本机主网卡累计</strong><Link to={`/usage/traffic?instance=${encodeURIComponent(traffic.instanceId)}`}>流量明细</Link></div>
    <dl>
      <div><dt>出站</dt><dd>{hasData ? `${formatNumber(traffic.egressGiB, 3)} GiB` : "—"}</dd></div>
      <div><dt>入站</dt><dd>{hasData ? `${formatNumber(traffic.ingressGiB, 3)} GiB` : "—"}</dd></div>
    </dl>
    <p className="quota-note">{traffic.elapsedDays === 0 ? "等待首个完整 UTC 日" : `本月已采集 ${traffic.observedDays} / ${traffic.elapsedDays} 个 UTC 日`}
      {traffic.completeDays < traffic.observedDays && ` · ${traffic.completeDays} 天采样完整`}</p>
  </div>
}

export function QuotaPanel({compact = false, revision = 0}: {compact?: boolean; revision?: number}) {
  const quota = useRead<QuotaSummary>("/dashboard/quota", revision)
  const data = quota.data
  const costs = useRead<CostSummary>(compact ? `/costs/summary?month=${utcMonth()}` : null, revision)
  const totals = new Map<string, number>()
  for (const row of costs.data?.daily ?? []) totals.set(row.currency, (totals.get(row.currency) ?? 0) + Number(row.costAmount))
  return <Panel title="本月使用情况" subtitle={data ? `${data.month} UTC` : undefined} className="block-panel"
    actions={<Link className="panel-link" to={compact ? "/usage/quota" : "/settings/quota"}>{compact ? "使用明细" : "用量上限"}</Link>}>
    <State pending={quota.pending && !data} error={quota.error} retry={quota.retry} />
    {data && <>
      <MonthlyNetwork traffic={data.instanceTraffic} />
      <ul className="quota-list">{data.items.map(item => <UsageLine key={item.key} item={item} compact={compact} failure={data.volumeFailure} />)}</ul>
    </>}
    {compact && totals.size > 0 && <div className="quota-line quota-value">
      <Link to="/usage/cost">本月 OCI 已入账</Link>
      <span>{Array.from(totals, ([currency, amount]) => money(amount, currency)).join(" · ")}</span>
    </div>}
  </Panel>
}
