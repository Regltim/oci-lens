import { createContext } from "react"

export type Theme = "dark" | "light"
const storageKey = "oci-lens-theme"

export const ThemeContext = createContext<Theme>("dark")

export function readTheme(): Theme {
  try {
    return localStorage.getItem(storageKey) === "light" ? "light" : "dark"
  } catch {
    return "dark"
  }
}

export function rememberTheme(theme: Theme) {
  try {
    localStorage.setItem(storageKey, theme)
  } catch {
    // The current page can still change theme when storage is unavailable.
  }
}
