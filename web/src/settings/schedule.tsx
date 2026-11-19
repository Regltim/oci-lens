import { useState, type FormEvent } from "react"
import { Link } from "react-router-dom"
import { ArrowUpRight, Plus } from "lucide-react"
import { Button } from "@/components/glass/button"
import { request, useRead, type MaintenanceWindow, type OciStatus, type SyncSchedule } from "../api"
import { Badge, Panel, State, formatTime } from "../ui"
import { FormFeedback, SettingSwitch } from "./form"

function initialDraft(schedule: SyncSchedule) {
  const [, minute = "0", hour = "8"] = schedule.cronExpression.trim().split(/\s+/)
  return {enabled: schedule.enabled, time: `${hour.padStart(2, "0")}:${minute.padStart(2, "0")}`, zoneId: schedule.zoneId}
}

function ScheduleForm({schedule, configured}: {schedule: SyncSchedule; configured?: boolean}) {
  const [saved, setSaved] = useState(schedule)
  const [draft, setDraft] = useState(() => initialDraft(schedule))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const previous = initialDraft(saved)
  const candidate = draft.enabled ? {...draft,zoneId:draft.zoneId.trim()} : {...previous,enabled:false}
  const changed = previous.enabled !== candidate.enabled || previous.time !== candidate.time || previous.zoneId !== candidate.zoneId
  const nextRun = !saved.enabled ? "每日同步已暂停。" : configured === false ? "OCI 接入后，同步计划才会运行。" : saved.nextRunAt ? `计划运行时间 ${formatTime(saved.nextRunAt)}` : "暂无计划运行时间。"

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!changed || busy) return
    const [hour, minute] = candidate.time.split(":").map(Number)
    setBusy(true); setError(""); setNotice("")
    try {
      const result = await request<SyncSchedule>("/sync/schedule", "PUT", {enabled:candidate.enabled,cronExpression:`0 ${minute} ${hour} * * *`,zoneId:candidate.zoneId,syncOnStartup:false})
      setSaved(result); setDraft(initialDraft(result)); setNotice("同步计划已保存。")
    } catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }

  return <form className="settings-form" onSubmit={save} onChange={() => {if (notice) setNotice("")}}>
    <SettingSwitch label="启用每日同步" description="按设置的时间读取 OCI 数据。" checked={draft.enabled} onChange={enabled => setDraft({...draft, enabled})} disabled={busy} />
    {draft.enabled && <div className="settings-fields settings-fields-pair">
      <label>同步时间<input name="time" type="time" required value={draft.time} onChange={event => setDraft({...draft,time:event.target.value})} disabled={busy} /></label>
      <label>时区<input name="zoneId" required value={draft.zoneId} placeholder="Asia/Shanghai" onChange={event => setDraft({...draft,zoneId:event.target.value})} disabled={busy} /></label>
    </div>}
    <p className="settings-meta">{nextRun}</p>
    <FormFeedback error={error} notice={notice} />
    <div className="settings-actions">
      <Button type="submit" variant="prominent" disabled={busy || !changed}>{busy ? "保存中…" : "保存计划"}</Button>
      {changed && <Button type="button" variant="link" disabled={busy} onClick={() => {setDraft(previous); setError("")}}>撤销修改</Button>}
    </div>
  </form>
}

function Maintenance() {
  const windows = useRead<MaintenanceWindow[]>("/maintenance-windows")
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setNotice("")
    const form = new FormData(event.currentTarget)
    const start = new Date(String(form.get("startsAt")))
    const end = new Date(String(form.get("endsAt")))
    if (!(end > start) || end <= new Date()) {setError("结束时间须晚于开始时间和当前时间。"); return}
    setBusy(true)
    try {
      await request("/maintenance-windows", "POST", {reason:form.get("reason"),startsAt:start.toISOString(),endsAt:end.toISOString()})
      setEditing(false); setNotice("维护窗口已添加。"); windows.retry()
    } catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }

  async function end(id: string, planned: boolean) {
    setBusy(true); setError(""); setNotice("")
    try {await request(`/maintenance-windows/${encodeURIComponent(id)}/end`, "POST"); setConfirming(null); setNotice(planned ? "维护窗口已取消。" : "维护窗口已结束。"); windows.retry()}
    catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }

  const now = Date.now()
  return <Panel title="维护窗口" subtitle="窗口内暂停即时告警推送，采样继续运行。" actions={editing ? undefined : <Button variant="glass" onClick={() => {setEditing(true); setConfirming(null); setError(""); setNotice("")}}><Plus data-icon="inline-start" />添加窗口</Button>}>
    <State pending={windows.pending && !windows.data} error={windows.error} retry={windows.retry} />
    {windows.data?.length === 0 && !editing && <p className="settings-meta">暂无维护窗口。</p>}
    {windows.data && windows.data.length > 0 && <ul className="diagnostic-list">{windows.data.map(item => {
      const planned = Date.parse(item.startsAt) > now
      const cancelled = item.startsAt === item.endsAt
      const ended = Date.parse(item.endsAt) <= now
      const active = !planned && !ended
      return <li key={item.id}>
        <div><strong>{item.reason}</strong><p>{formatTime(item.startsAt)} 至 {formatTime(item.endsAt)}</p></div>
        <div className="window-actions"><Badge ready={active}>{cancelled ? "已取消" : planned ? "待开始" : ended ? "已结束" : "进行中"}</Badge>
          {!cancelled && !ended && confirming !== item.id && <Button variant="link" disabled={busy || editing} onClick={() => {setConfirming(item.id); setError(""); setNotice("")}}>{planned ? "取消窗口" : "提前结束"}</Button>}</div>
        {confirming === item.id && <div className="settings-confirm"><p>{planned ? "取消后这个窗口将不会启动。" : "结束后恢复即时告警推送。"}</p><div className="settings-actions"><Button variant="glass" disabled={busy} onClick={() => setConfirming(null)}>返回</Button><Button variant="destructive" disabled={busy} onClick={() => end(item.id, planned)}>{planned ? "确认取消" : "确认结束"}</Button></div></div>}
      </li>
    })}</ul>}
    {editing && <form className="settings-form maintenance-form" onSubmit={add}>
      <div className="settings-fields"><label>维护原因<input name="reason" maxLength={120} required disabled={busy} /></label></div>
      <div className="settings-fields settings-fields-pair"><label>开始时间<input name="startsAt" type="datetime-local" required disabled={busy} /></label><label>结束时间<input name="endsAt" type="datetime-local" required disabled={busy} /></label></div>
      <div className="settings-actions"><Button type="submit" variant="prominent" disabled={busy}>{busy ? "保存中…" : "保存窗口"}</Button><Button type="button" variant="glass" disabled={busy} onClick={() => {setEditing(false); setError("")}}>取消</Button></div>
    </form>}
    <FormFeedback error={error} notice={notice} />
  </Panel>
}

export function ScheduleSettings() {
  const schedule = useRead<SyncSchedule>("/sync/schedule")
  const connection = useRead<OciStatus>("/settings/oci")
  return <div className="settings-stack">
    <Panel title="每日同步" actions={<Link to="/sync" className="settings-inline-link">同步记录 <ArrowUpRight size={14} aria-hidden="true" /></Link>}>
      <State pending={schedule.pending && !schedule.data} error={schedule.error} retry={schedule.retry} />
      {schedule.data && <ScheduleForm schedule={schedule.data} configured={connection.data?.configured} />}
    </Panel>
    <Maintenance />
  </div>
}
