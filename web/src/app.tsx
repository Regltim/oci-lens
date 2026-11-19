import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react"
import { BrowserRouter, HashRouter, Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom"
import { ArrowRight, Eye, EyeOff, LockKeyhole, LogOut, Menu, Moon, Sun, UserRound, X } from "lucide-react"
import { Button } from "@/components/glass/button"
import { GlassEffects } from "@/components/glass/glass-effects"
import { ApiError, demoMode, request, type AuthSession } from "./api"
import { Overview, Runs } from "./overview"
import { Monitor } from "./monitor"
import { Usage } from "./usage"
import { Settings } from "./settings"
import { Badge, Heading, Panel, State } from "./ui"
import { readTheme, rememberTheme, ThemeContext, type Theme } from "./theme"
import lightWorkspace from "./assets/workspace-day.jpg"
import darkWorkspace from "./assets/workspace-night.jpg"
import lightLogin from "./assets/login-day.jpg"
import darkLogin from "./assets/login-lens.jpg"

const links = [{ to: "/", text: "概览" }, { to: "/monitor", text: "监控" }, { to: "/usage", text: "用量与费用" }, { to: "/settings", text: "设置" }]
const artworkByTheme = {
  light: {login: lightLogin, workspace: lightWorkspace},
  dark: {login: darkLogin, workspace: darkWorkspace},
}

function BrandName() {
  return <><span className="brand-mark" aria-hidden="true"><span /></span><span>OCI Lens</span></>
}

function returnPath(destination: unknown) {
  return typeof destination === "string" && /^\/(?![/\\])/.test(destination) ? destination : "/"
}

function ThemeToggle({theme, onToggle}: {theme: Theme; onToggle: () => void}) {
  const action = theme === "dark" ? "切换浅色主题" : "切换深色主题"
  return <Button type="button" variant="ghost" size="icon" aria-label={action} title={action} onClick={onToggle}>
    {theme === "dark" ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
  </Button>
}

function Header({theme, onToggleTheme, onLogout, logoutPending}: {
  theme: Theme; onToggleTheme: () => void; onLogout: () => void; logoutPending: boolean
}) {
  const [expanded, setExpanded] = useState(false)
  const {pathname} = useLocation()
  useEffect(() => {setExpanded(false); window.scrollTo(0, 0)}, [pathname])

  return <header className="site-header" onKeyDown={event => {
    if (event.key === "Escape" && expanded) {
      setExpanded(false)
      document.getElementById("menu-button")?.focus()
    }
  }}>
    <div className="site-header-inner glass-liquid">
      <Link to="/" className="site-brand" aria-label="OCI Lens 概览"><BrandName /></Link>
      <nav id="site-menu" aria-label="主导航" className={`site-nav${expanded ? " is-expanded" : ""}`}>
        {links.map(link => <NavLink to={link.to} end={link.to === "/"} key={link.to}>{link.text}</NavLink>)}
      </nav>
      <div className="site-tools">
        <ThemeToggle theme={theme} onToggle={onToggleTheme} />
        <Button type="button" variant="ghost" className="top-logout" aria-label={logoutPending ? "正在退出…" : "退出登录"} title="退出登录" disabled={logoutPending} onClick={onLogout}>
          <LogOut aria-hidden="true" /><span>{logoutPending ? "正在退出…" : "退出登录"}</span>
        </Button>
        <Button id="menu-button" type="button" variant="ghost" size="icon" className="menu-button" aria-expanded={expanded} aria-controls="site-menu"
          aria-label={expanded ? "关闭导航" : "打开导航"} onClick={() => setExpanded(value => !value)}>{expanded ? <X /> : <Menu />}</Button>
      </div>
    </div>
  </header>
}

function Login({theme, onToggleTheme, onLogin}: {
  theme: Theme; onToggleTheme: () => void; onLogin: (session: AuthSession) => void
}) {
  const [error, setError] = useState("")
  const [pending, setPending] = useState(false)
  const [passwordVisible, setPasswordVisible] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const form = new FormData(event.currentTarget)
    setPending(true)
    setError("")
    try {
      const session = await request<AuthSession>("/auth/login", "POST", {username: form.get("username"), password: form.get("password")})
      onLogin(session)
    } catch (failure) {setError((failure as Error).message)} finally {setPending(false)}
  }
  return <main className="login-page">
    <div className="login-shell">
      <Panel className="login-panel">
        <header className="login-heading">
          <div className="login-brand-row">
            <div className="site-brand login-brand"><BrandName /></div>
            <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          </div>
          <h1>登录监控面板</h1>
          <p>查看本机状态、OCI 用量和同步记录。</p>
        </header>
        <form className="login-form" onSubmit={submit} onChange={() => { if (error) setError("") }}>
          <div className="login-field">
            <label htmlFor="login-username">账号</label>
            <div className="login-field-control">
              <UserRound size={18} aria-hidden="true" />
              <input id="login-username" name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} required />
            </div>
          </div>
          <div className="login-field">
            <label htmlFor="login-password">密码</label>
            <div className="login-field-control">
              <LockKeyhole size={18} aria-hidden="true" />
              <input id="login-password" name="password" type={passwordVisible ? "text" : "password"} autoComplete="current-password" autoCapitalize="none" spellCheck={false} required />
              <Button type="button" variant="ghost" size="icon" className="login-toggle" aria-label="显示密码" aria-pressed={passwordVisible} aria-controls="login-password" onClick={() => setPasswordVisible(value => !value)}>
                {passwordVisible ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              </Button>
            </div>
          </div>
          {error && <p className="login-error" role="alert">{error}</p>}
          <Button variant="prominent" size="lg" className="login-submit" type="submit" disabled={pending}>
            {pending ? "正在登录…" : "登录"}<ArrowRight data-icon="inline-end" aria-hidden="true" />
          </Button>
        </form>
      </Panel>
    </div>
  </main>
}

function Shell() {
  const [session, setSession] = useState<AuthSession | null | undefined>(demoMode ? {username:"admin"} : undefined)
  const [theme, setTheme] = useState(readTheme)
  const [error, setError] = useState("")
  const [revision, setRevision] = useState(0)
  const [logoutPending, setLogoutPending] = useState(false)
  const [logoutError, setLogoutError] = useState("")
  const sessionCheck = useRef<AbortController | null>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const [reportPath, reportParameters] = location.pathname === "/" && location.hash.startsWith("#/r/")
    ? location.hash.slice(1).split("?", 2)
    : [location.pathname, location.hash.slice(1)]
  const publicReport = reportPath.startsWith("/r/")
  useEffect(() => {
    if (publicReport || demoMode) return
    const controller = new AbortController()
    sessionCheck.current = controller
    request<AuthSession>("/auth/me", "GET", undefined, controller.signal).then(
      value => {if (!controller.signal.aborted) {setSession(value); setError("")}},
      failure => {
        if (controller.signal.aborted) return
        if (failure instanceof ApiError && failure.status === 401) setSession(null)
        else setError(failure.message)
      },
    )
    const expired = () => {controller.abort(); setSession(null)}
    window.addEventListener("oci-lens:unauthorized", expired)
    return () => {controller.abort(); window.removeEventListener("oci-lens:unauthorized", expired)}
  }, [revision, publicReport])

  function toggleTheme() {
    const next = theme === "dark" ? "light" : "dark"
    rememberTheme(next)
    setTheme(next)
  }

  async function logout() {
    if (logoutPending) return
    setLogoutPending(true)
    setLogoutError("")
    try {
      await request("/auth/logout", "POST")
      finishLogout()
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) finishLogout()
      else setLogoutError((failure as Error).message)
    } finally {
      setLogoutPending(false)
    }
  }

  function finishLogout() {
    sessionCheck.current?.abort()
    setSession(null)
    navigate("/login", {replace:true, state:null})
  }

  if (publicReport) return <Canvas theme={theme}><Settings key={location.key} report={{id:reportPath.slice(3), token:new URLSearchParams(reportParameters).get("token")}} /></Canvas>
  if (session === undefined) return <Canvas theme={theme} login={location.pathname === "/login"}><main className="workspace-content"><State pending={!error} error={error} retry={() => {setError(""); setRevision(value => value + 1)}} /></main></Canvas>
  if (!session) return location.pathname === "/login" ? <Canvas theme={theme} login><Login theme={theme} onToggleTheme={toggleTheme} onLogin={setSession} /></Canvas> : <Navigate to="/login" replace state={{returnTo: location.pathname + location.search}} />
  if (location.pathname === "/login") return <Navigate to={returnPath(location.state?.returnTo)} replace />

  return <Canvas theme={theme}><div className="app-shell"><Header theme={theme} onToggleTheme={toggleTheme} onLogout={logout} logoutPending={logoutPending} />
    <main className="workspace-content" id="main">
      {demoMode && <div className="demo-mark"><Badge>示例数据</Badge></div>}
      {logoutError && <State error={logoutError} retry={logout} />}
      <Routes>
        <Route path="/" element={<Overview />} />
        <Route path="/monitor/*" element={<Monitor />} />
        <Route path="/usage/*" element={<Usage />} />
        <Route path="/settings/*" element={<Settings session={session} onAccountUpdated={setSession} onLogout={logout} logoutPending={logoutPending} />} />
        <Route path="/sync" element={<Runs />} />
        <Route path="*" element={<><Heading title="页面不存在" /><Link to="/">返回概览</Link></>} />
      </Routes>
    </main>
  </div></Canvas>
}

function Canvas({theme, login = false, children}: {theme: Theme; login?: boolean; children: ReactNode}) {
  const scene = login ? "login" : "workspace"
  const artwork = artworkByTheme[theme][scene]
  useEffect(() => {
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "dark" ? "#080f20" : "#e8edf5")
  }, [theme])
  return <ThemeContext.Provider value={theme}>
    <div className={`app-canvas${theme === "dark" ? " dark" : ""}`} data-theme={theme} data-scene={scene}>
      <div className="site-backdrop" aria-hidden="true"><img key={artwork} src={artwork} alt="" fetchPriority="high" /></div>
      {children}
    </div>
  </ThemeContext.Provider>
}

export function App() {
  const Router = demoMode ? HashRouter : BrowserRouter
  return <>
    <GlassEffects />
    <Router><Shell /></Router>
  </>
}
