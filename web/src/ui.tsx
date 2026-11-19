import { useId, type ReactNode } from "react"
import { Link, NavLink } from "react-router-dom"
import { ArrowUpRight } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/glass/alert"
import { Button } from "@/components/glass/button"
import { Card, CardContent } from "@/components/glass/card"

export function Heading({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return <header className="page-heading"><div><h1>{title}</h1>{subtitle && <p>{subtitle}</p>}</div>{actions && <div className="page-actions">{actions}</div>}</header>
}

export function Panel({ title, subtitle, actions, children, className = "" }: { title?: string; subtitle?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return <Card className={`surface glass-liquid ${className}`} elevation="flat"><CardContent>
    {title && <div className="section-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{actions}</div>}
    {children}
  </CardContent></Card>
}

export function State({ error, pending, retry, empty }: { error?: string; pending?: boolean; retry?: () => void; empty?: string }) {
  if (error) return <Alert className="state-note glass-liquid" variant="destructive"><AlertTitle>请求未完成</AlertTitle><AlertDescription>{error}{retry && <Button variant="link" onClick={retry}>重试</Button>}</AlertDescription></Alert>
  return pending || empty ? <p className="state-note" role={pending ? "status" : undefined}>{pending ? "正在读取本地数据…" : empty}</p> : null
}

export function Tabs({ items, label }: { items: Array<{ to: string; label: string }>; label: string }) {
  return <nav className="page-tabs glass-liquid" aria-label={label}>{items.map(item => <NavLink key={item.to} to={item.to}>{item.label}</NavLink>)}</nav>
}

export function SegmentedChoice<T extends string | number>({ label, options, value, onChange }: {
  label: string; options: ReadonlyArray<{ value: T; label: string }>; value: T; onChange: (value: T) => void;
}) {
  const name = useId()
  return <fieldset className="segmented-choice glass-liquid" aria-label={label}>
    {options.map(option => <label className="segment" key={option.value}>
      <input type="radio" name={name} value={option.value} checked={option.value === value} onChange={() => onChange(option.value)} />
      <span>{option.label}</span>
    </label>)}
  </fieldset>
}

export function Badge({ children, ready = false, warning = false }: { children: ReactNode; ready?: boolean; warning?: boolean }) {
  return <span className={`tag ${warning ? "amber" : ready ? "green" : ""}`}>
    {(warning || ready) && <i className="status-dot" aria-hidden="true" />}{children}
  </span>
}

export function Metric({ label, value, unit, note, to }: { label: string; value: string; unit?: string; note?: string; to?: string }) {
  return <Panel className="metric-card">
    <div className="metric-top">{to ? <Link to={to}>{label}<ArrowUpRight size={14} aria-hidden="true" /></Link> : label}</div>
    <div className="metric-value"><strong>{value}</strong>{unit && value !== "—" && <span>{unit}</span>}</div>
    {note && <p className="metric-note">{note}</p>}
  </Panel>
}

export function formatNumber(value?: number | null, decimals = 1) { return value == null || !Number.isFinite(value) ? "—" : value.toLocaleString("zh-CN", { maximumFractionDigits: decimals }) }
export function utcMonth() { return new Date().toISOString().slice(0, 7) }
export function formatTime(value?: string | null) {
  const date = value ? new Date(value) : null
  return date && !Number.isNaN(date.getTime()) ? new Intl.DateTimeFormat("zh-CN", {month:"numeric", day:"numeric", hour:"2-digit", minute:"2-digit"}).format(date) : "—"
}
export function money(amount: string | number, currency: string) { return `${currency} ${formatNumber(Number(amount), 2)}` }
