import { Link } from "react-router-dom"
import { Progress } from "@/components/glass/progress"
import { useRead, type QuotaItem, type QuotaSummary } from "./api"
import { formatNumber, formatTime, Panel, State } from "./ui"

export const sourceState: Record<string, string> = {
  AVAILABLE: "已同步",
  PENDING: "等待首次同步",
  NOT_CONFIGURED: "等待 OCI 接入",
  DISABLED: "用量读取未接入",
  NO_DATA: "暂无可用计量",
  SCOPED: "采集范围内参考",
  OUTSIDE_HOME: "当前采集区域在主区域之外",
  DENIED: "权限或读取范围不足",
  PAUSED: "应用请求额度已用尽",
  ERROR: "同步失败",
  NOT_APPLICABLE: "存储仅显示当前快照",
}

function QuotaLine({item, compact}: {item: QuotaItem; compact: boolean}) {
  const inScope = item.status === "AVAILABLE"
  const percent = inScope && item.limit > 0 && item.used !== null ? Math.max(0, item.used / item.limit * 100) : null
  const unit = item.key === "egress" && item.limit >= 1000 ? "TB" : item.unit
  const scale = unit === "TB" ? 1000 : 1
  const amount = item.used == null ? null : item.used / scale

  return <li className="quota-line">
    <div className="quota-value">
      <strong>{item.name}</strong>
      <span>{amount == null ? "—" : formatNumber(amount, 2)} / {formatNumber(item.limit / scale, 2)} {unit}</span>
    </div>
    {percent != null && <Progress aria-label={item.name + "额度使用率"} value={Math.min(100, percent)} getValueLabel={() => `${formatNumber(percent)}%`} />}
    <p className="quota-note">{sourceState[item.status] ?? item.status}{percent != null && ` · ${formatNumber(percent)}%`}
      {!compact && ` · ${item.scope}`}{item.updatedAt && ` · ${formatTime(item.updatedAt)}`}</p>
    {!compact && item.requestedThrough && <p className="quota-note">查询截至 {item.requestedThrough} UTC，计量可能延迟。</p>}
  </li>
}

export function QuotaPanel({compact = false, revision = 0}: {compact?: boolean; revision?: number}) {
  const quota = useRead<QuotaSummary>("/dashboard/quota", revision)
  const data = quota.data
  return <Panel title="免费额度" subtitle={data ? `${data.month} UTC` : undefined} className="block-panel"
    actions={<Link className="panel-link" to={compact ? "/usage/quota" : "/settings/quota"}>{compact ? "额度明细" : "配置额度"}</Link>}>
    <State pending={quota.pending && !data} error={quota.error} retry={quota.retry} />
    {data && <>
      {data.runningA1Ocpus != null && <p className="state-note">当前采集范围内运行的 A1 实例规格合计 {formatNumber(data.runningA1Ocpus)} OCPU · {formatNumber(data.runningA1MemoryGb)} GB 内存</p>}
      <ul className="quota-list">{data.items.map(item => <QuotaLine key={item.key} item={item} compact={compact} />)}</ul>
    </>}
  </Panel>
}
