import type { ComponentProps } from "react"
import { LoaderCircle } from "lucide-react"

// Local loading icon for the registry Button's Spinner slot.
export function Spinner({ className, ...props }: ComponentProps<"svg">) {
  return <LoaderCircle {...props} className={["animate-spin", className].filter(Boolean).join(" ")} />
}
