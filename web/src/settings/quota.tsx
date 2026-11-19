import { useState, type FormEvent } from "react"
import { Button } from "@/components/glass/button"
import { request, useRead, type QuotaSettings } from "../api"
import { Panel, State } from "../ui"
import { FormFeedback } from "./form"

const fields = [
  ["ampereOcpuHours", "A1 OCPU 小时 / 月"],
  ["ampereMemoryGbHours", "A1 内存 GB 小时 / 月"],
  ["blockVolumeGb", "启动卷与块卷 GB"],
  ["outboundDataTransferGb", "出站流量 GB / 月"],
] as const

export function QuotaSettingsPanel() {
  const quota = useRead<QuotaSettings>("/settings/quota")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!quota.data) return
    const data = new FormData(event.currentTarget)
    const next = {...quota.data}
    for (const [field] of fields) next[field] = Number(data.get(field))
    setBusy(true)
    setError("")
    setNotice("")
    try {
      await request("/settings/quota", "PUT", next)
      quota.retry()
      setNotice("额度配置已保存。")
    } catch (failure) {
      setError((failure as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return <Panel title="用量上限" subtitle="按当前租户核实的用量上限填写。出站 10 TB 对应 10,000 GB。">
    <State pending={quota.pending && !quota.data} error={quota.error} retry={quota.retry} />
    {quota.data && <form key={quota.data.updatedAt} onSubmit={save} onChange={() => setNotice("")}>
      <fieldset className="filter-row" disabled={busy}>
        {fields.map(([field, label]) => <label key={field}>{label}<input name={field} type="number" min="0" step="any" required defaultValue={quota.data?.[field]} /></label>)}
        <Button variant="prominent" type="submit">{busy ? "正在保存…" : "保存额度"}</Button>
      </fieldset>
    </form>}
    <FormFeedback error={error} notice={notice} />
  </Panel>
}
