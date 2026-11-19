import { useContext, useEffect, useRef } from "react"
import { LineChart } from "echarts/charts"
import { GridComponent, TooltipComponent } from "echarts/components"
import { init, use } from "echarts/core"
import { CanvasRenderer } from "echarts/renderers"
import { ThemeContext } from "./theme"

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

export function SeriesChart({ points, label, unit }: { points: Array<{ label: string; value: number | null }>; label: string; unit: string }) {
  const element = useRef<HTMLDivElement>(null)
  const colorScheme = useContext(ThemeContext)

  useEffect(() => {
    if (!element.current) return
    const theme = getComputedStyle(element.current)
    const accent = theme.getPropertyValue("--primary").trim()
    const muted = theme.getPropertyValue("--muted-foreground").trim()
    const foreground = theme.getPropertyValue("--foreground").trim()
    const surface = theme.getPropertyValue("--card").trim()
    const grid = theme.getPropertyValue("--chart-grid").trim()
    const chart = init(element.current, undefined, { renderer: "canvas" })
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(element.current)
    chart.setOption({
      animation: false,
      textStyle: { fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, system-ui, sans-serif" },
      grid: { left: 40, top: 15, right: 12, bottom: 29 },
      tooltip: {
        trigger: "axis",
        backgroundColor: surface,
        borderColor: grid,
        textStyle: { color: foreground, fontSize: 12 },
        extraCssText: "box-shadow:none",
        valueFormatter: (value: unknown) => typeof value === "number" ? `${Number(value).toFixed(2)} ${unit}` : "未采集",
      },
      xAxis: {
        type: "category", boundaryGap: points.length === 1, data: points.map(point => point.label),
        axisLine: { lineStyle: { color: grid } }, axisTick: { show: false },
        axisLabel: { color: muted, fontSize: 12, margin: 12, hideOverlap: true, showMinLabel: true, showMaxLabel: true },
      },
      yAxis: {
        type: "value",
        axisLabel: { color: muted, fontSize: 12, formatter: "{value}" },
        splitLine: { lineStyle: { color: grid, type: "dashed" } },
      },
      series: [{
        name: label, type: "line", data: points.map(point => point.value), symbol: "circle",
        symbolSize: 7, smooth: 0.24, showSymbol: points.length <= 12,
        lineStyle: { color: accent, width: 2.5 }, itemStyle: { color: accent, borderColor: surface, borderWidth: 2 },
        areaStyle: { color: accent, opacity: 0.08 },
      }],
    })
    return () => { observer.disconnect(); chart.dispose() }
  }, [points, label, unit, colorScheme])

  return <div className="chart" ref={element} role="img" aria-label={`${label}趋势图，单位 ${unit}，${points.length} 个时间点`} />
}
