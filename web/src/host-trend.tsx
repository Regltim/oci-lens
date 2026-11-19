import { useContext, useEffect, useMemo, useRef, useState } from "react"
import { LineChart } from "echarts/charts"
import { GridComponent, TooltipComponent } from "echarts/components"
import { init, use } from "echarts/core"
import { CanvasRenderer } from "echarts/renderers"
import { type HostHistoryPoint } from "./api"
import { ThemeContext } from "./theme"
import { State, formatNumber, formatTime } from "./ui"

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const metrics = [
  {key:"cpuUsagePercent", label:"CPU", unit:"%"},
  {key:"memoryUsagePercent", label:"内存", unit:"%"},
  {key:"diskUsagePercent", label:"根分区", unit:"%"},
  {key:"networkRxBytesPerSecond", label:"网卡入站", unit:"KiB/s"},
  {key:"networkTxBytesPerSecond", label:"网卡出站", unit:"KiB/s"},
] as const

type MetricKey = typeof metrics[number]["key"]

export function HostTrend({points, daily = false}: {points: HostHistoryPoint[]; daily?: boolean}) {
  const element = useRef<HTMLDivElement>(null)
  const colorScheme = useContext(ThemeContext)
  const [hidden, setHidden] = useState<MetricKey[]>([])
  const samples = useMemo(() => points
    .map(point => ({point, time:Date.parse(point.sampledAt)}))
    .filter(sample => Number.isFinite(sample.time))
    .sort((a, b) => a.time - b.time), [points])
  const available = useMemo(() => metrics.filter(metric => samples.some(({point}) => point[metric.key] != null)), [samples])
  const shown = useMemo(() => available.filter(metric => !hidden.includes(metric.key)), [available, hidden])

  useEffect(() => {
    if (!element.current) return
    const target = element.current
    const style = getComputedStyle(target)
    const color = (token: string) => style.getPropertyValue(token).trim()
    const muted = color("--muted-foreground")
    const grid = color("--chart-grid")
    const chart = init(target, undefined, {renderer:"canvas"})
    const resize = new ResizeObserver(() => chart.resize())
    resize.observe(target)
    const hasPercent = shown.some(metric => metric.unit === "%")
    const hasNetwork = shown.some(metric => metric.unit === "KiB/s")
    const gapLimit = (daily ? 36 * 60 : 30) * 60000
    const timestamp = (value: number) => {
      if (!Number.isFinite(value)) return ""
      const iso = new Date(value).toISOString()
      return daily ? iso.slice(0, 10) : formatTime(iso)
    }

    chart.setOption({
      animation:false,
      grid:{left:48, right:hasNetwork ? 57 : 18, top:35, bottom:32},
      tooltip:{
        trigger:"axis", backgroundColor:color("--card"), borderColor:grid,
        textStyle:{color:color("--foreground"), fontSize:12},
        axisPointer:{label:{formatter:(item: {value: number | string}) => timestamp(Number(item.value))}},
      },
      xAxis:{
        type:"time", axisLine:{lineStyle:{color:grid}}, axisTick:{show:false},
        axisLabel:{color:muted, hideOverlap:true, formatter:timestamp},
      },
      yAxis:[
        {type:"value", show:hasPercent, position:"left", name:"使用率 %", nameTextStyle:{color:muted}, min:0, max:100,
          axisLabel:{color:muted}, splitLine:{lineStyle:{color:grid,type:"dashed"}}},
        {type:"value", show:hasNetwork, position:"right", name:"网卡 KiB/s", nameTextStyle:{color:muted}, min:0,
          axisLabel:{color:muted}, splitLine:{show:!hasPercent}},
      ],
      series:shown.map(metric => {
        const accent = color(`--host-series-${metrics.indexOf(metric) + 1}`)
        const data: Array<[number, number | null]> = []
        for (const {point, time} of samples) {
          const previous = data.at(-1)?.[0]
          if (previous != null && time - previous > gapLimit) data.push([(time + previous) / 2,null])
          const value = point[metric.key]
          data.push([time, value == null ? null : metric.unit === "%" ? value : value / 1024])
        }
        return {type:"line", name:metric.label, yAxisIndex:metric.unit === "%" ? 0 : 1, data,
          connectNulls:false, showSymbol:samples.length <= 14, symbolSize:5,
          lineStyle:{width:2, color:accent}, itemStyle:{color:accent},
          tooltip:{valueFormatter:(value:unknown) => {
            const amount = Array.isArray(value) ? value.at(-1) : value
            return typeof amount === "number" ? `${formatNumber(amount, 2)} ${metric.unit}` : "未采集"
          }}}
      }),
    })
    return () => {resize.disconnect(); chart.dispose()}
  }, [shown, samples, daily, colorScheme])

  if (!available.length) return <State empty="所选时段暂无本机采样。" />
  return <>
    <div className="host-legend" aria-label="趋势指标">
      {metrics.map((metric, index) => {
        const hasData = available.includes(metric)
        const visible = hasData && !hidden.includes(metric.key)
        return <button type="button" className={`host-legend-item host-legend-${index + 1}`}
          key={metric.key} disabled={!hasData} aria-pressed={visible}
          onClick={() => setHidden(items => visible ? [...items, metric.key] : items.filter(key => key !== metric.key))}>
          <span aria-hidden="true" />{metric.label}{!hasData && <small>未采集</small>}
        </button>
      })}
    </div>
    {shown.length
      ? <div className="chart host-chart" ref={element} role="img" aria-label={`本机资源趋势，${shown.map(metric => metric.label).join("、")}`} />
      : <State empty="选择要查看的指标。" />}
  </>
}
