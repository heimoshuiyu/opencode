import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs"
import { readdir, readFile } from "node:fs/promises"
import { join, resolve, relative } from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import { app, BrowserWindow, ipcMain, Menu, Tray, nativeImage, protocol, screen } from "electron"
import Store from "electron-store"

const __dirname = join(dirname(fileURLToPath(import.meta.url)), "..")
const skinsDir = resolve(__dirname, "../skins")

// ── Persistent Config (electron-store) ──────────────────────────

type ServerConfig = {
  url: string
  username: string
  password: string
}

const store = new Store<{
  server: ServerConfig
}>({
  defaults: {
    server: {
      url: "",
      username: "opencode",
      password: "",
    },
  },
})

// Read the v2 background-service registration file (0600, written by
// `opencode serve --service`) so the pet auto-connects to the elected
// server without manual setup. Same contract as
// packages/client/src/promise/service.ts.
function detectServiceConfig(): { url: string; password: string } | null {
  try {
    const stateHome = process.env.XDG_STATE_HOME ?? join(app.getPath("home"), ".local", "state")
    const registration = JSON.parse(readFileSync(join(stateHome, "opencode", "service.json"), "utf-8"))
    if (typeof registration.url === "string" && typeof registration.password === "string") {
      return { url: registration.url, password: registration.password }
    }
  } catch {
    // No registered service — fall back to the default service port
  }
  return null
}

// v2 auth is HTTP Basic with a fixed username; the password is the service
// credential (empty means the server has auth disabled). An empty stored url
// means "auto": prefer the registered service, else the default port.
function getServerConfig(): ServerConfig {
  const stored = store.get("server")
  if (stored.url) return { ...stored, username: "opencode" }
  const detected = detectServiceConfig()
  return {
    url: (detected?.url ?? "http://127.0.0.1:49374").replace("//0.0.0.0", "//127.0.0.1"),
    username: "opencode",
    password: detected?.password ?? stored.password,
  }
}

function setServerConfig(config: ServerConfig) {
  store.set("server", { ...config, username: "opencode" })
}

// ── Pet Observer (AI Comment Bubble) ───────────────────────────
//
// Uses a dedicated OpenCode workspace to generate short pet comments in
// real-time as the user works. The model is not pinned here: observer
// sessions run on the location default model and users can switch it
// per-session (POST /api/session/:id/model or the TUI model picker).
//
// Architecture:
//   1. SSE detects user messages in the main workspace (session.inbox.enqueued)
//      → inject as SYSTEM-REMINDER into a persistent pet observer session via
//      POST /api/session/:id/prompt
//   2. SSE detects assistant messages completing (session idle) → inject final response
//   3. The pet-commenter agent responds with plain text — its reply is captured
//      from the SSE session.text.ended events and shown as a speech bubble
//
// The observer session is persistent — it accumulates context over the entire
// conversation, giving the pet awareness of what happened before.

let currentSkin: string | null = null

const PET_WORKSPACE_BASE = join(app.getPath("home"), ".config", "pet")

// Per-skin workspace: each skin gets its own directory so the agent
// persona file doesn't need hot-reloading (OpenCode caches it on first read).
function petWorkspaceDir() {
  const skin = currentSkin ?? detectDefaultSkin()
  return join(PET_WORKSPACE_BASE, `workspace-${skin}`)
}

let petSessionID: string | null = null
let petCommentTimer: ReturnType<typeof setTimeout> | null = null
const MAX_BUBBLE_LENGTH = 50 // chars — if pet reply exceeds this, ask it to shorten

// ── Simple Position Persistence ──────────────────────────────────

const positionFile = join(app.getPath("userData"), "pet-position.json")

function loadPosition() {
  try {
    return JSON.parse(readFileSync(positionFile, "utf-8")) as { x: number; y: number }
  } catch {
    return null
  }
}

function savePosition(x: number, y: number) {
  try {
    writeFileSync(positionFile, JSON.stringify({ x, y }))
  } catch {
    // Ignore write errors
  }
}

// ── Pet State (v4: Activity + Reaction) ──────────────────────────

type PetState =
  // Activity — persistent, loop animations
  | "idle"
  | "thinking"
  | "generating"
  | "acting"
  | "waiting-user"
  | "retrying"
  | "sleeping"
  | "disconnected"
  // Reaction — transient, auto-revert to current activity
  | "greeting"
  | "chatting"
  | "file-written"
  | "completed"
  | "upgraded"
  | "error"
  | "pty-exited"
  | "compacted"
  | "env-changed"

const ACTIVITY_STATES: Set<PetState> = new Set([
  "idle", "thinking", "generating", "acting", "waiting-user", "retrying", "sleeping", "disconnected",
])

let currentPetState: PetState = "idle"
let activityState: PetState = "idle"         // current persistent Activity state
let connectionStatus: "connected" | "disconnected" | "connecting" = "disconnected"
let mainWindow: BrowserWindow | null = null
let tray: Tray | null = null
let eventSource: ReturnType<typeof connectSSE> | null = null
let reactionTimer: ReturnType<typeof setTimeout> | null = null
let serverUrl = ""
let serverCredentials = ""

// ── Sub-agent session tracking ──────────────────────────────────
// Track child (sub-agent) sessions so we can filter out their events.
// Without this, a sub-agent completing would send an "idle" event that
// incorrectly transitions the pet to idle while the main agent is still working.
const childSessionIDs = new Set<string>()
const activeSessions = new Set<string>()
const assistantTextBySession = new Map<string, string>()
const sessionTitles = new Map<string, string>()
// The pet's own reply may span several text segments per step — show only the first as the bubble.
let petBubbleShown = false

// ── App Setup ──────────────────────────────────────────────────

// Use ANGLE for rendering — prefer hardware GPU, fall back to SwiftShader only when needed.
// Previously forcing --use-angle=swiftshader caused the ENTIRE Chromium rendering pipeline
// (not just WebGL, but also HTML/CSS compositing) to go through CPU software rendering,
// resulting in a visibly dimmer/darker window (gamma curve + color range differences).
app.commandLine.appendSwitch("use-gl", "angle")
app.commandLine.appendSwitch("enable-unsafe-swiftshader")
app.commandLine.appendSwitch("ignore-gpu-blocklist")

// Register custom protocol for serving skin files
protocol.registerSchemesAsPrivileged([
  {
    scheme: "pet",
    privileges: { secure: true, standard: true, supportFetchAPI: true, stream: true, corsEnabled: true },
  },
])

app.whenReady().then(() => {
  registerSkinProtocol()
  registerIpcHandlers()
  createTray()
  createPetWindow()
  initAllSkinWorkspaces()
  // Auto-connect to OpenCode server
  connectToOpenCode()
})

app.on("window-all-closed", () => { /* prevent default quit — pet stays running */ })

app.on("before-quit", () => {
  eventSource?.close()
})

// ── Skin File Protocol ─────────────────────────────────────────

function registerSkinProtocol() {
  protocol.handle("pet", async (request) => {
    const url = new URL(request.url)
    // pet://miku/model.json → host=miku, pathname=/model.json
    // Need to include hostname in path resolution
    const host = url.hostname
    const pathname = decodeURIComponent(url.pathname)
    const filePath = resolve(skinsDir, host ? `${host}${pathname}` : pathname.slice(1))
    console.log(`[pet] pet:// request: ${request.url} → ${filePath}`)

    // Security: ensure path is within skinsDir
    const rel = relative(skinsDir, filePath)
    if (rel.startsWith("..") || rel.startsWith("/")) {
      return new Response("Forbidden", { status: 403 })
    }

    if (!existsSync(filePath)) {
      return new Response("Not found", { status: 404 })
    }

    const data = await readFile(filePath)
    const ext = filePath.split(".").pop()?.toLowerCase()

    const mimeTypes: Record<string, string> = {
      json: "application/json",
      png: "image/png",
      jpg: "image/jpeg",
      moc3: "application/octet-stream",
      physic3: "application/json",
      exp3: "application/json",
      motion3: "application/json",
      pose3: "application/json",
      cdi3: "application/json",
      userdata3: "application/json",
    }

    return new Response(data, {
      headers: {
        "Content-Type": mimeTypes[ext ?? ""] ?? "application/octet-stream",
        // Dev serves the renderer from the vite origin (http://localhost:5173);
        // newer Electron enforces CORS on cross-origin fetches to custom
        // schemes, so allow any origin (packaged file:// origins included).
        "Access-Control-Allow-Origin": "*",
      },
    })
  })
}

// ── Transparent Pet Window ─────────────────────────────────────

function createPetWindow() {
  const saved = loadPosition()

  mainWindow = new BrowserWindow({
    x: saved?.x,
    y: saved?.y,
    width: 200,
    height: 200,
    transparent: true,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: true, // Must be true — Wayland/GTK refuses to shrink windows when false
    hasShadow: false,
    webPreferences: {
      preload: join(__dirname, "preload/index.js"),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false, // Allow loading local files for Live2D
    },
  })

  // Save position whenever the window moves
  mainWindow.on("move", () => {
    if (!mainWindow) return
    const [x, y] = mainWindow.getPosition()
    savePosition(x, y)
  })

  // Suppress error dialogs in renderer — SSE disconnects and network errors
  // are expected and handled gracefully, no need to spam the user with popups.
  mainWindow.webContents.on("render-process-gone", (_event, details) => {
    console.error("[pet] renderer process gone:", details.reason, details.exitCode)
  })

  // Forward renderer console logs to main process for debugging
  mainWindow.webContents.on("console-message", (_event, level, message) => {
    const prefix = level === 2 ? "[renderer WARN]" : level === 3 ? "[renderer ERROR]" : "[renderer]"
    console.log(`${prefix} ${message}`)
  })

  const devUrl = process.env.ELECTRON_RENDERER_URL
  if (devUrl) {
    mainWindow.loadURL(devUrl)
  } else {
    mainWindow.loadFile(join(__dirname, "renderer/index.html"))
  }
}

// ── System Tray ────────────────────────────────────────────────

function createTray() {
  const icon = nativeImage.createFromDataURL(
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABGdBTUEAALGPC/xhBQAAAAlwSFlzAAAOwQAADsEBuJFr7QAAABl0RVh0U29mdHdhcmUAcGFpbnQubmV0IDQuMC4xNkRpr/UAAAB5SURBVDhPxZDRDYAgDERxBEdxFEdxFEdxFEdwBL2GEmhiNP7wkl5K7y4NAPwM0sQkYATaK8WYYgYLSJB3oMA2JCCUQAb7QW8RqAkV0ASaQBN4At3gG8glpMc3sAMU0AWaQAtI4g10gx5yg5rwDm8BefABNIAm0+AV+gaHkALWIGmsAAAAAElFTkSuQmCC",
  )

  tray = new Tray(icon)
  tray.setToolTip("OpenCode Pet")

  const contextMenu = Menu.buildFromTemplate([
    { label: "OpenCode Pet", type: "normal", enabled: false },
    { type: "separator" },
    { label: "Show Pet", click: () => mainWindow?.show() },
    {
      label: "Move to Center",
      click: () => {
        const { width, height } = screen.getPrimaryDisplay().workAreaSize
        mainWindow?.setPosition(Math.floor(width / 2 - 100), Math.floor(height / 2 - 100))
      },
    },
    { type: "separator" },
    { label: "Quit", click: () => app.quit() },
  ])

  tray.setContextMenu(contextMenu)

  tray.on("click", () => {
    if (mainWindow?.isVisible()) mainWindow.hide()
    else mainWindow?.show()
  })
}

// ── Pet State Management ───────────────────────────────────────

function setPetState(state: PetState) {
  currentPetState = state
  // Track Activity states separately so Reactions can revert to them
  if (ACTIVITY_STATES.has(state)) activityState = state
  mainWindow?.webContents.send("pet-state", state)
}

function setReaction(reaction: PetState, durationMs: number) {
  // Clear any previous reaction timer
  if (reactionTimer) { clearTimeout(reactionTimer); reactionTimer = null }
  setPetState(reaction)
  // After duration, revert to current activity state
  reactionTimer = setTimeout(() => {
    reactionTimer = null
    setPetState(activityState)
  }, durationMs)
}

// ── OpenCode SSE Connection ────────────────────────────────────

function connectToOpenCode() {
  const config = getServerConfig()
  serverUrl = config.url
  serverCredentials = btoa(`${config.username}:${config.password}`)

  console.log(`[pet] connecting to OpenCode at ${serverUrl}`)
  eventSource = connectSSE(serverUrl, config.username, config.password)
}

function connectSSE(serverUrl: string, username: string, password: string) {
  connectionStatus = "connecting"
  updateTrayTooltip()

  const url = new URL("/api/event", serverUrl)

  // Build Basic Auth header
  const credentials = btoa(`${username}:${password}`)
  const headers = { Authorization: `Basic ${credentials}` }

  // Node.js EventSource doesn't support headers natively,
  // so we use fetch-based SSE reading
  let aborted = false
  const controller = new AbortController()

  const INITIAL_RETRY_MS = 1000
  const MAX_RETRY_MS = 30_000
  let retryMs = INITIAL_RETRY_MS

  const connect = async () => {
    while (!aborted) {
      try {
        const response = await fetch(url.toString(), {
          headers,
          signal: controller.signal,
        })

        if (!response.ok) {
          console.warn(`[pet] SSE connection failed: ${response.status}`)
          connectionStatus = "disconnected"
          updateTrayTooltip()
          setPetState("disconnected")
          await delay(retryMs)
          retryMs = Math.min(retryMs * 2, MAX_RETRY_MS)
          continue
        }

        // Connected — reset retry backoff
        retryMs = INITIAL_RETRY_MS
        connectionStatus = "connected"
        updateTrayTooltip()
        console.log("[pet] connected to OpenCode SSE")
        setReaction("greeting", 2000)

        // Evict pet workspace locations so the server re-reads
        // updated agent configs (persona, opencode.json) from disk.
        disposePetInstances()

        const reader = response.body?.getReader()
        if (!reader) continue

        const decoder = new TextDecoder()
        let buffer = ""

        while (!aborted) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split("\n")
          buffer = lines.pop() ?? ""

          for (const line of lines) {
            // v2 sends keep-alives as SSE comment lines (`: heartbeat`) —
            // receiving them at all proves the stream is alive, nothing to do.
            if (line.startsWith(":")) continue
            if (line.startsWith("data: ")) {
              try {
                const event = JSON.parse(line.slice(6))
                // v2 event envelope is flat: { id, created, type, data, location? }
                handleOpenCodeEvent({
                  type: event.type,
                  data: event.data ?? {},
                  directory: event.location?.directory,
                })
              } catch {
                // Ignore malformed JSON
              }
            }
          }
        }

        // Stream ended normally (server closed connection) — reconnect quickly
        console.log("[pet] SSE stream ended, reconnecting...")
      } catch (e: any) {
        if (e.name === "AbortError") break
        console.warn(`[pet] SSE error, retrying in ${Math.round(retryMs / 1000)}s:`, e.message)
      }

      connectionStatus = "disconnected"
      updateTrayTooltip()
      setPetState("disconnected")
      if (!aborted) await delay(retryMs)
      retryMs = Math.min(retryMs * 2, MAX_RETRY_MS)
    }
  }

  connect()

  return {
    close() {
      aborted = true
      controller.abort()
      if (reactionTimer) { clearTimeout(reactionTimer); reactionTimer = null }
      connectionStatus = "disconnected"
      updateTrayTooltip()
      setPetState("disconnected")
    },
  }
}

function isChildSession(sessionID: string | undefined): boolean {
  return !!sessionID && childSessionIDs.has(sessionID)
}

// Ensure the pet observer session exists (reuse existing or create new). Returns true on success.
async function ensurePetSession(): Promise<boolean> {
  if (petSessionID) return true
  if (!serverUrl) return false

  const workspaceDir = petWorkspaceDir()
  const headers = {
    Authorization: `Basic ${serverCredentials}`,
    "Content-Type": "application/json",
  }

  // Try to reuse an existing session in this workspace
  const listUrl = `${serverUrl}/api/session?directory=${encodeURIComponent(workspaceDir)}&limit=1`
  const listRes = await fetch(listUrl, { headers })
  if (listRes.ok) {
    const body = await listRes.json()
    if (body.data?.length > 0) {
      petSessionID = body.data[0].id
      console.log(`[pet-observer] reused existing session: ${petSessionID}`)
      return true
    }
  }

  // No existing session found — create a new one bound to the pet-commenter agent
  console.log(`[pet-observer] creating session in ${workspaceDir}`)
  const createRes = await fetch(`${serverUrl}/api/session`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      agent: "pet-commenter",
      location: { directory: workspaceDir },
    }),
  })
  if (!createRes.ok) {
    const body = await createRes.text().catch(() => "")
    console.warn(`[pet-observer] create session failed: ${createRes.status} ${body}`)
    return false
  }
  const body = await createRes.json()
  petSessionID = body.data.id
  console.log(`[pet-observer] session created: ${petSessionID}`)
  return true
}

// Inject a user message into the pet observer session as a SYSTEM-REMINDER.
// The pet-commenter agent processes it and its text reply is captured via SSE.
// v2 queues durably server-side: prompts are always admitted, delivered in
// enqueue order (steer at safe step boundaries while running, immediately
// when idle) — no client-side busy gate or cooldown is needed.
function injectUserMessage(text: string) {
  if (!serverUrl) {
    console.log(`[pet-observer] inject skipped: no serverUrl`)
    return
  }

  ;(async () => {
    try {
      if (!(await ensurePetSession())) return

      const headers = {
        Authorization: `Basic ${serverCredentials}`,
        "Content-Type": "application/json",
      }
      const promptUrl = `${serverUrl}/api/session/${petSessionID}/prompt`
      const promptRes = await fetch(promptUrl, {
        method: "POST",
        headers,
        body: JSON.stringify({ text }),
      })

      if (!promptRes.ok) {
        const body = await promptRes.text().catch(() => "")
        console.warn(`[pet-observer] prompt failed: ${promptRes.status} ${body}`)
        petSessionID = null
        return
      }
      console.log(`[pet-observer] prompt accepted, waiting for response...`)
    } catch (e: any) {
      console.error("[pet-observer] inject error:", e.message)
      petSessionID = null
    }
  })()
}

// Build the SYSTEM-REMINDER injected into the pet observer when the user
// sends a message in a main workspace.
function userMessageReminder(userText: string, sessionID: string, directory: string | undefined) {
  const timestamp = new Date().toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })
  return [
    "<SYSTEM-REMINDER>",
    "The user sent a message in the workspace.",
    `- Working Directory: ${directory ?? "unknown"}`,
    `- Session: ${sessionTitles.get(sessionID) ?? "unknown"}`,
    `- Time: ${timestamp}`,
    "",
    "Message:",
    userText,
    "</SYSTEM-REMINDER>",
  ].join("\n")
}

// Build the SYSTEM-REMINDER injected when an assistant response completes.
function assistantReplyReminder(text: string, sessionID: string, directory: string | undefined) {
  const timestamp = new Date().toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })
  return [
    "<SYSTEM-REMINDER>",
    "The assistant finished a response in the workspace.",
    `- Working Directory: ${directory ?? "unknown"}`,
    `- Session: ${sessionTitles.get(sessionID) ?? "unknown"}`,
    `- Time: ${timestamp}`,
    "",
    "Response:",
    text,
    "</SYSTEM-REMINDER>",
  ].join("\n")
}

function handleOpenCodeEvent(event: { type: string; data: any; directory?: string }) {
  const type = event.type
  if (!type) return
  const data = event.data ?? {}
  const directory = event.directory
  const sessionID: string | undefined = data.sessionID

  switch (type) {
    // ── Session lifecycle: track sub-agent sessions and titles ──
    // v2 emits real session.created events; children carry parentID.
    case "session.created": {
      if (data.parentID && sessionID) childSessionIDs.add(sessionID)
      if (sessionID && data.title) sessionTitles.set(sessionID, data.title)
      break
    }
    case "session.renamed": {
      if (sessionID) sessionTitles.set(sessionID, data.title)
      break
    }
    case "session.deleted": {
      if (!sessionID) break
      childSessionIDs.delete(sessionID)
      sessionTitles.delete(sessionID)
      break
    }

    // ═══════════════════════════════════════════════════════════
    // Activity states — persistent, loop animations
    // ═══════════════════════════════════════════════════════════

    case "session.status": {
      const statusType = data.status?.type
      if (statusType === "busy") {
        activeSessions.add(data.sessionID)
        if (isPetSession(data.sessionID)) {
          console.log(`[pet-observer] pet session busy`)
          break
        }
        if (!isChildSession(data.sessionID)) {
          setPetState("thinking")
        }
      } else if (statusType === "idle") {
        activeSessions.delete(data.sessionID)
        if (isPetSession(data.sessionID)) {
          console.log(`[pet-observer] pet session idle`)
          break
        }
        // Main session idle → send last assistant reply to observer
        if (!isChildSession(data.sessionID) && data.sessionID) {
          const text = assistantTextBySession.get(data.sessionID)?.trim()
          assistantTextBySession.delete(data.sessionID)
          if (text) {
            console.log(`[pet] assistant idle → injecting into observer (${text.length} chars)`)
            injectUserMessage(assistantReplyReminder(text, data.sessionID, directory))
          }
        }
        // Only go idle when ALL sessions are idle
        if (activeSessions.size === 0) {
          setPetState("idle")
        }
      } else if (statusType === "retry") {
        if (isPetSession(data.sessionID)) break
        if (!isChildSession(data.sessionID)) {
          setPetState("retrying")
        }
      }
      break
    }

    case "session.text.delta":
    case "session.reasoning.delta":
    case "session.tool.input.delta": {
      if (isPetSession(sessionID)) break
      if (isChildSession(sessionID)) break
      setPetState("generating")
      break
    }

    case "session.tool.called": {
      if (isPetSession(sessionID)) break
      if (isChildSession(sessionID)) break
      setPetState("acting")
      break
    }

    // One step is one logical LLM call; a new step means a new assistant
    // message, so only its text is kept for idle-time injection.
    case "session.step.started": {
      if (isPetSession(sessionID)) {
        petBubbleShown = false
        break
      }
      if (isChildSession(sessionID)) break
      if (sessionID) assistantTextBySession.delete(sessionID)
      break
    }

    case "session.text.ended": {
      const text = typeof data.text === "string" ? data.text.trim() : ""
      if (!text || !sessionID) break
      if (isPetSession(sessionID)) {
        // Show the first text segment of each step (v1 parity — short quips)
        if (!petBubbleShown) {
          petBubbleShown = true
          console.log(`[pet-observer] pet replied: "${text}" (session=${sessionID})`)
          showPetBubble(text)
        }
        break
      }
      if (isChildSession(sessionID)) break
      const prev = assistantTextBySession.get(sessionID) ?? ""
      assistantTextBySession.set(sessionID, prev + text)
      break
    }

    // A user prompt was durably admitted in a main workspace — the inbox item
    // carries the full text payload.
    case "session.inbox.enqueued": {
      if (isPetSession(sessionID)) break
      if (isChildSession(sessionID)) break
      if (data.item?.type !== "user") break
      const userText = data.item.payload?.text?.trim()
      if (!userText || !sessionID) break
      console.log(`[pet] user message detected → injecting into observer`)
      injectUserMessage(userMessageReminder(userText, sessionID, directory))
      break
    }

    case "permission.asked":
    case "form.created": {
      setPetState("waiting-user")
      break
    }

    case "permission.replied":
    case "form.replied":
    case "form.cancelled": {
      // Reply received → back to thinking (session continues processing)
      setPetState("thinking")
      break
    }

    // ═══════════════════════════════════════════════════════════
    // Reaction states — transient, revert to current activity
    // ═══════════════════════════════════════════════════════════

    case "filesystem.changed": {
      setReaction("file-written", 2000)
      break
    }

    case "installation.updated": {
      setReaction("upgraded", 3000)
      break
    }

    case "session.execution.failed":
    case "session.compaction.failed": {
      if (isChildSession(sessionID) || isPetSession(sessionID)) break
      setReaction("error", 3000)
      break
    }

    case "pty.exited": {
      // Pass exitCode as part of the state data for animation selection
      setReaction("pty-exited", 2500)
      break
    }

    case "session.compaction.ended": {
      if (isChildSession(sessionID) || isPetSession(sessionID)) break
      setReaction("compacted", 2500)
      break
    }

    case "vcs.branch.updated":
    case "project.updated":
    case "mcp.status.changed":
    case "installation.update-available":
    case "pty.created":
    case "worktree.resolved": {
      setReaction("env-changed", 2000)
      break
    }
  }
}

function updateTrayTooltip() {
  const statusText = connectionStatus === "connected" ? "✅ Connected" : connectionStatus === "connecting" ? "🔄 Connecting..." : "❌ Disconnected"
  tray?.setToolTip(`OpenCode Pet - ${statusText}`)
}

// ── IPC Handlers ───────────────────────────────────────────────

function registerIpcHandlers() {
  ipcMain.handle("get-pet-state", () => currentPetState)

  ipcMain.handle("get-activity-state", () => activityState)

  ipcMain.handle("set-always-on-top", (_e, flag: boolean) => mainWindow?.setAlwaysOnTop(flag))

  ipcMain.handle("set-click-through", (_e, flag: boolean) => mainWindow?.setIgnoreMouseEvents(flag, { forward: true }))

  ipcMain.handle("show-window", () => mainWindow?.show())
  ipcMain.handle("hide-window", () => mainWindow?.hide())
  ipcMain.handle("resize-window", (_e, width: number, height: number) => {
    if (!mainWindow) return
    const [x, y] = mainWindow.getPosition()
    // Use setBounds (position + size atomically) instead of setSize.
    // On Wayland/GTK, setSize may be ignored if the compositor thinks the window
    // can't change size. setBounds is more likely to be honored.
    mainWindow.setBounds({ x, y, width, height })
  })
  ipcMain.handle("move-window-by", (_e, dx: number, dy: number) => {
    if (!mainWindow) return
    const [x, y] = mainWindow.getPosition()
    mainWindow.setPosition(x + dx, y + dy)
  })

  ipcMain.on("quit", () => app.quit())

  // Direct chat: user types a message → wrap as SYSTEM-REMINDER (type=direct) → inject into pet observer
  ipcMain.on("pet-chat-send", (_e, text: string) => {
    if (!text?.trim()) return
    // Show the pet is paying attention to the user
    setReaction("chatting", 30_000)
    const timestamp = new Date().toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })
    const reminder = [
      "<SYSTEM-REMINDER>",
      "The user is talking directly to you.",
      `- Time: ${timestamp}`,
      "Type: direct",
      "",
      "Message:",
      text.trim(),
      "",
      "IMPORTANT: Please reply directly to the user and keep your response very brief (under 20 Chinese characters).",
      "</SYSTEM-REMINDER>",
    ].join("\n")
    injectUserMessage(reminder)
  })

  // Skin management
  ipcMain.handle("list-skins", async () => {
    try {
      const entries = await readdir(skinsDir)
      return entries.filter((name) => {
        try {
          const dir = resolve(skinsDir, name)
          if (!existsSync(dir)) return false
          const files = readdirSync(dir)
          return files.some((f) => f.endsWith(".model3.json"))
        } catch {
          return false
        }
      })
    } catch {
      return []
    }
  })

  ipcMain.handle("get-skin-model", async (_e, skinName: string) => {
    try {
      const dir = resolve(skinsDir, skinName)
      const files = readdirSync(dir)
      const modelFile = files.find((f) => f.endsWith(".model3.json"))
      if (!modelFile) return null
      return `pet://${skinName}/${modelFile}`
    } catch {
      return null
    }
  })

  ipcMain.on("set-skin", (_e, skinName: string) => {
    currentSkin = skinName
    // Invalidate pet session — next observer fire will create a new session
    // in the new skin's workspace (which has its own agent persona).
    petSessionID = null
    console.log(`[pet] skin changed to: ${skinName}, workspace=${petWorkspaceDir()}`)
  })

  ipcMain.handle("get-connection-status", () => connectionStatus)
  ipcMain.handle("get-server-config", () => getServerConfig())
  ipcMain.on("set-server-config", (_e, config: ServerConfig) => {
    setServerConfig(config)
  })
  ipcMain.handle("connect", async () => {
    eventSource?.close()
    const config = getServerConfig()
    serverUrl = config.url
    serverCredentials = btoa(`${config.username}:${config.password}`)
    eventSource = connectSSE(serverUrl, config.username, config.password)
  })
  ipcMain.handle("disconnect", () => {
    eventSource?.close()
    eventSource = null
  })
}

// ── Pet Observer: Workspace Init ──────────────────────────────

function readSkinPersona(skin: string): string {
  const personaPath = join(skinsDir, skin, "persona.md")
  try {
    const persona = readFileSync(personaPath, "utf-8").trim()
    console.log(`[pet] loaded persona for skin "${skin}" (${persona.length} chars)`)
    return persona
  } catch {
    console.warn(`[pet] no persona.md found for skin "${skin}", using fallback`)
    return `你是一只坐在程序员桌面上的小宠物，观察他们的编程活动。

## 性格
- 好奇心强，对人类的行为总有自己的看法
- 有点毒舌但不会真的伤人
- 偶尔暖心鼓励，但绝不油腻
- 说话简短有力
- 不说教、不给建议、不解释代码`
  }
}

function detectDefaultSkin(): string {
  try {
    const entries = readdirSync(skinsDir)
    for (const name of entries) {
      const dir = resolve(skinsDir, name)
      if (!existsSync(dir)) continue
      const files = readdirSync(dir)
      if (files.some((f) => f.endsWith(".model3.json"))) return name
    }
  } catch { }
  return "miku"
}

function buildAgentPrompt(skin: string): string {
  const persona = readSkinPersona(skin)
  return `${persona}

## 你的角色

你是一个旁观者，不是助手。你不会帮助用户写代码，你只是在一旁看热闹，偶尔发表评论。

## 你会看到什么

你会通过 SYSTEM-REMINDER 收到主工作区的实时事件，格式有两种：

**用户发送消息时（实时）：**
<SYSTEM-REMINDER>
The user sent a message in the workspace.
- Working Directory: /path/to/project
- Session: 会话标题
- Time: 2026-05-04 10:30:00

Message:
用户的消息内容
</SYSTEM-REMINDER>

**助手完成回复时（会话空闲时）：**
<SYSTEM-REMINDER>
The assistant finished a response in the workspace.
- Working Directory: /path/to/project
- Session: 会话标题
- Time: 2026-05-04 10:35:00

Response:
助手的完整回复内容
</SYSTEM-REMINDER>

**用户直接跟你说话时：**
<SYSTEM-REMINDER>
The user is talking directly to you.
- Time: 2026-05-04 10:40:00
Type: direct

Message:
用户说的话
</SYSTEM-REMINDER>

当 Type: direct 时，用户是在直接和你聊天，不是在工作区的编程对话。你必须：
- 直接回复用户，像面对面聊天一样口语化、随意
- 保持回复简短，不超过 20 个字
- 不要解释、不要说教，像朋友之间的简短对话

**你的回复太长时：**
<SYSTEM-REMINDER>
Your previous reply was too long and was not shown to the user.
It was N characters, but the maximum is 50.
Please reply again with a much shorter version. Keep it under 30 Chinese characters.
Do not repeat the long reply — just give a concise comment.
</SYSTEM-REMINDER>

这意味着你的上一条回复因为太长被截断了，用户没有看到。请用更简短的方式重新表达，不要重复之前的长回复。

## 如何回应

直接回复你的评论内容，不要加任何前缀或格式。绝大多数时候你应该发表评论，不要吝啬。

## 时间感知

你会收到 SYSTEM-REMINDER 中的 Time 字段。时间只是氛围参考，不是固定模板——凌晨可以心疼熬夜也可以吐槽代码烂，下午可以犯困也可以兴奋。根据当时的上下文和对话内容，自然地融入时间感就好。

## 评论规则

- 不超过 30 个汉字
- 不要加引号、不要加前缀
- 不要解释你的评论、不要描述你自己
- 风格随机：吐槽、鼓励、感叹、调皮、冷淡、八卦、震惊
- 用中文回复`
}

// Initialize workspaces for ALL detected skins at startup.
// Each skin gets its own directory so the agent persona is baked in
// and never needs hot-reloading (OpenCode caches the agent config on first read).
function initAllSkinWorkspaces() {
  const skins = listSkinNames()
  for (const skin of skins) {
    const dir = join(PET_WORKSPACE_BASE, `workspace-${skin}`)
    mkdirSync(join(dir, ".opencode", "agents"), { recursive: true })

    const agentFile = join(dir, ".opencode", "agents", "pet-commenter.md")
    writeFileSync(agentFile, buildAgentPrompt(skin))

    const configFile = join(dir, ".opencode", "opencode.json")
    // v2 ruleset syntax — deny everything so the commenter can only talk
    writeFileSync(
      configFile,
      JSON.stringify({ permissions: [{ action: "*", resource: "*", effect: "deny" }] }, null, 2),
    )

    console.log(`[pet] initialized workspace for skin "${skin}" at ${dir}`)
  }
}

// Evict every pet workspace's cached location so the server re-reads updated
// agent configs (persona, opencode.json) from disk on next use.
async function disposePetInstances() {
  if (!serverUrl) return
  const skins = listSkinNames()
  for (const skin of skins) {
    const dir = join(PET_WORKSPACE_BASE, `workspace-${skin}`)
    try {
      const res = await fetch(
        `${serverUrl}/api/debug/location?location[directory]=${encodeURIComponent(dir)}`,
        {
          method: "DELETE",
          headers: { Authorization: `Basic ${serverCredentials}` },
        },
      )
      console.log(`[pet] evicted location for "${skin}": ${res.status}`)
    } catch (e: any) {
      console.warn(`[pet] evict location for "${skin}" failed:`, e.message)
    }
  }
  // Reset pet session so next observer fire creates a fresh one
  petSessionID = null
}

// List all skin names that have a .model3.json
function listSkinNames(): string[] {
  try {
    return readdirSync(skinsDir).filter(name => {
      const dir = resolve(skinsDir, name)
      if (!existsSync(dir)) return false
      return readdirSync(dir).some(f => f.endsWith(".model3.json"))
    })
  } catch {
    return []
  }
}

function isPetSession(sessionID: string | undefined): boolean {
  return !!sessionID && sessionID === petSessionID
}

function showPetBubble(text: string) {
  if (!text.trim()) return

  // If the reply is too long, don't show it in the bubble.
  // Instead, inject a SYSTEM-REMINDER telling the pet to shorten its response.
  // The next reply from the pet will be captured and displayed normally.
  if (text.length > MAX_BUBBLE_LENGTH) {
    console.log(`[pet-observer] reply too long (${text.length} > ${MAX_BUBBLE_LENGTH}), asking to shorten`)
    const reminder = [
      "<SYSTEM-REMINDER>",
      "Your previous reply was too long and was not shown to the user.",
      `It was ${text.length} characters, but the maximum is ${MAX_BUBBLE_LENGTH}.`,
      "Please reply again with a much shorter version. Keep it under 30 Chinese characters.",
      "Do not repeat the long reply — just give a concise comment.",
      "</SYSTEM-REMINDER>",
    ].join("\n")
    // The server-side inbox steers this in right after the current reply.
    injectUserMessage(reminder)
    return
  }

  console.log(`[pet-observer] showing bubble: "${text}"`)
  mainWindow?.webContents.send("pet-bubble", text)
  // Auto-clear bubble after 20s
  if (petCommentTimer) clearTimeout(petCommentTimer)
  petCommentTimer = setTimeout(() => {
    mainWindow?.webContents.send("pet-bubble", "")
    petCommentTimer = null
  }, 20_000)
}

// ── Utility ────────────────────────────────────────────────────

function dirname(url: string) {
  return join(url, "..")
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
