import { useEffect, useRef, useState, type ComponentProps } from "react"
import { cn } from "@/lib/glass-cn"

export function ScrollRegion({ label, className, children, ...props }: ComponentProps<"div"> & { label: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [vertical, setVertical] = useState(false)
  const [horizontal, setHorizontal] = useState(false)
  const overflowing = vertical || horizontal

  useEffect(() => {
    const element = ref.current
    if (!element) return

    const measure = () => {
      setVertical(element.scrollHeight > element.clientHeight + 1)
      setHorizontal(element.scrollWidth > element.clientWidth + 1)
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    if (element.firstElementChild) observer.observe(element.firstElementChild)
    return () => observer.disconnect()
  }, [])

  return <div {...props} ref={ref} className={cn("scroll-region glass-focus", className)}
    data-overflowing={overflowing || undefined} data-overflowing-y={vertical || undefined}
    role={overflowing ? "region" : undefined} tabIndex={overflowing ? 0 : undefined}
    aria-label={overflowing ? label : undefined}>
    {children}
  </div>
}
