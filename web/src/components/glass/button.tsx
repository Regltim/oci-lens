import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/glass-cn"
import * as Slot from "@radix-ui/react-slot"

import { Spinner as ActivitySpinner } from "@/components/glass/spinner"

/**
 * Glasscn supplies the material and interaction. The application theme supplies
 * the colors for action priority and state.
 */
const liquid = "glass-liquid glass-illuminate glass-press-liquid"
const prominent = "glass-prominent glass-press-liquid font-semibold"
const buttonVariants = cva(
  "group/button relative inline-flex shrink-0 items-center justify-center font-medium whitespace-nowrap transition-[scale,background-color,box-shadow,opacity,color,filter] duration-[calc(var(--glass-duration)*1.5)] ease-glass glass-focus select-none aria-busy:pointer-events-none disabled:pointer-events-none aria-disabled:pointer-events-none aria-invalid:ring-(length:--glass-ring-width) aria-invalid:ring-destructive/30 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: prominent,
        prominent,
        glass: liquid,
        tinted: liquid,
        secondary: liquid,
        outline: liquid,
        ghost: liquid,
        destructive: liquid,
        link: liquid,
      },
      size: {
        default: "h-control gap-2 px-pad text-sm has-data-[icon=inline-end]:pe-4 has-data-[icon=inline-start]:ps-4",
        xs: "h-control-xs gap-1 px-pad-xs hit-area text-xs [&_svg:not([class*='size-'])]:size-3",
        sm: "h-control-sm gap-1.5 px-pad-sm hit-area text-[0.8rem] [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-control-lg gap-2 px-pad-lg text-base [&_svg:not([class*='size-'])]:size-5",
        icon: "size-control",
        "icon-xs": "size-control-xs hit-area [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-control-sm hit-area [&_svg:not([class*='size-'])]:size-3.5",
        "icon-lg": "size-control-lg [&_svg:not([class*='size-'])]:size-5",
      },
      shape: {
        pill: "rounded-button",
        rounded: "rounded-control",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
      shape: "pill",
    },
  }
)

/** The activity indicator a loading button shows; the button's aria-busy announces it. */
const Spinner = (props: React.ComponentProps<"svg">) => <ActivitySpinner data-slot="button-spinner" aria-hidden className="motion-reduce:animate-spin" {...props} />

/**
 * While `loading` the button is busy (`aria-busy`, clicks ignored) and keeps its width: a
 * leading icon is swapped for the spinner, otherwise the spinner sits over the hidden label.
 * `loadingText` replaces the label instead (the width may then change).
 */
function withLoading(children: React.ReactNode, size: string | null | undefined, loadingText: React.ReactNode) {
  if (loadingText !== undefined)
    return (
      <>
        <Spinner data-icon="inline-start" />
        {loadingText}
      </>
    )
  const items = React.Children.toArray(children)
  const [first, ...rest] = items
  if (!size?.startsWith("icon") && React.isValidElement(first) && items.length > 1)
    return (
      <>
        <Spinner data-icon="inline-start" />
        {rest}
      </>
    )
  return (
    <>
      <span className="inline-flex items-center gap-[inherit] opacity-0">{children}</span>
      <span className="absolute inset-0 flex items-center justify-center">
        <Spinner />
      </span>
    </>
  )
}

function Button({
  className,
  variant = "default",
  size = "default",
  shape = "pill",
  asChild = false,
  loading = false,
  loadingText,
  children,
  onClick,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
    /** Shows a spinner and marks the button busy; clicks are ignored. */
    loading?: boolean
    /** A label to show while loading (e.g. "Saving…"). */
    loadingText?: React.ReactNode
  }) {
  const Comp = asChild ? Slot.Root : "button"
  const busy = loading && !asChild
  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      aria-busy={busy || undefined}
      className={cn(buttonVariants({ variant, size, shape, className }))}
      onClick={busy ? (e: React.MouseEvent<HTMLButtonElement>) => e.preventDefault() : onClick}
      {...props}
    >
      {busy ? withLoading(children, size, loadingText) : children}
    </Comp>
  )
}

/** A button that opens a menu: the label and a trailing chevron. Wrap it in DropdownMenuTrigger asChild. */
function PullDownButton({ children, ...props }: React.ComponentProps<typeof Button>) {
  return (
    <Button {...props}>
      {children}
      <svg data-icon="inline-end" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.25" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="opacity-70">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </Button>
  )
}

/**
 * A primary action joined to a menu of alternatives: two buttons that read as one (shared outer
 * corners, a hairline between). Children: the action Button, then a PullDownButton (or icon Button).
 */
function SplitButton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      role="group"
      data-slot="split-button"
      className={cn(
        "inline-flex w-fit items-stretch *:data-[slot=button]:focus-visible:z-10 [&>[data-slot=button]:not(:first-child)]:rounded-s-none [&>[data-slot=button]:not(:first-child)]:border-s [&>[data-slot=button]:not(:first-child)]:border-separator [&>[data-slot=button]:not(:first-child)]:ps-2.5 [&>[data-slot=button]:not(:last-child)]:rounded-e-none",
        className
      )}
      {...props}
    />
  )
}

export { Button, buttonVariants, PullDownButton, SplitButton }
