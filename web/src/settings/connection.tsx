import { useState } from "react"
import { Link } from "react-router-dom"
import { ArrowUpRight, Copy, RefreshCw, Terminal } from "lucide-react"
import { Button } from "@/components/glass/button"
import cloudShellScript from "../../../scripts/oci-cloud-shell-setup.sh?raw"
import { request, useRead, type OciDiagnostic, type OciStatus } from "../api"
import { Badge, Panel, SegmentedChoice, State } from "../ui"

type Guide = "instance" | "api"
const diagnosticLabels: Record<string, string> = { SUCCESS: "通过", FAILED: "失败", WARNING: "需关注", SKIPPED: "已跳过" }

function authorization(data: OciStatus) {
  const network = data.networkCompartmentId && data.networkCompartmentId !== data.compartmentId ? data.networkCompartmentId : ""
  const ids = [data.instanceId, data.tenancyId, data.compartmentId, ...(network ? [network] : [])]
  if (data.authMode !== "instance_principal" || !ids.every(value => /^ocid1\.[a-z]+\.[A-Za-z0-9._-]+$/.test(value))) return null
  const tenancyScope = data.tenancyId === data.compartmentId || data.tenancyId === network
  const args = [`--tenancy-id ${data.tenancyId}`, `--instance-id ${data.instanceId}`, `--compartment-id ${data.compartmentId}`,
    ...(network ? [`--network-compartment-id ${network}`] : []), ...(tenancyScope ? ["--allow-tenancy-scope"] : []),
    ...(data.costEnabled ? ["--with-cost"] : []), "--with-volumes", "--apply"]
  const command = ["bash -s --", ...args].join(" \\\n  ") + " <<'OCI_LENS_IAM'"
  return {command, content: `${command}\n${cloudShellScript.trimEnd()}\nOCI_LENS_IAM\n`, tenancyScope}
}

function ConnectionGuide({guide}: {guide: Guide}) {
  return guide === "instance" ? <ol className="instruction-list">
    <li><strong>确认部署方式</strong><p>在 OCI Compute 上部署，保留默认 <code>auto</code> 认证。启动时自动识别实例和所属范围。</p></li>
    <li><strong>获取只读授权命令</strong><p>启动后刷新此页。识别成功后，这里会提供可直接复制的 Cloud Shell 命令。</p></li>
  </ol> : <>
    <ol className="instruction-list">
      <li><strong>获取 API Key 配置</strong><p>在 OCI 控制台的账号设置中添加 API Key，复制配置片段并下载私钥。</p></li>
      <li><strong>挂载私钥文件</strong><p>将配置和私钥以只读方式挂载到 <code>secrets/oci</code>。<code>key_file</code> 使用容器内的路径。</p></li>
      <li><strong>设置采集范围</strong><p>在部署环境中配置 <code>OCI_REGION</code> 和 <code>OCI_COMPARTMENT_OCID</code>，重启服务后回来检查连接。</p></li>
    </ol>
    <a className="settings-inline-link" href="https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm" target="_blank" rel="noreferrer">Oracle API Key 教程 <ArrowUpRight size={14} aria-hidden="true" /></a>
  </>
}

export function ConnectionSettings() {
  const connection = useRead<OciStatus>("/settings/oci")
  const [selectedGuide, setSelectedGuide] = useState<Guide | null>(null)
  const [diagnostic, setDiagnostic] = useState<OciDiagnostic | null>(null)
  const [checking, setChecking] = useState(false)
  const [diagnosticError, setDiagnosticError] = useState("")
  const [copyError, setCopyError] = useState("")
  const [copied, setCopied] = useState(false)
  const data = connection.data
  const guide = selectedGuide ?? (data?.authMode === "instance_principal" ? "instance" : "api")
  const auth = data ? authorization(data) : null
  const status = diagnostic?.overallStatus === "SUCCESS" ? "连接通过" : data?.configured ? "已读取配置" : data ? "本机模式" : "读取配置中"

  async function copy() {
    if (!auth) return
    setCopyError("")
    try {await navigator.clipboard.writeText(auth.content); setCopied(true)}
    catch {setCopyError("剪贴板不可用，请展开完整命令后手动复制。")}
  }

  async function diagnose() {
    setChecking(true); setDiagnostic(null); setDiagnosticError("")
    try {setDiagnostic(await request<OciDiagnostic>("/settings/oci/diagnostics", "POST"))}
    catch (failure) {setDiagnosticError((failure as Error).message)} finally {setChecking(false)}
  }

  return <div className="settings-stack">
    <Panel title="OCI 连接" actions={<Badge ready={diagnostic?.overallStatus === "SUCCESS"}>{status}</Badge>}>
      <State pending={connection.pending && !data} error={connection.error} retry={connection.retry} />
      {data && (data.configured ? <dl className="connection-facts">
        <div><dt>认证方式</dt><dd>{data.authModeLabel}</dd></div><div><dt>区域</dt><dd>{data.region || "—"}</dd></div>
        <div><dt>账单读取</dt><dd>{data.costEnabled ? "已启用" : "已关闭"}</dd></div>
        <div className="connection-compartment"><dt>Compartment</dt><dd className="long-id">{data.compartmentId || "—"}</dd></div>
      </dl> : <div className="settings-callout"><p>本机采样可正常使用。完成 OCI 接入后，可同步云实例、流量和已授权的账单。</p><Link to="/monitor/host">查看本机监控 <ArrowUpRight size={14} aria-hidden="true" /></Link></div>)}
    </Panel>

    {data && (auth ? <Panel title="Cloud Shell 授权" subtitle="在租户主区域执行只读授权，再回来检查连接。">
      {auth.tenancyScope && <p className="settings-callout">当前范围包含根 Compartment。相应的只读策略覆盖整个租户。</p>}
      <div className="settings-actions"><Button variant="prominent" onClick={copy}><Copy data-icon="inline-start" />{copied ? "已复制授权命令" : "复制授权命令"}</Button>
        <Button asChild variant="glass"><a href="https://cloud.oracle.com/" target="_blank" rel="noreferrer">打开 OCI 控制台<ArrowUpRight data-icon="inline-end" /></a></Button></div>
      <State error={copyError} />
      <details className="settings-disclosure"><summary><Terminal size={16} aria-hidden="true" />查看完整命令</summary><div className="shell-preview"><pre><code>{auth.content}</code></pre></div></details>
    </Panel> : <Panel title="接入指南" subtitle={data.configured ? "当前配置已读取，可以直接检查连接。" : "按部署方式选择对应步骤。"}>
      {data.configured
        ? <details className="settings-disclosure"><summary>查看配置方法</summary><ConnectionGuide guide={guide} /></details>
        : <><SegmentedChoice label="部署方式" options={[{value:"instance",label:"OCI 实例"},{value:"api",label:"其他主机"}]} value={guide} onChange={setSelectedGuide} />
          <ConnectionGuide guide={guide} />
          <div className="settings-actions"><Button variant="glass" onClick={connection.retry} disabled={connection.pending}><RefreshCw data-icon="inline-start" />{connection.pending ? "正在刷新…" : "刷新配置状态"}</Button></div></>}
    </Panel>)}

    {data?.configured && <Panel title="连接检查" subtitle="核对身份和已配置的数据读取权限。" actions={<Button variant="prominent" onClick={diagnose} disabled={checking}>{checking ? "检查中…" : diagnostic ? "重新检查" : "检查连接"}</Button>}>
      <State error={diagnosticError} />
      {diagnostic && <div role="status"><p className="settings-feedback">{diagnostic.summary}</p><ul className="diagnostic-list">{diagnostic.steps.map(step => <li key={step.name}>
        <div><strong>{step.name}</strong><p>{step.message}</p>{step.suggestion && <p>{step.suggestion}</p>}</div><Badge ready={step.status === "SUCCESS"}>{diagnosticLabels[step.status] ?? step.status}</Badge>
      </li>)}</ul></div>}
    </Panel>}
  </div>
}
