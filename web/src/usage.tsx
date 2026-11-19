import { useState, type FormEvent } from "react"
import { Navigate, Route, Routes, useSearchParams } from "react-router-dom"
import { Button } from "@/components/glass/button"
import { ScrollRegion } from "@/components/glass/scroll-region"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/glass/table"
import { query, request, useRead, type Budget, type CostSummary, type CoverageDay, type InstanceOverview, type ManualCost, type TrafficSummary } from "./api"
import { trafficDays } from "./overview"
import { SeriesChart } from "./traffic-chart"
import { Heading, Metric, Panel, State, Tabs, formatNumber, money, utcMonth } from "./ui"
import { QuotaPanel } from "./quota"
import { FormFeedback } from "./settings/form"

function Coverage({ rows, source, reload }: { rows: CoverageDay[]; source: "traffic" | "cost"; reload: () => void }) {
  const [notice, setNotice] = useState("")
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  const gaps = rows.filter(row => ["GAP", "PENDING", "UNAVAILABLE"].includes(row.status))
  if (!gaps.length) return null
  async function repair(day: string) {
    setBusy(true)
    setError("")
    setNotice("")
    try {
      await request("/sync/backfill", "POST", {source, day})
      setNotice("补采已提交，可在同步记录查看进度。")
      reload()
    } catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }
  return <Panel title="数据覆盖" className="block-panel">
    <State error={error} empty={notice} />
    <ScrollRegion label="待补采日期">
      <div className="change-list">{gaps.slice(-10).reverse().map(row => <div className="coverage-row" key={row.statDate}><span>{row.statDate}</span><span>{row.message || (row.status === "PENDING" ? "待核对" : "缺少采样")}</span>
        {row.status === "GAP" && <Button variant="link" disabled={busy} onClick={() => repair(row.statDate)}>补采这一天</Button>}</div>)}</div>
    </ScrollRegion>
  </Panel>
}

function MonthFilter({month, available, onChange}: {month: string; available: string[]; onChange: (month: string) => void}) {
  return <>
    <label>月份<input type="month" value={month} onChange={event => onChange(event.target.value)} /></label>
    {available.length > 0 && <label>有记录的月份<select value={available.includes(month) ? month : ""} onChange={event => onChange(event.target.value)}>
      <option value="" disabled>选择月份</option>
      {available.map(item => <option key={item}>{item}</option>)}
    </select></label>}
  </>
}

function Traffic() {
  const [params, setParams] = useSearchParams()
  const months = useRead<string[]>("/traffic/months")
  const month = params.get("month") || utcMonth()
  const instanceId = params.get("instance") || ""
  const instances = useRead<InstanceOverview[]>("/instances")
  const options = (instances.data ?? []).map(row => row.instance)
  const traffic = useRead<TrafficSummary>(query("/traffic/summary", {month, instanceId}))
  const rows = trafficDays(traffic.data?.daily ?? [], traffic.data?.coverage ?? [])
  const valid = rows.filter(([,row]) => row.egress != null)
  return <>
    <Panel title="主网卡流量" subtitle="按 UTC 日展示已采集流量" className="block-panel">
      <div className="filter-row usage-filters">
        <MonthFilter month={month} available={months.data ?? []} onChange={value => setParams({month:value,instance:instanceId})} />
        <label>实例<select value={instanceId} onChange={event => setParams({month,instance:event.target.value})}><option value="">全部</option>{options.map(instance => <option key={instance.id} value={instance.id}>{instance.displayName}</option>)}</select></label>
      </div>
      <State pending={traffic.pending} error={traffic.error} retry={traffic.retry} empty={traffic.data && !rows.length ? "当前月份暂无流量快照。" : undefined} />
      {valid.length > 0 && <SeriesChart points={rows.map(([day, item]) => ({label:day.slice(5),value:item.egress}))} label="主网卡出站" unit="GiB" />}
      {rows.length > 0 && <Table className="usage-data-table" aria-label="主网卡流量"><TableHeader><TableRow><TableHead>UTC 日期</TableHead><TableHead numeric>入站 GiB</TableHead><TableHead numeric>出站 GiB</TableHead></TableRow></TableHeader><TableBody>
        {rows.slice().reverse().map(([day,value]) => <TableRow key={day}><TableCell>{day}</TableCell><TableCell numeric>{formatNumber(value.ingress, 2)}</TableCell><TableCell numeric>{formatNumber(value.egress, 2)}</TableCell></TableRow>)}
      </TableBody></Table>}
    </Panel>
    <Coverage rows={traffic.data?.coverage ?? []} source="traffic" reload={traffic.retry} />
  </>
}

function BudgetPanel({month, currency}: {month: string; currency: string}) {
  const budgets = useRead<Budget[]>(query("/cost-budgets", {month}))
  const current = budgets.data?.find(item => item.currency === currency)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [saving, setSaving] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const amount = new FormData(event.currentTarget).get("amount")
    setSaving(true)
    setError(""); setNotice("")
    try {await request(`/cost-budgets/${month}/${encodeURIComponent(currency)}`, "PUT", {amount}); budgets.retry(); setNotice("预算已保存。")}
    catch (failure) {setError((failure as Error).message)} finally {setSaving(false)}
  }
  return <Panel title="月度预算" subtitle={`${month} · ${currency} · 按账单覆盖范围计算`} className="block-panel">
    <State pending={budgets.pending && !budgets.data} error={budgets.error} retry={budgets.retry} />
    {current && <p className="state-note">已入账 {money(current.usedAmount, currency)} / 预算 {money(current.amount, currency)}{current.reachedThreshold > 0 && ` · 达到 ${current.reachedThreshold}%`}</p>}
    <form className="filter-row" onSubmit={submit} onChange={() => {if (notice) setNotice("")}}><label>预算金额<input name="amount" key={`${month}-${currency}-${current?.amount ?? ""}`} type="number" min="0.01" step="0.01" defaultValue={current?.amount} required disabled={saving} /></label><Button type="submit" variant="prominent" disabled={saving}>{saving ? "保存中…" : "保存预算"}</Button></form>
    <FormFeedback error={error} notice={notice} />
  </Panel>
}

function ManualEntries({rows, month, reload}: {rows: ManualCost[]; month: string; reload: () => void}) {
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [entry, setEntry] = useState<ManualCost | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const element = event.currentTarget
    const data = Object.fromEntries(form)
    setError(""); setNotice("")
    setBusy(true)
    try {
      await request(entry ? `/costs/manual/${encodeURIComponent(entry.id)}` : "/costs/manual", entry ? "PUT" : "POST", data)
      setEntry(null); element.reset(); reload(); setNotice("费用已保存。")
    } catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }
  async function remove(row: ManualCost) {
    setError(""); setNotice(""); setBusy(true)
    try {
      await request(`/costs/manual/${encodeURIComponent(row.id)}`, "DELETE")
      if (entry?.id === row.id) setEntry(null)
      setRemoving(null); reload(); setNotice("费用已删除。")
    }
    catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }
  return <Panel title="手工费用" className="block-panel">
    {rows.length > 0 && <ScrollRegion label="手工费用列表"><div className="change-list">{rows.map(row => <div key={row.id}>
      <div className="coverage-row"><span className="usage-cost-name">{row.costName} · {row.occurredOn}</span><strong>{money(row.amount,row.currency)}</strong>
        <Button variant="link" disabled={busy} onClick={() => {setEntry(row); setRemoving(null); setNotice(""); setError("")}}>编辑</Button>
        <Button variant="link" disabled={busy} aria-expanded={removing === row.id} onClick={() => {setRemoving(row.id); setNotice(""); setError("")}}>删除</Button>
      </div>
      {removing === row.id && <div className="settings-confirm usage-cost-name">
        <p>删除「{row.costName}」这条手工费用？</p>
        <div className="settings-actions">
          <Button variant="glass" disabled={busy} onClick={() => {setRemoving(null); setError("")}}>取消</Button>
          <Button variant="destructive" disabled={busy} onClick={() => remove(row)}>{busy ? "删除中…" : "确认删除"}</Button>
        </div>
      </div>}
    </div>)}</div></ScrollRegion>}
    <form key={entry?.id ?? "new"} onSubmit={save} onChange={() => {if (notice) setNotice("")}}>
      <fieldset className="filter-row usage-entry-fields" disabled={busy}>
        <label>名称<input name="costName" defaultValue={entry?.costName} required maxLength={100} /></label>
        <label>类别<input name="category" defaultValue={entry?.category ?? "运维"} required maxLength={60} /></label>
        <label>金额<input name="amount" defaultValue={entry?.amount} type="number" min="0.01" step="0.01" required /></label>
        <label>币种<input name="currency" defaultValue={entry?.currency ?? "USD"} required pattern="[A-Z]{3}" maxLength={3} /></label>
        <label>日期<input name="occurredOn" type="date" defaultValue={entry?.occurredOn ?? `${month}-01`} required /></label>
        <label>备注<input name="note" defaultValue={entry?.note} maxLength={500} /></label>
        <div className="usage-entry-actions"><Button type="submit" variant="prominent">{busy ? "处理中…" : entry ? "保存修改" : "添加费用"}</Button>{entry && <Button type="button" variant="link" onClick={() => {setEntry(null); setError("")}}>取消编辑</Button>}</div>
      </fieldset>
    </form>
    <FormFeedback error={error} notice={notice} />
  </Panel>
}

function Costs() {
  const [params, setParams] = useSearchParams()
  const month = params.get("month") || utcMonth()
  const currency = params.get("currency") || ""
  const costs = useRead<CostSummary>(query("/costs/summary", {month}))
  const months = useRead<string[]>("/costs/months")
  const currencies = [...new Set([...(costs.data?.daily ?? []).map(row => row.currency),...(costs.data?.manualCosts ?? []).map(row => row.currency)])].sort()
  const filterCurrency = currencies.includes(currency) ? currency : ""
  const selected = filterCurrency || (currencies.length === 1 ? currencies[0] : "")
  const rows = (costs.data?.daily ?? []).filter(row => !selected || selected === row.currency)
  const pageSize = 100
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize))
  const page = Math.max(1, Math.min(pageCount, Math.trunc(Number(params.get("page")) || 1)))
  const perCurrency = new Map<string, number>()
  for (const row of rows) perCurrency.set(row.currency, (perCurrency.get(row.currency) ?? 0) + Number(row.costAmount))
  return <>
    <Panel title="OCI 账单" subtitle={costs.data?.through ? `已核对至 ${costs.data.through} UTC` : "账单有延迟，按 UTC 自然月统计"} className="block-panel">
      <div className="filter-row usage-filters">
        <MonthFilter month={month} available={months.data ?? []} onChange={value => setParams({month:value,currency:filterCurrency})} />
        <label>币种<select value={filterCurrency} onChange={event => setParams({month,currency:event.target.value})}><option value="">全部</option>{currencies.map(value => <option key={value}>{value}</option>)}</select></label>
      </div>
      <State pending={costs.pending} error={costs.error} retry={costs.retry} empty={costs.data && !rows.length ? "当前没有已入账明细。" : undefined} />
      {perCurrency.size > 0 && <div className="metric-grid">{[...perCurrency].map(([key,value]) => <Metric key={key} label={`已入账 · ${key}`} value={formatNumber(value, 2)} unit={key} />)}</div>}
      {rows.length > 0 && <Table key={`${month}/${selected}/${page}`} className="usage-data-table usage-billing-table" aria-label="OCI 账单明细"><TableHeader><TableRow><TableHead>UTC 日期</TableHead><TableHead>服务</TableHead><TableHead numeric>费用</TableHead></TableRow></TableHeader><TableBody>
        {rows.slice((page - 1) * pageSize, page * pageSize).map((row,index) => <TableRow key={`${row.statDate}-${index}`}><TableCell>{row.statDate}</TableCell><TableCell>{row.serviceName || "未归属"}</TableCell><TableCell numeric>{money(row.costAmount,row.currency)}</TableCell></TableRow>)}
      </TableBody></Table>}
      {pageCount > 1 && <div className="filter-row">
        <Button variant="link" disabled={page <= 1} onClick={() => setParams({month,currency:filterCurrency,page:String(page - 1)})}>上一页</Button>
        <span className="state-note">{page} / {pageCount}</span>
        <Button variant="link" disabled={page >= pageCount} onClick={() => setParams({month,currency:filterCurrency,page:String(page + 1)})}>下一页</Button>
      </div>}
    </Panel>
    <Coverage rows={costs.data?.coverage ?? []} source="cost" reload={costs.retry} />
    <BudgetPanel key={`${month}/${selected || "USD"}`} month={month} currency={selected || "USD"} />
    <ManualEntries key={month} rows={(costs.data?.manualCosts ?? []).filter(row => !selected || row.currency === selected)} month={month} reload={costs.retry} />
  </>
}

export function Usage() {
  return <><Heading title="用量与费用" subtitle="从本地快照查看流量和已入账金额" /><Tabs label="用量页面" items={[{to:"/usage/traffic",label:"流量"},{to:"/usage/cost",label:"费用"},{to:"/usage/quota",label:"本月使用"}]} />
    <Routes><Route index element={<Navigate to="traffic" replace />} /><Route path="traffic" element={<Traffic />} /><Route path="cost" element={<Costs />} /><Route path="quota" element={<QuotaPanel />} /><Route path="*" element={<Navigate to="traffic" replace />} /></Routes>
  </>
}
