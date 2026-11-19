import { getMonitorView, setMonitorView, type MonitorView } from "./monitor-preference"
import { useEffect, useState, type FormEvent } from "react"
import { Navigate, Route, Routes } from "react-router-dom"
import { Button } from "@/components/glass/button"
import { request, useRead, type AuthSession, type WechatStatus } from "./api"
import { Badge, Heading, Panel, State, Tabs, formatTime } from "./ui"
import { ConnectionSettings } from "./settings/connection"
import { FormFeedback, SettingSwitch } from "./settings/form"
import { QuotaSettingsPanel } from "./settings/quota"
import { ScheduleSettings } from "./settings/schedule"

type AccountProps = { session: AuthSession; onAccountUpdated: (session: AuthSession) => void; onLogout: () => void; logoutPending: boolean }

function Account({session, onAccountUpdated, onLogout, logoutPending}: AccountProps) {
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [username, setUsername] = useState(session.username)
  const [passwordEdited, setPasswordEdited] = useState(false)
  const [saving, setSaving] = useState(false)
  const busy = saving || logoutPending
  const renamed = username.trim() !== session.username
  const changed = renamed || passwordEdited

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!changed || busy) return
    setError(""); setNotice("")
    const element = event.currentTarget
    const form = new FormData(element)
    const newPassword = form.get("newPassword")
    const confirmPassword = form.get("confirmPassword")
    if (newPassword !== confirmPassword) {
      setError("两次输入的新密码不一致。")
      element.querySelector<HTMLInputElement>("[name=confirmPassword]")?.focus()
      return
    }
    setSaving(true)
    try {
      const saved = await request<AuthSession>("/auth/credentials", "POST", {
        currentPassword: form.get("currentPassword"),
        username: renamed ? username.trim() : undefined,
        newPassword: newPassword || undefined,
        confirmPassword: confirmPassword || undefined,
      })
      element.reset()
      setUsername(saved.username)
      setPasswordEdited(false)
      onAccountUpdated(saved)
      setNotice("登录凭据已更新。")
    }
    catch (failure) {setError((failure as Error).message)} finally {setSaving(false)}
  }
  return <div className="settings-stack">
    <Panel className="account-session" title="当前登录" subtitle={session.username} actions={<Button variant="glass" disabled={busy} onClick={onLogout}>{logoutPending ? "正在退出…" : "退出登录"}</Button>}>{null}</Panel>
    <Panel title="登录凭据" subtitle="修改账号名或密码时，需要验证当前密码。">
      <form className="settings-form" onSubmit={save} onChange={() => {if (notice) setNotice(""); if (error) setError("")}}>
        <fieldset className="settings-fields settings-fields-pair" disabled={busy}>
          <label>账号名<input name="username" value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={64} required /></label>
          <label>当前密码<input type="password" autoComplete="current-password" name="currentPassword" required /></label>
          <label>新密码<input type="password" autoComplete="new-password" name="newPassword" minLength={12} maxLength={128} aria-describedby="new-password-hint" onChange={event => setPasswordEdited(event.target.value !== "")} /></label>
          <label>确认新密码<input type="password" autoComplete="new-password" name="confirmPassword" minLength={12} maxLength={128} /></label>
          <p id="new-password-hint" className="settings-meta settings-field-wide">新密码至少 12 位。仅修改账号名时可留空。</p>
        </fieldset>
        <FormFeedback error={error} notice={notice} />
        <div className="settings-actions"><Button type="submit" variant="prominent" disabled={busy || !changed}>{saving ? "保存中…" : "保存更改"}</Button></div>
      </form>
    </Panel>
  </div>
}

function notificationDraft(value: WechatStatus) {
  return {enabled:value.enabled, immediatePushEnabled:value.immediatePushEnabled, dailySummaryEnabled:value.dailySummaryEnabled,
    detailPageEnabled:value.detailPageEnabled, dailySummaryTime:value.dailySummaryTime, zoneId:value.zoneId, detailPageTokenTtlDays:value.detailPageTokenTtlDays}
}

function NotificationForm({value}: {value: WechatStatus}) {
  const [saved, setSaved] = useState(value)
  const [draft, setDraft] = useState(() => notificationDraft(value))
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState("")
  const [error, setError] = useState("")
  const [credentialEdited, setCredentialEdited] = useState(false)
  const changed = credentialEdited || JSON.stringify(draft) !== JSON.stringify(notificationDraft(saved))

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!changed || busy) return
    setNotice(""); setError(""); setBusy(true)
    const element = event.currentTarget
    const credentials = Object.fromEntries([...new FormData(element)].filter(([,field]) => String(field).trim()))
    try {
      const next = await request<WechatStatus>("/settings/wechat", "PUT", {...credentials, ...draft})
      setSaved(next); setDraft(notificationDraft(next)); element.reset(); setCredentialEdited(false); setNotice("通知设置已保存。")
    } catch (failure) {setError((failure as Error).message)} finally {setBusy(false)}
  }

  return <Panel title="微信通知" actions={<Badge ready={saved.enabled && saved.configured}>{!saved.enabled ? "已关闭" : saved.configured ? "已启用" : "待配置"}</Badge>}>
    <form className="settings-form" onSubmit={save} onChange={() => {if (notice) setNotice("")}}>
      <SettingSwitch label="启用微信通知" description="启用后可通过公众号接收告警和日报。" checked={draft.enabled} onChange={enabled => {setDraft({...draft,enabled}); if (!enabled) setCredentialEdited(false)}} disabled={busy} />
      {draft.enabled && <>
        <details className="settings-disclosure" open={!saved.configured || undefined}>
          <summary>公众号与接收人</summary>
          {saved.configured && <p className="settings-meta">留空保留已有配置。当前接收人 {saved.recipientCount} 位。</p>}
          <div className="settings-fields settings-fields-pair" onChange={event => setCredentialEdited([...event.currentTarget.querySelectorAll("input")].some(input => input.value.trim() !== ""))}>
            <label>公众号 App ID<input name="appId" autoComplete="off" disabled={busy} /></label>
            <label>App Secret<input name="appSecret" type="password" autoComplete="new-password" disabled={busy} /></label>
            <label>运行模板 ID<input name="templateId" disabled={busy} /></label><label>费用模板 ID<input name="costTemplateId" disabled={busy} /></label>
            <label className="settings-field-wide">接收人 OpenID<input name="openIds" placeholder="多个值用逗号分隔" disabled={busy} /></label>
          </div>
        </details>
        <div className="settings-switches">
          <SettingSwitch label="推送即时告警" checked={draft.immediatePushEnabled} onChange={immediatePushEnabled => setDraft({...draft,immediatePushEnabled})} disabled={busy} />
          <SettingSwitch label="发送日报" checked={draft.dailySummaryEnabled} onChange={dailySummaryEnabled => setDraft({...draft,dailySummaryEnabled})} disabled={busy} />
          {draft.dailySummaryEnabled && <div className="settings-fields settings-fields-pair">
            <label>发送时间<input type="time" required value={draft.dailySummaryTime} onChange={event => setDraft({...draft,dailySummaryTime:event.target.value})} disabled={busy} /></label>
            <label>时区<input value={draft.zoneId} required onChange={event => setDraft({...draft,zoneId:event.target.value})} disabled={busy} /></label>
          </div>}
          <SettingSwitch label="允许访问脱敏日报详情" checked={draft.detailPageEnabled} onChange={detailPageEnabled => setDraft({...draft,detailPageEnabled})} disabled={busy} />
          {draft.detailPageEnabled && <div className="settings-fields settings-fields-narrow"><label>详情有效天数<input type="number" min="1" max="7" required value={draft.detailPageTokenTtlDays || ""} onChange={event => setDraft({...draft,detailPageTokenTtlDays:Number(event.target.value)})} disabled={busy} /></label></div>}
        </div>
      </>}
      <FormFeedback error={error} notice={notice} />
      <div className="settings-actions"><Button type="submit" variant="prominent" disabled={busy || !changed}>{busy ? "保存中…" : "保存通知"}</Button></div>
    </form>
  </Panel>
}

function Notifications() {
  const settings = useRead<WechatStatus>("/settings/wechat")
  if (settings.data) return <NotificationForm value={settings.data} />
  return <Panel title="微信通知"><State pending={settings.pending} error={settings.error} retry={settings.retry} /></Panel>
}

type PublicReportLink = {id: string; token: string | null}

function PublicReport({id, token}: PublicReportLink) {
  const [report, setReport] = useState<{title?: string; generatedAt?: string; reportDate?: string; instances?: {totalCount:number; runningCount:number}} | null>(null)
  const [error, setError] = useState("")
  useEffect(() => {
    const controller = new AbortController()
    if (!token || !id) {setError("报告不存在或已过期。"); return}
    fetch(`/api/v1/public/reports/${encodeURIComponent(id)}`, {headers:{Authorization:`Bearer ${token}`},cache:"no-store",signal:controller.signal}).then(async response => {
      if (!response.ok) throw new Error()
      const payload = await response.json()
      const snapshot = payload.data?.report ?? payload.data
      if (!snapshot) throw new Error()
      if (!controller.signal.aborted) setReport(snapshot)
    }).catch(() => {if (!controller.signal.aborted) setError("报告不存在或已过期。")})
    return () => controller.abort()
  }, [id, token])
  return <main className="workspace-content"><Heading title={report?.title ?? "OCI 运行日报"} subtitle={report ? `${report.reportDate ?? ""} · ${formatTime(report.generatedAt)}` : undefined} /><State error={error} pending={!report && !error} />
    {report?.instances && <Panel title="实例摘要"><p>运行 {report.instances.runningCount} 台 / 共 {report.instances.totalCount} 台</p></Panel>}
  </main>
}

function MonitorSettings() {
  const [view, setView] = useState<MonitorView>(getMonitorView)
  return <Panel title="默认监控视图" subtitle="设置保存在当前浏览器">
    <div className="filter-row"><label>打开监控时显示<select value={view} onChange={event => {
      const next = event.target.value === "instances" ? "instances" : "host"
      setView(next)
      setMonitorView(next)
    }}><option value="host">本机</option><option value="instances">云实例</option></select></label></div>
  </Panel>
}

export function Settings(props: AccountProps | {report: PublicReportLink}) {
  if ("report" in props) return <PublicReport {...props.report} />
  return <section className="settings-page"><Heading title="设置" /><Tabs label="设置页面" items={[{to:"/settings/monitor",label:"监控偏好"},{to:"/settings/oci",label:"OCI 接入"},{to:"/settings/sync",label:"同步计划"},{to:"/settings/quota",label:"免费额度"},{to:"/settings/account",label:"账号"},{to:"/settings/notifications",label:"通知"}]} />
    <Routes><Route index element={<Navigate to="oci" replace />} /><Route path="monitor" element={<MonitorSettings />} /><Route path="oci" element={<ConnectionSettings />} /><Route path="sync" element={<ScheduleSettings />} /><Route path="quota" element={<QuotaSettingsPanel />} /><Route path="account" element={<Account {...props} />} /><Route path="notifications" element={<Notifications />} /><Route path="*" element={<Navigate to="oci" replace />} /></Routes>
  </section>
}
