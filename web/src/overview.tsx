import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { ArrowRight, RefreshCw, ShieldCheck } from "lucide-react"
import { Button } from "@/components/glass/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/glass/table"
import { request, useRead, type Choice, type CostSummary, type CoverageDay, type HostSummary, type InstanceOverview, type SyncRun, type SyncSchedule, type SyncStatus, type TrafficDay, type TrafficSummary } from "./api"
import { SeriesChart } from "./traffic-chart"
import { Badge, Heading, Metric, Panel, SegmentedChoice, State, formatNumber, formatTime, money, utcMonth } from "./ui"

export function trafficDays(rows: TrafficDay[], coverage: CoverageDay[] = []) {
  const days = new Map<string, {ingress: number | null; egress: number | null}>()
  for (const date of coverage) days.set(date.statDate, {ingress:null,egress:null})
  for (const row of rows) {
    const day = days.get(row.statDate) ?? {ingress:null,egress:null}
    if (row.ingressGb != null) day.ingress = (day.ingress ?? 0) + row.ingressGb
    if (row.egressGb != null) day.egress = (day.egress ?? 0) + row.egressGb
    days.set(row.statDate, day)
  }
  return [...days].sort(([a], [b]) => a.localeCompare(b))
}

const statuses: Record<string, string> = { SUCCESS: "已完成", SUCCEEDED: "已完成", RUNNING: "同步中", FAILED: "失败", PARTIAL: "部分完成", PAUSED: "额度暂停", INTERRUPTED: "已中断", NOT_CONFIGURED: "本机模式", NEVER_SYNCED: "等待首次同步" }
export function syncLabel(status: string) { return statuses[status] ?? status }

const instanceStates: Record<string, string> = { RUNNING: "运行中", STOPPED: "已停止", STARTING: "启动中", STOPPING: "停止中", PROVISIONING: "配置中", TERMINATING: "终止中", TERMINATED: "已终止", CREATING_IMAGE: "制作镜像" }
export function instanceStateLabel(state: string) { return instanceStates[state] ?? state }

function utcDayBefore(date: Date, offset: number) {
  const day = new Date(date)
  day.setUTCDate(date.getUTCDate() - offset)
  return day.toISOString().slice(0, 10)
}

export function Overview() {
  const [revision, setRevision] = useState(0)
  const [days, setDays] = useState<7 | 30>(7)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState("")
  const sync = useRead<SyncStatus>("/sync/status", revision)
  const schedule = useRead<SyncSchedule>("/sync/schedule", revision)
  const host = useRead<HostSummary>("/server/status", revision)
  const instances = useRead<InstanceOverview[]>("/instances", revision)
  const today = new Date()
  const lastDay = utcDayBefore(today, 1)
  const firstDay = utcDayBefore(today, days)
  const lastMonth = lastDay.slice(0, 7)
  const firstMonth = firstDay.slice(0, 7)
  const traffic = useRead<TrafficSummary>(`/traffic/summary?month=${lastMonth}`, revision)
  const prior = useRead<TrafficSummary>(firstMonth !== lastMonth ? `/traffic/summary?month=${firstMonth}` : null, revision)
  const costs = useRead<CostSummary>(`/costs/summary?month=${utcMonth()}`, revision)
  const changes = useRead<Choice[]>("/sync/changes", revision)
  const active = sync.data?.lastStatus === "RUNNING"
  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(sync.retry, 2000)
    return () => window.clearInterval(timer)
  }, [active, sync.retry])
  useEffect(() => {if (sync.data?.lastFinishedAt) setRevision(value => value + 1)}, [sync.data?.lastFinishedAt])

  async function run() {
    setWorking(true)
    setError("")
    try {await request("/sync/full", "POST"); sync.retry()}
    catch (failure) {setError((failure as Error).message)} finally {setWorking(false)}
  }
  const cloud = sync.data?.configured
  const last = sync.data?.lastSuccessfulAt || (sync.data?.lastStatus === "SUCCESS" ? sync.data.lastFinishedAt : "")
  const stale = last && Date.now() - Date.parse(last) > 172_800_000
  const daily = new Map(trafficDays([...(prior.data?.daily ?? []), ...(traffic.data?.daily ?? [])]))
  const out = daily.get(lastDay)?.egress
  const amounts = new Map<string, number>()
  for (const item of costs.data?.daily ?? []) amounts.set(item.currency, (amounts.get(item.currency) ?? 0) + Number(item.costAmount))
  const displayAmount = amounts.size === 1 ? [...amounts].map(([currency, amount]) => money(amount, currency))[0] : "—"
  const points = Array.from({length:days}, (_, index) => {
    const day = utcDayBefore(today, days - index)
    return {label:day.slice(5),value:daily.get(day)?.egress ?? null}
  })
  const trendError = traffic.error || prior.error
  const trendPending = traffic.pending || prior.pending
  const alerts = host.data?.alerts ?? []
  return <>
    <Heading title="运行总览" actions={cloud
      ? <Button variant="prominent" size="lg" disabled={active || working} onClick={run}><RefreshCw data-icon="inline-start" />{active || working ? "同步中" : "立即同步"}</Button>
      : <Button asChild variant="prominent" size="lg"><Link to="/settings/oci">接入 OCI<ArrowRight data-icon="inline-end" /></Link></Button>} />
    <State error={error || sync.error} retry={sync.error ? sync.retry : undefined} />
    {sync.data && ["FAILED", "PARTIAL", "PAUSED"].includes(sync.data.lastStatus) && <State empty={sync.data.lastMessage} />}
    <div className="overview-grid">
      <div className="metric-grid overview-metrics">
        <Metric label="运行实例" to="/monitor/instances" value={sync.data?.hasData && instances.data ? String(instances.data.filter(row => row.instance.lifecycleState === "RUNNING").length) : "—"} unit="台" note={cloud ? "最近成功的清单" : "等待 OCI 接入"} />
        <Metric label="昨日主网卡出站" to="/usage/traffic" value={formatNumber(out, 2)} unit="GiB" note={out == null ? "等待可用流量" : "UTC 日"} />
        <Metric label="本月 OCI 已入账" to="/usage/cost" value={displayAmount} note={amounts.size > 1 ? "有多个币种，查看明细" : costs.data?.through ? `核对至 ${costs.data.through}` : "等待可用账单"} />
        <Metric label="本机磁盘已用" to="/monitor/host" value={formatNumber(host.data?.current?.diskUsagePercent)} unit="%" note={host.data?.current ? `采样于 ${formatTime(host.data.current.sampledAt)}` : "等待本机采样"} />
      </div>
      <Panel title="同步状态" className="sync-panel" actions={<Badge ready={Boolean(sync.data && last && !stale)}>{sync.data ? cloud ? syncLabel(sync.data.lastStatus) : "本机模式" : "读取中"}</Badge>}>
        <dl className="sync-times">
          <div><dt>最近成功</dt><dd>{last ? formatTime(last) : "—"}{stale && <span>数据较旧</span>}</dd></div>
          <div><dt>下次同步</dt><dd>{cloud && schedule.data?.enabled && schedule.data.nextRunAt ? formatTime(schedule.data.nextRunAt) : "—"}</dd></div>
        </dl>
        <Link to="/sync" className="panel-link">同步记录 <ArrowRight size={15} /></Link>
      </Panel>
      <Panel title="出站流量趋势" subtitle="主网卡出站 · GiB · 按完整 UTC 日" className="chart-panel" actions={<SegmentedChoice label="流量时间范围" options={[{value:7,label:"7 天"}, {value:30,label:"30 天"}]} value={days} onChange={setDays} />}>
        <State error={trendError} retry={() => {if (traffic.error) traffic.retry(); if (prior.error) prior.retry()}} pending={trendPending} />
        {points.some(point => point.value !== null) ? <SeriesChart points={points} label="主网卡出站" unit="GiB" /> : !trendPending && !trendError && <State empty="同步完成后这里会显示趋势。" />}
      </Panel>
      <Panel title="需要关注" subtitle="本机告警和最近变化" className="change-panel">
        <div className="change-list">{[...alerts, ...(changes.data ?? [])].map((item, index) => <div className="change-row" key={`${item.title}-${index}`}><strong>{item.title}</strong><small>{item.description}</small></div>)}</div>
        {!alerts.length && !changes.data?.length && <State empty={cloud ? "当前没有需要关注的变化。" : "本机没有活跃告警。"} />}
        <div className="subtle-row"><ShieldCheck size={14} />页面仅读取本地快照</div>
      </Panel>
    </div>
    <Panel title="实例快照" className="instance-panel" actions={<Link to="/monitor/instances">查看全部</Link>}>
      <State error={instances.error} retry={instances.retry} pending={instances.pending} />
      {instances.data?.length ? <InstanceTable instances={instances.data.slice(0, 5)} /> : !instances.pending && !instances.error && <State empty="当前没有实例快照。" />}
    </Panel>
  </>
}

export function InstanceTable({instances}: {instances: InstanceOverview[]}) {
  return <Table aria-label="实例快照"><TableHeader><TableRow><TableHead>实例</TableHead><TableHead>状态</TableHead><TableHead>CPU</TableHead><TableHead>内存</TableHead></TableRow></TableHeader><TableBody>
    {instances.map(row => <TableRow key={row.instance.id}>
      <TableCell><Link to={`/monitor/instances/${encodeURIComponent(row.instance.id)}`}>{row.instance.displayName}</Link><small>{row.instance.shape}</small></TableCell>
      <TableCell><Badge ready={row.instance.lifecycleState === "RUNNING"}>{instanceStateLabel(row.instance.lifecycleState)}</Badge></TableCell>
      <TableCell>{row.cpuUtilization == null ? "—" : `${formatNumber(row.cpuUtilization)}%`}</TableCell>
      <TableCell>{row.memoryUtilization == null ? "—" : `${formatNumber(row.memoryUtilization)}%`}</TableCell>
    </TableRow>)}
  </TableBody></Table>
}

export function Runs() {
  const runs = useRead<SyncRun[]>("/sync/history")
  const active = runs.data?.some(run => run.status === "RUNNING")
  useEffect(() => {if (!active) return; const timer = window.setInterval(runs.retry, 2000); return () => window.clearInterval(timer)}, [active, runs.retry])
  return <><Heading title="同步记录" subtitle="云端读取任务与历史补采" actions={<Button onClick={runs.retry} variant="glass">刷新</Button>} />
    <Panel><State error={runs.error} retry={runs.retry} pending={runs.pending} empty={runs.data?.length === 0 ? "尚无同步记录。" : undefined} />
      {runs.data?.length ? <div className="change-list">{runs.data.map(run => <details key={run.id} className="run-row"><summary><Badge ready={run.status === "SUCCESS"}>{syncLabel(run.status)}</Badge><span>{formatTime(run.startedAt)}</span><span>{run.syncType === "BACKFILL" ? "历史补采" : run.syncType === "SCHEDULED" ? "每日同步" : "手动同步"}</span></summary><p>{run.message}</p><p>实例 {run.instanceCount} · 指标 {run.metricCount} · 流量 {run.trafficCount} · 费用 {run.costCount}</p></details>)}</div> : null}
    </Panel>
  </>
}
