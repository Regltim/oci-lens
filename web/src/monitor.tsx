import { useState } from "react"
import { Link, Navigate, Route, Routes, useParams } from "react-router-dom"
import { ChevronDown, Search } from "lucide-react"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/glass/table"
import { type HostHistoryPoint, type HostSummary, type InstanceOverview, type MetricDates, type MetricPoint, type OciStatus, type VolumeDetails, query, useRead } from "./api"
import { dailyAverage, DateFilters, latestRange, type DateRange } from "./history-date-range"
import { getMonitorView } from "./monitor-preference"
import { HostTrend } from "./host-trend"
import { sourceState, VolumeReadHint } from "./quota"
import { InstanceTable, instanceStateLabel } from "./overview"
import { SeriesChart } from "./traffic-chart"
import { Heading, Panel, State, Tabs, formatNumber, formatTime } from "./ui"

function Instances() {
  const [search, setSearch] = useState("")
  const [state, setState] = useState("")
  const instances = useRead<InstanceOverview[]>("/instances")
  const rows = (instances.data ?? []).filter(item => item.instance.displayName.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()) && (!state || item.instance.lifecycleState === state))
  return <Panel title="云实例" subtitle="来自最近成功同步" className="instance-panel">
    <div className="instance-filters" aria-label="筛选云实例">
      <label><span className="field-caption">实例名称</span><span className="filter-input"><Search size={16} aria-hidden="true" /><input type="search" placeholder="搜索实例名称" value={search} onChange={event => setSearch(event.target.value)} /></span></label>
      <label><span className="field-caption">状态</span><span className="filter-select"><select value={state} onChange={event => setState(event.target.value)}><option value="">全部状态</option>{[...new Set(instances.data?.map(row => row.instance.lifecycleState))].map(value => <option key={value} value={value}>{instanceStateLabel(value)}</option>)}</select><ChevronDown size={16} aria-hidden="true" /></span></label>
    </div>
    <State error={instances.error} retry={instances.retry} pending={instances.pending} empty={instances.data && !rows.length ? (search.trim() || state ? "没有符合筛选条件的实例。" : "当前没有云实例。") : undefined} />
    {rows.length > 0 && <InstanceTable instances={rows} />}
  </Panel>
}

function InstanceMetricHistory({id, initialRange}: {id: string; initialRange: DateRange}) {
  const [kind, setKind] = useState("cpu_utilization")
  const [range, setRange] = useState(initialRange)
  const valid = Boolean(range.from && range.to && range.from <= range.to)
  const metrics = useRead<MetricPoint[]>(valid ? query(`/instances/${encodeURIComponent(id)}/metrics`, {metricName:kind,...range}) : null)
  const label = kind === "cpu_utilization" ? "CPU" : "内存"
  const daily = dailyAverage(range)
  return <Panel title="指标趋势" subtitle={daily ? "UTC 日均值" : "原始采样"}>
    <div className="filter-row">
      <label>指标<select value={kind} onChange={event => setKind(event.target.value)}><option value="cpu_utilization">CPU</option><option value="memory_utilization">内存</option></select></label>
      <DateFilters range={range} onChange={setRange} />
    </div>
    <State pending={metrics.pending} error={metrics.error} retry={metrics.retry} empty={metrics.data?.length === 0 ? "所选日期内没有采样。" : undefined} />
    {metrics.data && metrics.data.length > 0 && <SeriesChart points={metrics.data.map(point => ({label:daily ? point.sampledAt.slice(0, 10) : formatTime(point.sampledAt),value:point.value}))} label={label} unit="%" />}
  </Panel>
}

function InstanceVolumes({id}: {id: string}) {
  const data = useRead<VolumeDetails>(`/instances/${encodeURIComponent(id)}/volumes`)
  const snapshot = data.data?.snapshot
  const volumes = data.data?.volumes ?? []
  const state = snapshot ? sourceState[snapshot.status] ?? snapshot.status : ""
  const attachmentStates: Record<string, string> = {ATTACHED:"已挂载",ATTACHING:"挂载中",DETACHING:"卸载中"}
  return <Panel title="OCI 云盘" className="block-panel" subtitle={snapshot?.succeededAt ? `最近成功 ${formatTime(snapshot.succeededAt)}` : undefined}>
    <State pending={data.pending} error={data.error} retry={data.retry} />
    {snapshot && <p className="state-note">{state}{snapshot.incomplete && " · 有挂载卷在当前采集范围之外"}</p>}
    {snapshot?.status === "DENIED" && <VolumeReadHint failure={snapshot.failure} />}
    {volumes.length ? <Table aria-label="OCI 云盘"><TableHeader><TableRow>
      <TableHead>卷名称</TableHead><TableHead>类型</TableHead><TableHead numeric>分配容量</TableHead><TableHead>挂载状态</TableHead>
    </TableRow></TableHeader><TableBody>{volumes.map(volume => <TableRow key={volume.id}>
      <TableCell>{volume.name}</TableCell><TableCell>{volume.kind === "BOOT" ? "启动卷" : "块卷"}</TableCell>
      <TableCell numeric>{volume.sizeGb == null ? "—" : `${formatNumber(volume.sizeGb)} GB`}</TableCell>
      <TableCell>{volume.attachmentState ? attachmentStates[volume.attachmentState] ?? volume.attachmentState : "—"}</TableCell>
    </TableRow>)}</TableBody></Table> : snapshot?.succeededAt && <State empty="本次查看范围内没有关联云盘。" />}
  </Panel>
}

function InstanceDetail() {
  const {id = ""} = useParams()
  const instances = useRead<InstanceOverview[]>("/instances")
  const instance = instances.data?.find(row => row.instance.id === id)?.instance
  const metricDates = useRead<MetricDates>(instance ? `/instances/${encodeURIComponent(id)}/metrics/dates` : null)
  const dates = metricDates.data
  return <>
    <Link className="back-link" to="/monitor/instances">返回云实例</Link>
    <State error={instances.error} retry={instances.retry} pending={instances.pending} empty={instances.data && !instance ? "云实例清单中没有这个实例。" : undefined} />
    {instance && <>
      <Panel title={instance.displayName} subtitle={`${instance.shape} · ${instance.region}`} className="block-panel">
        <dl className="scope-grid">
          <div><dt>OCPU</dt><dd>{formatNumber(instance.ocpus)}</dd></div>
          <div><dt>内存</dt><dd>{instance.memoryGb ? `${formatNumber(instance.memoryGb)} GB` : "—"}</dd></div>
          <div><dt>启动盘</dt><dd>{instance.bootVolumeGb ? `${formatNumber(instance.bootVolumeGb)} GB` : "—"}</dd></div>
        </dl>
        {dates?.firstDay && <p className="state-note">指标记录范围 {dates.firstDay} 至 {dates.lastDay} UTC</p>}
      </Panel>
      <InstanceVolumes id={id} />
      {!metricDates.pending && <InstanceMetricHistory key={id} id={id} initialRange={latestRange(dates?.lastDay, dates?.firstDay)} />}
    </>}
  </>
}

function gib(bytes: number | null) {
  return bytes == null ? "—" : `${formatNumber(bytes / 1073741824, 2)} GiB`
}

function Host() {
  const [range, setRange] = useState<DateRange>(latestRange)
  const host = useRead<HostSummary>("/server/status")
  const oci = useRead<OciStatus>("/settings/oci")
  const instances = useRead<InstanceOverview[]>(oci.data?.instanceId ? "/instances" : null)
  const instance = instances.data?.find(item => item.instance.id === oci.data?.instanceId)
  const valid = Boolean(range.from && range.to && range.from <= range.to)
  const history = useRead<HostHistoryPoint[]>(valid ? query("/server/history", {...range}) : null)
  const daily = dailyAverage(range)
  return <>
    <Panel title="本机资源趋势" subtitle={daily ? "UTC 日均采样" : "按采样时间展示"} className="block-panel host-trend-panel"
      actions={instance && <Link className="panel-link" to={`/monitor/instances/${encodeURIComponent(instance.instance.id)}`}>对应云实例</Link>}>
      <div className="filter-row"><DateFilters range={range} onChange={setRange} /></div>
      <State pending={history.pending} error={history.error} retry={history.retry} />
      {history.data && <HostTrend points={history.data} daily={daily} />}
    </Panel>
    <Panel title="本机文件系统" className="block-panel">
      <State error={host.error} retry={host.retry} pending={host.pending} />
      {host.data && <Table aria-label="本机文件系统"><TableHeader><TableRow>
        <TableHead>挂载点</TableHead><TableHead numeric>总容量</TableHead><TableHead numeric>已用</TableHead><TableHead numeric>可用</TableHead><TableHead numeric>使用率</TableHead>
      </TableRow></TableHeader><TableBody>
        {host.data.disks.map(disk => <TableRow key={disk.mount}><TableCell>{disk.mount}{disk.totalBytes == null && <small>采集不可用</small>}</TableCell>
           <TableCell numeric>{gib(disk.totalBytes)}</TableCell><TableCell numeric>{gib(disk.usedBytes)}</TableCell>
           <TableCell numeric>{gib(disk.usableBytes)}</TableCell>
           <TableCell numeric>{disk.usagePercent == null ? "—" : `${formatNumber(disk.usagePercent)}%`}</TableCell>
        </TableRow>)}
      </TableBody></Table>}
    </Panel>
  </>
}

export function Monitor() {
  return <><Heading title="监控" /><Tabs label="监控页面" items={[{to:"/monitor/host",label:"本机"},{to:"/monitor/instances",label:"云实例"}]} />
    <Routes>
      <Route index element={<Navigate to={getMonitorView()} replace />} />
      <Route path="instances" element={<Instances />} />
      <Route path="instances/:id" element={<InstanceDetail />} />
      <Route path="history/*" element={<Navigate to="/monitor/host" replace />} />
      <Route path="host" element={<Host />} />
      <Route path="*" element={<Navigate to={getMonitorView()} replace />} />
    </Routes>
  </>
}
