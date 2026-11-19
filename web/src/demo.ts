import type { Budget, CostSummary, HostSummary, InstanceOverview, MaintenanceWindow, ManualCost, SyncSchedule, TrafficSummary, WechatStatus } from "./api"

const now = new Date()
const day = (offset: number) => new Date(now.getTime() - offset * 86400000).toISOString().slice(0,10)
const at = (offset: number) => `${day(offset)}T00:00:00Z`
const ids = ["ocid1.instance.oc1.ap-tokyo-1.demoa", "ocid1.instance.oc1.ap-tokyo-1.demob"]
const currency = "USD"
const instances: InstanceOverview[] = ids.map((id, index) => ({
  instance:{id,displayName:index ? "home-services" : "lens-monitor",region:"ap-tokyo-1",lifecycleState:"RUNNING",shape:"VM.Standard.A1.Flex",ocpus:2,memoryGb:12,bootVolumeGb:50,publicIp:"",privateIp:"",updatedAt:at(0)},
  cpuUtilization:index ? 12.6 : 8.2, memoryUtilization:index ? 42 : 31, egressGbToday:index ? 1.16 : 2.68,
}))
const traffic: TrafficSummary = {
  daily:[2.21,2.91,2.54,3.36,3.12,3.53,3.84].flatMap((amount,index) => ids.map((instanceId,i) => ({instanceId,statDate:day(7 - index),ingressGb:0.45,egressGb:amount * (i ? 0.3 : 0.7)}))),
  coverage:[{statDate:day(7), status:"GAP", message:"部分采样未返回，可补采"}],
}
const costs: CostSummary = { daily:[{serviceName:"Block Storage",resourceId:"",statDate:day(3),costAmount:"0.42",currency}], manualCosts:[], coverage:[{statDate:day(1),status:"PENDING",message:"账单待核对"}], through:day(3) }
const host: HostSummary = {
  current:{sampledAt:now.toISOString(),cpuUsagePercent:12.8,memoryUsagePercent:42,diskUsagePercent:34,diskTotalBytes:64424509440,diskUsableBytes:42520176230,networkRxBytesPerSecond:0,networkTxBytesPerSecond:0,uptimeSeconds:340200},
  history:[9,14,11,10,15,12.8].map((cpuUsagePercent,index) => ({cpuUsagePercent,memoryUsagePercent:42,diskUsagePercent:34,sampledAt:new Date(now.getTime() - (5 - index) * 300000).toISOString()})), alerts:[],
}
let schedule: SyncSchedule = {enabled:true,cronExpression:"0 0 8 * * *",zoneId:"Asia/Shanghai",syncOnStartup:false,nextRunAt:at(-1)}
let wechat: WechatStatus = {enabled:false,configured:false,dailySummaryTime:"09:00",zoneId:"Asia/Shanghai",recipientCount:0,immediatePushEnabled:true,dailySummaryEnabled:false,detailPageEnabled:false,detailPageTokenTtlDays:1,encryptionReady:false}
let username = "admin"
const windows: MaintenanceWindow[] = []
const budgets: Budget[] = []

export function demoRequest<T>(path: string, method: string, body: unknown): T {
  const url = new URL(path, "https://demo.invalid")
  const values = body as Record<string, string> | undefined
  if (method !== "GET") {
    if (url.pathname === "/auth/credentials") {
      username = values?.username ?? username
      return {username} as T
    }
    if (url.pathname === "/sync/schedule") {
      const update = body as SyncSchedule
      schedule = {...schedule,...update,nextRunAt:update.enabled ? at(-1) : ""}
      return schedule as T
    }
    if (url.pathname === "/settings/wechat") {
      const {enabled, dailySummaryTime, zoneId, immediatePushEnabled, dailySummaryEnabled, detailPageEnabled, detailPageTokenTtlDays} = body as WechatStatus
      wechat = {...wechat,enabled,dailySummaryTime,zoneId,immediatePushEnabled,dailySummaryEnabled,detailPageEnabled,detailPageTokenTtlDays}
      return wechat as T
    }
    if (url.pathname === "/maintenance-windows" && values) windows.push({id:String(windows.length + 1),reason:values.reason,startsAt:values.startsAt,endsAt:values.endsAt})
    const ending = url.pathname.match(/^\/maintenance-windows\/([^/]+)\/end$/)
    if (ending) {
      const window = windows.find(item => item.id === ending[1])
      if (window) window.endsAt = new Date(Math.max(Date.parse(window.startsAt), Date.now())).toISOString()
    }
    if (url.pathname.startsWith("/cost-budgets/") && values) {
      const [, , month, currency] = url.pathname.split("/")
      const budget = {month,currency,amount:values.amount,usedAmount:"0.42",reachedThreshold:0}
      const index = budgets.findIndex(row => row.month === month && row.currency === currency)
      if (index < 0) budgets.push(budget)
      else budgets[index] = budget
    }
    const manual = url.pathname.match(/^\/costs\/manual(?:\/([^/]+))?$/)
    if (manual) {
      const id = manual[1]
      if (method === "POST" && values) costs.manualCosts.push({...values,id:String(Date.now())} as unknown as ManualCost)
      if (method === "PUT" && values) costs.manualCosts = costs.manualCosts.map(row => row.id === id ? {...row,...values} : row)
      if (method === "DELETE") costs.manualCosts = costs.manualCosts.filter(row => row.id !== id)
    }
    if (url.pathname.endsWith("/diagnostics")) return {summary:"示例连接检查通过。",overallStatus:"SUCCESS",steps:[{name:"读取实例",status:"SUCCESS",message:"示例只读检查通过",suggestion:"",durationMs:12}]} as T
    return {status:"SUCCESS",username} as T
  }
  if (url.pathname.endsWith("/metrics")) return host.history.map(item => ({sampledAt:item.sampledAt,value:item.cpuUsagePercent})) as T
  if (url.pathname === "/traffic/summary") {
    const month = url.searchParams.get("month") ?? ""
    const id = url.searchParams.get("instanceId") ?? ""
    return {daily:traffic.daily.filter(row => row.statDate.startsWith(month) && (!id || row.instanceId === id)),coverage:traffic.coverage.filter(row => row.statDate.startsWith(month))} as T
  }
  if (url.pathname === "/costs/summary") {
    const month = url.searchParams.get("month") ?? ""
    return {
      daily:costs.daily.filter(row => row.statDate.startsWith(month)), manualCosts:costs.manualCosts.filter(row => row.occurredOn.startsWith(month)),
      coverage:costs.coverage.filter(row => row.statDate.startsWith(month)), through:costs.through?.startsWith(month) ? costs.through : "",
    } as T
  }
  if (url.pathname === "/cost-budgets") return budgets.filter(row => row.month === url.searchParams.get("month")) as T
  const fixtures: Record<string, unknown> = {
    "/instances":instances,"/server/status":host,
    "/sync/schedule":schedule,"/maintenance-windows":windows,
    "/sync/status":{configured:true,hasData:true,lastStatus:"SUCCESS",lastMessage:"已读取示例快照",lastStartedAt:at(0),lastFinishedAt:at(0),lastSuccessfulAt:at(0)},
    "/sync/changes":[{title:"本月首次出现费用",description:"Block Storage · US$0.42"},{title:"实例状态无变化",description:"两台实例持续运行"}],
    "/sync/history":[{id:"demo",syncType:"SCHEDULED",status:"SUCCESS",message:"已读取示例快照",startedAt:at(0),finishedAt:at(0),instanceCount:2,metricCount:24,trafficCount:14,costCount:1}],
    "/settings/oci":{configured:true,authMode:"instance_principal",authModeLabel:"Instance Principal",instanceId:ids[0],tenancyId:"ocid1.tenancy.oc1..demo",compartmentId:"ocid1.compartment.oc1..demo",region:"ap-tokyo-1",costEnabled:false},
    "/settings/wechat":wechat,
  }
  if (!(url.pathname in fixtures)) throw new Error("该接口没有示例数据。")
  return fixtures[url.pathname] as T
}
