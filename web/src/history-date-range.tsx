export interface DateRange { from: string; to: string }

export function latestRange(lastDay?: string | null, firstDay?: string | null): DateRange {
  const to = lastDay || new Date().toISOString().slice(0, 10)
  const date = new Date(to + "T00:00:00Z")
  date.setUTCDate(date.getUTCDate() - 6)
  const from = date.toISOString().slice(0, 10)
  return {from: firstDay && firstDay > from ? firstDay : from, to}
}

export function dailyAverage(range: DateRange) {
  return Date.parse(range.to) - Date.parse(range.from) >= 7 * 86400000
}

export function DateFilters({range, onChange}: {range: DateRange; onChange: (range: DateRange) => void}) {
  return <>
    <label>开始日期（UTC）<input type="date" value={range.from} max={range.to || undefined} onChange={event => onChange({...range, from:event.target.value})} /></label>
    <label>结束日期（UTC）<input type="date" value={range.to} min={range.from || undefined} max={new Date().toISOString().slice(0, 10)} onChange={event => onChange({...range, to:event.target.value})} /></label>
  </>
}
