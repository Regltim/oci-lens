const key = "oci-lens.monitor.default-view"

export type MonitorView = "host" | "instances"

export function getMonitorView(): MonitorView {
  return localStorage.getItem(key) === "instances" ? "instances" : "host"
}

export function setMonitorView(view: MonitorView) {
  localStorage.setItem(key, view)
}
