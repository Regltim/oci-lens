import { useCallback, useEffect, useState } from "react"

export const demoMode = import.meta.env.MODE === "demo"

export interface AuthSession { username: string }
export interface Instance {
  id: string; displayName: string; region: string; lifecycleState: string; shape: string;
  ocpus: number; memoryGb: number; bootVolumeGb: number; publicIp: string; privateIp: string; updatedAt: string;
}
export interface InstanceOverview { instance: Instance; cpuUtilization: number | null; memoryUtilization: number | null; egressGbToday: number | null }
export interface HistoricalInstance { id: string; displayName: string; region: string; shape: string; firstDay: string | null; lastDay: string | null; ocpus: number | null; memoryGb: number | null; bootVolumeGb: number | null; lifecycleState: string | null }
export interface MetricDates { firstDay: string | null; lastDay: string | null }
export interface HostHistorySource { host: string; firstDay: string; lastDay: string }
export interface HostDisk { mount: string; totalBytes: number | null; usedBytes: number | null; usableBytes: number | null; usagePercent: number | null }
export interface MetricPoint { sampledAt: string; value: number | null }
export interface HostSnapshot {
  sampledAt: string; cpuUsagePercent: number; memoryUsagePercent: number; diskUsagePercent: number;
  memoryTotalBytes: number; diskTotalBytes: number; diskUsableBytes: number; networkRxBytesPerSecond: number; networkTxBytesPerSecond: number; uptimeSeconds: number;
}
export type HostHistoryPoint = Pick<HostSnapshot,
  "sampledAt" | "cpuUsagePercent" | "memoryUsagePercent" | "diskUsagePercent" | "networkRxBytesPerSecond" | "networkTxBytesPerSecond">
export interface HostSummary {
  current: HostSnapshot | null; history: HostHistoryPoint[]; disks: HostDisk[]; networkAvailable: boolean; systemInfo: {availableProcessors: number};
  alerts: Array<{ title: string; description: string; severity: string }>;
}
export interface TrafficDay { instanceId: string; statDate: string; ingressGb: number | null; egressGb: number | null }
export interface CoverageDay { statDate: string; status: string; message: string }
export interface TrafficSummary { daily: TrafficDay[]; coverage: CoverageDay[] }
export interface CostDay { serviceName: string; resourceId: string; statDate: string; costAmount: string | number; currency: string }
export interface ManualCost { id: string; costName: string; category: string; amount: string | number; currency: string; occurredOn: string; note?: string }
export interface CostSummary { daily: CostDay[]; manualCosts: ManualCost[]; coverage: CoverageDay[]; through: string | null }
export interface SyncStatus { configured: boolean; hasData: boolean; lastStatus: string; lastMessage: string; lastStartedAt: string; lastFinishedAt: string; lastSuccessfulAt?: string }
export interface SyncRun { id: string; syncType: string; status: string; message: string; startedAt: string; finishedAt: string; instanceCount: number; metricCount: number; trafficCount: number; costCount: number }
export interface SyncSchedule { enabled: boolean; cronExpression: string; zoneId: string; syncOnStartup: boolean; nextRunAt: string }
export interface Choice { title: string; description: string }
export interface QuotaSettings {
  ampereOcpuHours: number; ampereMemoryGbHours: number; blockVolumeGb: number; outboundDataTransferGb: number;
  monitoringIngestionPoints: number; monitoringRetrievalPoints: number; updatedAt: string;
}
export interface QuotaItem {
  key: string; name: string; unit: string; limit: number; used: number | null; status: string; scope: string;
  updatedAt: string | null; requestedThrough: string | null;
}
export interface QuotaSummary { month: string; runningA1Ocpus: number | null; runningA1MemoryGb: number | null; items: QuotaItem[] }
export interface VolumeDetails {
  snapshot: { status: string; succeededAt: string | null; attemptedAt: string | null; region: string | null; incomplete: boolean };
  volumes: Array<{id: string; name: string; kind: string; sizeGb: number | null; state: string; attachmentState: string | null}>;
}
export interface OciStatus { configured: boolean; authMode: string; authModeLabel: string; instanceId: string; tenancyId: string; compartmentId: string; region: string; costEnabled: boolean; networkCompartmentId?: string }
export interface OciDiagnostic { summary: string; overallStatus: string; steps: Array<{ name: string; status: string; message: string; suggestion: string; durationMs: number }> }
export interface Budget { month: string; currency: string; amount: string; usedAmount: string; reachedThreshold: number }
export interface MaintenanceWindow { id: string; reason: string; startsAt: string; endsAt: string }
export interface WechatStatus { enabled: boolean; configured: boolean; dailySummaryTime: string; zoneId: string; recipientCount: number; immediatePushEnabled: boolean; dailySummaryEnabled: boolean; detailPageEnabled: boolean; detailPageTokenTtlDays: number; encryptionReady: boolean }

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

type Method = "GET" | "POST" | "PUT" | "DELETE"

export async function request<T>(path: string, method: Method = "GET", body?: unknown, signal?: AbortSignal): Promise<T> {
  if (demoMode) {
    const { demoRequest } = await import("./demo")
    return demoRequest<T>(path, method, body)
  }
  const headers = new Headers({ Accept: "application/json" })
  if (method !== "GET") {
    const csrf = await request<{ token: string }>("/auth/csrf", "GET", undefined, signal)
    headers.set("X-CSRF-TOKEN", csrf.token)
    if (body !== undefined) headers.set("Content-Type", "application/json")
  }
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: "same-origin", signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const payload: { success?: boolean; data?: T; message?: string } = await response.json().catch(() => ({}))
  if (!response.ok || !payload.success) {
    if (response.status === 401 && path !== "/auth/login") window.dispatchEvent(new Event("oci-lens:unauthorized"))
    throw new ApiError(payload.message || "无法读取服务，请稍后重试。", response.status)
  }
  return payload.data as T
}

export function useRead<T>(path: string | null, revision = 0) {
  const [retry, setRetry] = useState(0)
  const reload = useCallback(() => setRetry(value => value + 1), [])
  const [state, setState] = useState<{ path: string | null; data: T | null; error: string; pending: boolean }>({ path, data: null, error: "", pending: Boolean(path) })
  useEffect(() => {
    if (!path) return
    const controller = new AbortController()
    setState(previous => ({ path, data: previous.path === path ? previous.data : null, error: "", pending: true }))
    request<T>(path, "GET", undefined, controller.signal).then(
      data => { if (!controller.signal.aborted) setState({ path, data, error: "", pending: false }) },
      error => { if (!controller.signal.aborted) setState(previous => ({ ...previous, error: error.message, pending: false })) },
    )
    return () => controller.abort()
  }, [path, retry, revision])
  const current = state.path === path ? state : { path, data: null, error: "", pending: Boolean(path) }
  return { ...current, retry: reload }
}

export function query(path: string, values: Record<string, string>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value) params.set(key, value)
  return params.size ? `${path}?${params}` : path
}
