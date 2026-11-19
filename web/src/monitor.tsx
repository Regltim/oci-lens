import { useState } from "react"
import { Link, Navigate, Route, Routes, useParams } from "react-router-dom"
import { ChevronDown, Search } from "lucide-react"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/glass/table"
import { type HistoricalInstance, type HostHistoryPoint, type HostHistorySource, type HostSummary, type InstanceOverview, type MetricDates, type MetricPoint, type OciStatus, type VolumeDetails, query, useRead } from "./api"
import { dailyAverage, DateFilters, latestRange, type DateRange } from "./history-date-range"
import { getMonitorView } from "./monitor-preference"
import { sourceState } from "./quota"
import { InstanceTable, instanceStateLabel } from "./overview"
import { SeriesChart } from "./traffic-chart"
import { Heading, Metric, Panel, State, Tabs, formatNumber, formatTime } from "./ui"

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
    <State error={instances.error} retry={instances.retry} pending={instances.pending} empty={instances.data && !rows.length ? (search.trim() || state ? "没有符合筛选条件的实例。" : "当前没有实例快照。") : undefined} />
    {rows.length > 0 && <InstanceTable instances={rows} />}
  </Panel>
}

function HistoricalInstances() {
  const history = useRead<HistoricalInstance[]>("/instances/history")
  return <Panel title="历史实例" subtitle="按实例回看已保留的云指标" className="instance-panel">
    <State error={history.error} retry={history.retry} pending={history.pending} empty={history.data?.length === 0 ? "当前没有历史实例。" : undefined} />
    {history.data && history.data.length > 0 && <div className="change-list">{history.data.map(instance => (
      <div className="change-row" key={instance.id}>
        <Link className="panel-link" to={`/monitor/history/${encodeURIComponent(instance.id)}`}>{instance.displayName}</Link>
        <small>{[instance.shape, instance.region, instance.firstDay && `${instance.firstDay} 至 ${instance.lastDay}`].filter(Boolean).join(" · ") || "暂无指标采样"}</small>
      </div>
    ))}</div>}
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
    {volumes.length ? <Table aria-label="OCI 云盘"><TableHeader><TableRow>
      <TableHead>卷名称</TableHead><TableHead>类型</TableHead><TableHead numeric>分配容量</TableHead><TableHead>挂载状态</TableHead>
    </TableRow></TableHeader><TableBody>{volumes.map(volume => <TableRow key={volume.id}>
      <TableCell>{volume.name}</TableCell><TableCell>{volume.kind === "BOOT" ? "启动卷" : "块卷"}</TableCell>
      <TableCell numeric>{volume.sizeGb == null ? "—" : `${formatNumber(volume.sizeGb)} GB`}</TableCell>
      <TableCell>{volume.attachmentState ? attachmentStates[volume.attachmentState] ?? volume.attachmentState : "—"}</TableCell>
    </TableRow>)}</TableBody></Table> : snapshot?.succeededAt && <State empty="本次查看范围内没有关联云盘。" />}
  </Panel>
}

function InstanceDetail({archived = false}: {archived?: boolean}) {
  const {id = ""} = useParams()
  const instances = useRead<InstanceOverview[]>(archived ? null : "/instances")
  const entry = instances.data?.find(row => row.instance.id === id)
  const history = useRead<HistoricalInstance[]>(archived || (instances.data && !entry) ? "/instances/history" : null)
  const historical = history.data?.find(row => row.id === id)
  const name = entry?.instance.displayName ?? historical?.displayName
  const specs = entry?.instance ?? historical
  const metricDates = useRead<MetricDates>(entry ? `/instances/${encodeURIComponent(id)}/metrics/dates` : null)
  const dates = historical ?? metricDates.data
  return <>
    <Link className="back-link" to={archived ? "/monitor/history" : "/monitor/instances"}>{archived ? "返回历史实例" : "返回云实例"}</Link>
    <State error={instances.error || history.error} retry={instances.error ? instances.retry : history.retry} pending={instances.pending || history.pending} empty={history.data && !name ? "没有该实例的记录。" : undefined} />
    {name && <Panel title={name} subtitle={entry ? `${entry.instance.shape} · ${entry.instance.region}` : historical?.lifecycleState ? `最后记录的状态 · ${instanceStateLabel(historical.lifecycleState)}` : "历史实例"} className="block-panel">
      {specs && <dl className="scope-grid">
        <div><dt>OCPU</dt><dd>{formatNumber(specs.ocpus)}</dd></div>
        <div><dt>内存</dt><dd>{specs.memoryGb ? `${formatNumber(specs.memoryGb)} GB` : "—"}</dd></div>
        <div><dt>启动盘</dt><dd>{specs.bootVolumeGb ? `${formatNumber(specs.bootVolumeGb)} GB` : "—"}</dd></div>
      </dl>}
      {dates?.firstDay && <p className="state-note">指标记录范围 {dates.firstDay} 至 {dates.lastDay} UTC</p>}
    </Panel>}
    {entry && <InstanceVolumes id={id} />}
    {name && !metricDates.pending && <InstanceMetricHistory key={id} id={id} initialRange={latestRange(dates?.lastDay, dates?.firstDay)} />}
  </>
}

function networkRate(value?: number | null) {
  return value == null ? null : value / 1024
}

const hostTrends = [
  {key:"cpuUsagePercent", label:"CPU", unit:"%"},
  {key:"memoryUsagePercent", label:"内存", unit:"%"},
  {key:"diskUsagePercent", label:"根分区", unit:"%"},
  {key:"networkTxBytesPerSecond", label:"网卡出站", unit:"KiB/s"},
  {key:"networkRxBytesPerSecond", label:"网卡入站", unit:"KiB/s"},
] as const

function HostSeries({points, daily = false, network = true, disk = true}: {points: HostHistoryPoint[]; daily?: boolean; network?: boolean; disk?: boolean}) {
  const [kind, setKind] = useState<string>("cpuUsagePercent")
  const choices = hostTrends.filter(item => (network || item.unit !== "KiB/s") && (disk || item.key !== "diskUsagePercent"))
  const trend = choices.find(item => item.key === kind) ?? hostTrends[0]
  return <>
    <div className="filter-row"><label>趋势指标<select value={trend.key} onChange={event => setKind(event.target.value)}>
      {choices.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
    </select></label></div>
    {points.length ? <SeriesChart points={points.map(point => ({
      label:daily ? point.sampledAt.slice(0, 10) : formatTime(point.sampledAt),
      value:trend.unit === "KiB/s" ? networkRate(point[trend.key]) : point[trend.key],
    }))} label={trend.label} unit={trend.unit} /> : <State empty="所选时间内没有本机采样。" />}
  </>
}

function LegacyHosts() {
  const sources = useRead<HostHistorySource[]>("/server/history-sources")
  const [selected, setSelected] = useState("")
  const source = sources.data?.find(item => item.host === selected) ?? sources.data?.[0]
  return <Panel title="历史主机采样" subtitle="从旧站导入的主机记录" className="block-panel">
    <State error={sources.error} retry={sources.retry} pending={sources.pending} empty={sources.data?.length === 0 ? "当前没有导入的主机采样。" : undefined} />
    {source && <>
      <div className="filter-row"><label>来源主机<select value={source.host} onChange={event => setSelected(event.target.value)}>
        {sources.data?.map(item => <option key={item.host}>{item.host}</option>)}
      </select></label></div>
      <ArchivedHostSeries key={source.host} source={source} />
    </>}
  </Panel>
}

function ArchivedHostSeries({source}: {source: HostHistorySource}) {
  const [range, setRange] = useState(() => latestRange(source.lastDay, source.firstDay))
  const valid = Boolean(range.from && range.to && range.from <= range.to)
  const history = useRead<HostHistoryPoint[]>(valid ? query("/server/history", {...range,host:source.host}) : null)
  return <>
    <p className="state-note">数据覆盖 {source.firstDay} 至 {source.lastDay} UTC{dailyAverage(range) && " · UTC 日均值"}</p>
    <div className="filter-row"><DateFilters range={range} onChange={setRange} /></div>
    <State pending={history.pending} error={history.error} retry={history.retry} />
    {history.data && <HostSeries points={history.data} daily={dailyAverage(range)} />}
  </>
}

function gib(bytes: number | null) {
  return bytes == null ? "—" : `${formatNumber(bytes / 1073741824, 2)} GiB`
}

function Host() {
  const host = useRead<HostSummary>("/server/status")
  const oci = useRead<OciStatus>("/settings/oci")
  const instances = useRead<InstanceOverview[]>(oci.data?.instanceId ? "/instances" : null)
  const instance = instances.data?.find(item => item.instance.id === oci.data?.instanceId)
  const current = host.data?.current
  const cores = host.data?.systemInfo?.availableProcessors
  const root = host.data?.disks?.find(item => item.mount === "/")
  return <>
    <State error={host.error} retry={host.retry} pending={host.pending} empty={host.data && !current ? "等待本机采样。" : undefined} />
    {current && <><div className="metric-grid">
      <Metric label="CPU 已用" value={formatNumber(current.cpuUsagePercent)} unit="%" note={cores ? `${cores} 个逻辑核` : undefined} />
      <Metric label="内存已用" value={formatNumber(current.memoryUsagePercent)} unit="%" note={current.memoryTotalBytes > 0 ? `总计 ${gib(current.memoryTotalBytes)}` : undefined} />
      <Metric label="根分区已用" value={formatNumber(root?.usagePercent)} unit="%" />
      <Metric label="网卡出站" value={formatNumber(host.data?.networkAvailable ? networkRate(current.networkTxBytesPerSecond) : null, 2)} unit="KiB/s" note={host.data?.networkAvailable ? undefined : "网卡采样不可用"} />
    </div>
      <Panel title="本机趋势" subtitle={`最近采样 ${formatTime(current.sampledAt)} · 已运行 ${formatNumber(current.uptimeSeconds / 3600)} 小时`}
        className="block-panel" actions={instance && <Link className="panel-link" to={`/monitor/instances/${encodeURIComponent(instance.instance.id)}`}>对应云实例</Link>}>
        <HostSeries points={host.data?.history ?? []} network={host.data?.networkAvailable} disk={root?.totalBytes != null} />
      </Panel>
      <Panel title="本机文件系统" className="block-panel">
        <Table aria-label="本机文件系统"><TableHeader><TableRow>
          <TableHead>挂载点</TableHead><TableHead numeric>总容量</TableHead><TableHead numeric>已用</TableHead><TableHead numeric>可用</TableHead><TableHead numeric>使用率</TableHead>
        </TableRow></TableHeader><TableBody>
          {host.data?.disks?.map(disk => <TableRow key={disk.mount}><TableCell>{disk.mount}{disk.totalBytes == null && <small>采集不可用</small>}</TableCell>
            <TableCell numeric>{gib(disk.totalBytes)}</TableCell><TableCell numeric>{gib(disk.usedBytes)}</TableCell><TableCell numeric>{gib(disk.usableBytes)}</TableCell>
            <TableCell numeric>{disk.usagePercent == null ? "—" : `${formatNumber(disk.usagePercent)}%`}</TableCell>
          </TableRow>)}
        </TableBody></Table>
      </Panel>
    </>}
    <LegacyHosts />
  </>
}

export function Monitor() {
  return <><Heading title="监控" /><Tabs label="监控页面" items={[{to:"/monitor/host",label:"本机"},{to:"/monitor/instances",label:"云实例"},{to:"/monitor/history",label:"历史实例"}]} />
    <Routes>
      <Route index element={<Navigate to={getMonitorView()} replace />} />
      <Route path="instances" element={<Instances />} />
      <Route path="instances/:id" element={<InstanceDetail />} />
      <Route path="history" element={<HistoricalInstances />} />
      <Route path="history/:id" element={<InstanceDetail archived />} />
      <Route path="host" element={<Host />} />
      <Route path="*" element={<Navigate to={getMonitorView()} replace />} />
    </Routes>
  </>
}
