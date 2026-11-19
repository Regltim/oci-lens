import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { ArrowRight, RefreshCw } from "lucide-react"
import { Button } from "@/components/glass/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/glass/table"
import { request, useRead, type Choice, type CoverageDay, type HostSummary, type HostHistoryPoint, type InstanceOverview, type SyncRun, type SyncSchedule, type SyncStatus, type TrafficDay } from "./api"
import { QuotaPanel } from "./quota"
import { HostTrend } from "./host-trend"
import { Badge, Heading, Panel, SegmentedChoice, State, formatNumber, formatTime } from "./ui"

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
  const [days, setDays] = useState<1 | 7 | 30>(1)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState("")
  const sync = useRead<SyncStatus>("/sync/status", revision)
  const schedule = useRead<SyncSchedule>("/sync/schedule", revision)
  const host = useRead<HostSummary>("/server/status", revision)
  const today = new Date()
  const history = useRead<HostHistoryPoint[]>(days > 1
    ? `/server/history?from=${utcDayBefore(today, days - 1)}&to=${utcDayBefore(today, 0)}`
    : null, revision)
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
  const trend = days === 1 ? host : history
  const points = days === 1 ? host.data?.history : history.data
  const alerts = host.data?.alerts ?? []
  return <>
    <Heading title="运行总览" actions={cloud
      ? <Button variant="prominent" size="lg" disabled={active || working} onClick={run}><RefreshCw data-icon="inline-start" />{active || working ? "同步中" : "立即同步"}</Button>
      : <Button asChild variant="prominent" size="lg"><Link to="/settings/oci">接入 OCI<ArrowRight data-icon="inline-end" /></Link></Button>} />
    <State error={error || sync.error} retry={sync.error ? sync.retry : undefined} />
    {sync.data && ["FAILED", "PARTIAL", "PAUSED"].includes(sync.data.lastStatus) && <State empty={sync.data.lastMessage} />}
    <Panel title="本机资源趋势" className="block-panel host-trend-panel"
      subtitle={days === 30 ? "UTC 日均采样" : host.data?.current ? `最近采样 ${formatTime(host.data.current.sampledAt)}` : "按采样时间展示"}
      actions={<SegmentedChoice label="资源时间范围" options={[{value:1,label:"24 小时"}, {value:7,label:"7 天"}, {value:30,label:"30 天"}]} value={days} onChange={setDays} />}>
      <State error={trend.error} retry={trend.retry} pending={trend.pending && !points} />
      {points && <HostTrend points={points} daily={days === 30} />}
    </Panel>
    <div className="overview-grid">
      <QuotaPanel compact revision={revision} />
      <aside className="overview-sidebar">
        <Panel title="OCI 同步" className="sync-panel" actions={<Badge ready={Boolean(sync.data && last && !stale)}>{sync.data ? cloud ? syncLabel(sync.data.lastStatus) : "本机模式" : "读取中"}</Badge>}>
          <dl className="sync-times">
            <div><dt>最近成功</dt><dd>{last ? formatTime(last) : "—"}{stale && <span>数据较旧</span>}</dd></div>
            <div><dt>下次同步</dt><dd>{cloud && schedule.data?.enabled && schedule.data.nextRunAt ? formatTime(schedule.data.nextRunAt) : "—"}</dd></div>
          </dl>
          <Link to="/sync" className="panel-link">同步记录 <ArrowRight size={15} /></Link>
        </Panel>
        {Boolean(alerts.length || changes.data?.length) && <Panel title="需要关注" className="change-panel">
          <div className="change-list">{[...alerts, ...(changes.data ?? [])].map((item, index) => <div className="change-row" key={`${item.title}-${index}`}><strong>{item.title}</strong><small>{item.description}</small></div>)}</div>
        </Panel>}
      </aside>
    </div>
  </>
}

export function InstanceTable({instances}: {instances: InstanceOverview[]}) {
  return <Table aria-label="云实例清单"><TableHeader><TableRow><TableHead>实例</TableHead><TableHead>状态</TableHead><TableHead>CPU</TableHead><TableHead>内存</TableHead></TableRow></TableHeader><TableBody>
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
