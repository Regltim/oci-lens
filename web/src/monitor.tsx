import { useState } from "react"
import { Link, Navigate, Route, Routes, useParams } from "react-router-dom"
import { ChevronDown, Search } from "lucide-react"
import { type HostSummary, type InstanceOverview, type MetricPoint, query, useRead } from "./api"
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

function InstanceDetail() {
  const {id = ""} = useParams()
  const instances = useRead<InstanceOverview[]>("/instances")
  const entry = instances.data?.find(row => row.instance.id === id)
  const [kind, setKind] = useState("cpu_utilization")
  const metrics = useRead<MetricPoint[]>(entry ? query(`/instances/${encodeURIComponent(id)}/metrics`, {metricName:kind}) : null)
  const label = kind === "cpu_utilization" ? "CPU" : "内存"
  return <>
    <Link className="back-link" to="/monitor/instances">返回云实例</Link>
    <State error={instances.error} retry={instances.retry} pending={instances.pending} empty={instances.data && !entry ? "当前清单没有该实例。" : undefined} />
    {entry && <Panel title={entry.instance.displayName} subtitle={`${entry.instance.shape} · ${entry.instance.region}`}>
      <dl className="scope-grid"><div><dt>OCPU</dt><dd>{formatNumber(entry.instance.ocpus)}</dd></div><div><dt>内存</dt><dd>{entry.instance.memoryGb} GiB</dd></div><div><dt>启动盘</dt><dd>{entry.instance.bootVolumeGb > 0 ? `${entry.instance.bootVolumeGb} GiB` : "—"}</dd></div></dl>
      <div className="filter-row"><label>指标<select value={kind} onChange={event => setKind(event.target.value)}><option value="cpu_utilization">CPU</option><option value="memory_utilization">内存</option></select></label></div>
      <State pending={metrics.pending} error={metrics.error} retry={metrics.retry} empty={metrics.data?.length === 0 ? "当前没有采样。" : undefined} />
      {Boolean(metrics.data?.length) && <SeriesChart points={metrics.data!.map(point => ({label:formatTime(point.sampledAt),value:point.value}))} label={label} unit="%" />}
    </Panel>}
  </>
}

function Host() {
  const host = useRead<HostSummary>("/server/status")
  const current = host.data?.current
  return <>
    <State error={host.error} retry={host.retry} pending={host.pending} empty={host.data && !current ? "等待本机采样。" : undefined} />
    {current && <><div className="metric-grid">
      <Metric label="CPU 已用" value={formatNumber(current.cpuUsagePercent)} unit="%" />
      <Metric label="内存已用" value={formatNumber(current.memoryUsagePercent)} unit="%" />
      <Metric label="磁盘已用" value={formatNumber(current.diskUsagePercent)} unit="%" />
      <Metric label="运行时间" value={formatNumber(current.uptimeSeconds / 3600)} unit="小时" />
    </div><Panel title="CPU 趋势" subtitle={`本机采样 · ${formatTime(current.sampledAt)}`}><SeriesChart points={(host.data?.history ?? []).map(item => ({label:formatTime(item.sampledAt),value:item.cpuUsagePercent}))} label="本机 CPU" unit="%" /></Panel></>}
  </>
}

export function Monitor() {
  return <><Heading title="监控" subtitle="云实例和部署主机" /><Tabs label="监控页面" items={[{to:"/monitor/instances",label:"云实例"},{to:"/monitor/host",label:"本机"}]} />
    <Routes><Route index element={<Navigate to="instances" replace />} /><Route path="instances" element={<Instances />} /><Route path="instances/:id" element={<InstanceDetail />} /><Route path="host" element={<Host />} /><Route path="*" element={<Navigate to="instances" replace />} /></Routes>
  </>
}
