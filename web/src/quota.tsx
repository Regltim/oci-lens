import { Link } from "react-router-dom"
import { ArrowUpRight } from "lucide-react"
import { query, useRead, type CostSummary, type InstanceTrafficMonth, type QuotaItem, type QuotaSummary, type VolumeFailure } from "./api"
import { formatNumber, formatTime, Panel, State, utcMonth } from "./ui"

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
  const status = sourceState[item.status] ?? item.status
  if (compact) return <li className="quota-metric">
    <strong>{label}</strong>
    {item.used == null ? <span className="quota-note">{status}</span> : <>
      <span className="quota-metric-value">{formatNumber(item.used, 2)} <small>{item.unit}</small></span>
      {!["AVAILABLE", "SCOPED"].includes(item.status) && <span className="quota-note">{status}</span>}
    </>}
  </li>
  return <li className="quota-line">
    <div className="quota-value">
      <strong>{label}</strong>
      <span>{item.used == null ? "—" : `${formatNumber(item.used, 2)} ${item.unit}`}</span>
    </div>
    <p className="quota-note">{status}
      {item.scope && ` · ${item.scope}`}{item.updatedAt && ` · ${formatTime(item.updatedAt)}`}</p>
    {item.key === "volume" && item.status === "DENIED" && <VolumeReadHint failure={failure} />}
    {item.requestedThrough && <p className="quota-note">采集窗口至 {item.requestedThrough} UTC，计量可能延迟。</p>}
  </li>
}

function MonthlyNetwork({traffic}: {traffic?: InstanceTrafficMonth | null}) {
  if (!traffic) return <State empty="等待识别本机对应的 OCI 实例。" />
  const hasData = traffic.observedDays > 0
  const directions = [
    { key: "outbound", label: "出站", value: hasData ? traffic.egressGiB : null },
    { key: "inbound", label: "入站", value: hasData ? traffic.ingressGiB : null },
  ]
  const maximum = Math.max(0, ...directions.map(({ value }) => value ?? 0))
  return <div className="monthly-network">
    <div className="monthly-network-name"><strong>本机主网卡流量</strong>
      <Link className="panel-link" to={query("/usage/traffic", {instance: traffic.instanceId})}>流量明细 <ArrowUpRight size={14} aria-hidden="true" /></Link>
    </div>
    <dl aria-label="本月本机主网卡累计流量">
      {directions.map(({ key, label, value }) => {
        const percent = maximum > 0 && value != null ? Math.max(0, value) / maximum * 100 : 0
        return <div key={key}>
          <dt>{label}</dt>
          <dd>
            <span className="monthly-traffic-track" aria-hidden="true"><span data-direction={key} style={{ width: `${percent}%` }} /></span>
            <span className="monthly-traffic-number">{value == null ? "—" : `${formatNumber(value, 3)} GiB`}</span>
          </dd>
        </div>
      })}
    </dl>
    <p className="quota-note">{traffic.elapsedDays === 0 ? "等待首个完整 UTC 日" : `本月已采集 ${traffic.observedDays} / ${traffic.elapsedDays} 个 UTC 日`}
      {traffic.completeDays < traffic.observedDays && ` · ${traffic.completeDays} 天采样完整`}</p>
  </div>
}

const bookedAmount = new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function QuotaPanel({compact = false, revision = 0}: {compact?: boolean; revision?: number}) {
  const month = utcMonth()
  const quota = useRead<QuotaSummary>(query("/dashboard/quota", {month}), revision)
  const data = quota.data
  const costs = useRead<CostSummary>(compact ? query("/costs/summary", {month}) : null, revision)
  const totals = new Map<string, number>()
  for (const row of costs.data?.daily ?? []) totals.set(row.currency, (totals.get(row.currency) ?? 0) + Number(row.costAmount))
  return <Panel title="本月使用情况" subtitle={`${data?.month ?? month} UTC`} className="block-panel"
    actions={<Link className="panel-link" to={compact ? "/usage/quota" : "/settings/quota"}>
      {compact ? "使用明细" : "用量上限"}<ArrowUpRight size={14} aria-hidden="true" />
    </Link>}>
    <div className={compact ? "monthly-usage-summary" : undefined}>
      <div className="monthly-network-summary">
        <State pending={quota.pending && !data} error={quota.error} retry={quota.retry} />
        {data && <MonthlyNetwork traffic={data.instanceTraffic} />}
      </div>
      {compact && <div className="monthly-booked">
        <strong>本月 OCI 已入账</strong>
        <State pending={costs.pending && !costs.data} error={costs.error} retry={costs.retry}
          empty={costs.data && !totals.size ? "暂无已入账记录" : undefined} />
        {totals.size > 0 && <ul className="monthly-booked-amounts">
          {Array.from(totals, ([currency, amount]) => <li key={currency}><strong>{bookedAmount.format(amount)}</strong><span>{currency}</span></li>)}
        </ul>}
        <Link to="/usage/cost" className="panel-link">费用明细 <ArrowUpRight size={14} aria-hidden="true" /></Link>
      </div>}
    </div>
    {data && <ul className={compact ? "quota-metrics" : "quota-list"}>
      {data.items.map(item => <UsageLine key={item.key} item={item} compact={compact} failure={data.volumeFailure} />)}
    </ul>}
  </Panel>
}
