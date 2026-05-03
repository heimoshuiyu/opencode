import type { PetState } from "./types"
import type { PetApi } from "../preload/types"
import { PetRenderer } from "./pet/renderer"
import motionMap from "./pet/motion-map"

const pet = window.pet as PetApi

// Suppress unhandled errors — network errors from SSE disconnects are expected
// and would otherwise trigger Electron's default error dialog spam.
window.addEventListener("error", (e) => {
  console.warn("[pet] uncaught error:", e.message)
  e.preventDefault()
})
window.addEventListener("unhandledrejection", (e) => {
  console.warn("[pet] unhandled rejection:", e.reason)
  e.preventDefault()
})

declare global {
  interface Window {
    pet: {
      getPetState: () => Promise<PetState>
      onPetStateChange: (callback: (state: PetState) => void) => () => void
      onPetBubble: (callback: (text: string) => void) => () => void
      sendChatMessage: (text: string) => void
      getConnectionStatus: () => Promise<string>
      getServerConfig: () => Promise<{ url: string; username: string; password: string }>
      setServerConfig: (config: { url: string; username: string; password: string }) => void
      connect: () => Promise<void>
      disconnect: () => Promise<void>
      setAlwaysOnTop: (flag: boolean) => Promise<void>
      setClickThrough: (flag: boolean) => Promise<void>
      listSkins: () => Promise<string[]>
      getSkinModel: (name: string) => Promise<string | null>
      showWindow: () => Promise<void>
      hideWindow: () => Promise<void>
      resizeWindow: (width: number, height: number) => Promise<void>
      moveWindowBy: (dx: number, dy: number) => Promise<void>
      quit: () => void
    }
  }
}

// ── State management (cooldown queue: buffer rapid state changes) ──
//
// Problem: SSE events can arrive in rapid bursts (e.g. file.edited → session.diff
// → message.part.updated within 100ms), causing jarring animation flicker.
//
// Solution: after every visual state change, enforce a cooldown before
// the next transition. States arriving during cooldown are buffered (last-write-wins).
// When cooldown expires, only the latest buffered state is applied — intermediate
// ones are discarded. This gives each animation a minimum visible duration while
// keeping the pet responsive to the latest event.
//
// v4 changes:
//   - Activity transitions use shorter cooldown (500ms) — meaningful phase changes
//   - Reaction events use full cooldown (3500ms) — prevent flicker
//   - Revert timer only handles idle→sleep; Reaction revert is managed by main process
//   - Reaction revert goes to current activity state, not always idle

// Activity states — persistent, loop animations
const ACTIVITY_STATES = new Set([
  "idle", "thinking", "generating", "acting", "waiting-user", "retrying", "sleeping", "disconnected",
])

const IDLE_TO_SLEEP_MS = 30_000
const ACTIVITY_COOLDOWN_MS = 500
const REACTION_COOLDOWN_MS = 3500

let currentState: PetState = "idle"
let activityState: PetState = "idle"  // mirror of main process activityState
let idleTimer: ReturnType<typeof setTimeout> | null = null
let cooldownTimer: ReturnType<typeof setTimeout> | null = null
let pendingState: PetState | null = null

function setPetState(state: PetState) {
  // During cooldown: buffer latest state (skip same state to avoid repeats)
  if (cooldownTimer) {
    if (state !== currentState) pendingState = state
    return
  }

  // Skip if already in this state
  if (currentState === state) {
    if (state === "idle") resetIdleTimer()
    return
  }

  applyState(state)

  // Cooldown duration depends on whether the NEW state is an Activity or Reaction
  const isActivityTransition = ACTIVITY_STATES.has(state) && ACTIVITY_STATES.has(currentState)
  const cooldownMs = isActivityTransition ? ACTIVITY_COOLDOWN_MS : REACTION_COOLDOWN_MS

  // Start cooldown — no visual state change for this duration
  cooldownTimer = setTimeout(() => {
    cooldownTimer = null
    if (pendingState) {
      const next = pendingState
      pendingState = null
      setPetState(next)
    }
  }, cooldownMs)
}

function applyState(state: PetState) {
  if (idleTimer) { clearTimeout(idleTimer); idleTimer = null }

  currentState = state

  // Track Activity state locally (Reaction revert comes from main process)
  if (ACTIVITY_STATES.has(state)) activityState = state

  renderer.startAnimation(state)

  // Start idle→sleep timer
  if (state === "idle") resetIdleTimer()
}

function resetIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    idleTimer = null
    setPetState("sleeping")
  }, IDLE_TO_SLEEP_MS)
}

const BASE_PET_SIZE = 200
const BASE_SETTINGS_PANEL_HEIGHT = 420
const BASE_SETTINGS_MIN_WIDTH = 300

const savedScale = parseFloat(localStorage.getItem("pet-scale") || "1")
let petScale = Math.max(0.5, Math.min(3, isNaN(savedScale) ? 1 : savedScale))

function petSize() {
  return Math.round(BASE_PET_SIZE * petScale)
}

// Dampened UI scale: 25% of pet zoom (2x pet → 1.25x UI)
function uiScale() {
  return 1 + (petScale - 1) * 0.25
}

function settingsPanelHeight() {
  return Math.round(BASE_SETTINGS_PANEL_HEIGHT * uiScale())
}

function settingsMinWidth() {
  return Math.round(BASE_SETTINGS_MIN_WIDTH * uiScale())
}

// Initialize renderer
const petContainer = document.getElementById("pet-container")!
const initialSize = petSize()
petContainer.style.width = `${initialSize}px`
petContainer.style.height = `${initialSize}px`

const renderer = new PetRenderer(petContainer, initialSize)

// When the renderer computes new display dimensions (model loaded, scale changed),
// update the container CSS and window size to match — no wasted transparent space.
renderer.onResize((w, h) => {
  petContainer.style.width = `${w}px`
  petContainer.style.height = `${h}px`
  if (settingsOpen) {
    pet.resizeWindow(Math.max(w, settingsMinWidth()), h + settingsPanelHeight())
  } else {
    pet.resizeWindow(w, h)
  }
})

// Listen for state changes from main process (OpenCode events)
pet.onPetStateChange((state) => {
  setPetState(state)
})

// Initialize with current state
pet.getPetState().then((state) => {
  setPetState(state)
})

// Notify main process of initial skin once model loads
function notifyInitialSkin() {
  const url = renderer.getCurrentModel()
  if (url) {
    const skin = new URL(url).hostname
    pet.setSkin(skin)
  }
}

// Poll until model is loaded (it's async in PetRenderer constructor)
const skinNotifyInterval = setInterval(() => {
  if (renderer.isReady) {
    notifyInitialSkin()
    clearInterval(skinNotifyInterval)
  }
}, 200)

// ── Drag behavior & interaction ─────────────────────────────────
// Per-element hit testing for click-through:
// Default: setIgnoreMouseEvents(true, { forward: true }) — transparent areas pass through.
// mousemove is always forwarded even when ignoring, so we use elementFromPoint to detect
// whether the cursor is over the pet or settings panel, and toggle capture accordingly.

let isDragging = false
let isInteracting = false // true while any mouse button is held (slider, button, etc.)
let isCapturingEvents = false
let wasDragged = false
let dragStartX = 0
let dragStartY = 0

// Start in click-through mode (transparent window)
pet.setClickThrough(true)

function updateHitTest(e: MouseEvent) {
  // During drag or interaction, keep current capture state
  if (isDragging || isInteracting) return

  const target = document.elementFromPoint(e.clientX, e.clientY)
  const shouldCapture = !!target?.closest("#pet-container") || !!target?.closest("#settings-panel")

  if (shouldCapture !== isCapturingEvents) {
    isCapturingEvents = shouldCapture
    pet.setClickThrough(!shouldCapture)
  }
}

window.addEventListener("mousemove", (e) => {
  updateHitTest(e)

  if (!isDragging) return
  const dx = e.screenX - dragStartX
  const dy = e.screenY - dragStartY
  if (Math.abs(dx) > 2 || Math.abs(dy) > 2) wasDragged = true
  dragStartX = e.screenX
  dragStartY = e.screenY
  pet.moveWindowBy(dx, dy)
})

document.addEventListener("mousedown", (e) => {
  isInteracting = true
  wasDragged = false
  if (e.button !== 0 || settingsOpen) return
  // Don't start drag when clicking the chat input
  if ((e.target as HTMLElement).closest("#chat-input")) return
  isDragging = true
  dragStartX = e.screenX
  dragStartY = e.screenY
})

document.addEventListener("mouseup", () => {
  isDragging = false
  isInteracting = false
})

// Right-click to open settings
document.addEventListener("contextmenu", (e) => {
  e.preventDefault()
  openSettings()
})

// ── AI Comment Bubble ──────────────────────────────────────────

let bubbleTimer: ReturnType<typeof setTimeout> | null = null

function showBubble(text: string, durationMs: number) {
  const bubble = document.getElementById("status-bubble")
  if (!bubble) return

  if (bubbleTimer) { clearTimeout(bubbleTimer); bubbleTimer = null }

  if (!text) {
    bubble.style.opacity = "0"
    setTimeout(() => { bubble.style.display = "none" }, 300)
    return
  }

  bubble.textContent = text
  bubble.style.display = "block"
  bubble.style.opacity = "1"

  bubbleTimer = setTimeout(() => {
    bubbleTimer = null
    bubble.style.opacity = "0"
    setTimeout(() => { bubble.style.display = "none" }, 300)
  }, durationMs)
}

pet.onPetBubble((text) => {
  showBubble(text || "", text ? 20_000 : 0)
})

// ── Direct Chat Input ──────────────────────────────────────────

const CHAT_AUTO_HIDE_MS = 3000
const chatInput = document.getElementById("chat-input") as HTMLInputElement
let chatAutoHideTimer: ReturnType<typeof setTimeout> | null = null

function showChatInput() {
  if (chatAutoHideTimer) { clearTimeout(chatAutoHideTimer); chatAutoHideTimer = null }
  chatInput.style.display = "block"
  // Trigger reflow for transition
  requestAnimationFrame(() => {
    chatInput.style.opacity = "1"
    chatInput.focus()
  })
}

function hideChatInput() {
  if (chatAutoHideTimer) { clearTimeout(chatAutoHideTimer); chatAutoHideTimer = null }
  chatInput.style.opacity = "0"
  setTimeout(() => { chatInput.style.display = "none" }, 200)
}

function scheduleChatAutoHide() {
  if (chatAutoHideTimer) { clearTimeout(chatAutoHideTimer); chatAutoHideTimer = null }
  // Only auto-hide if input is empty
  if (!chatInput.value.trim()) {
    chatAutoHideTimer = setTimeout(() => {
      chatAutoHideTimer = null
      if (!chatInput.value.trim()) hideChatInput()
    }, CHAT_AUTO_HIDE_MS)
  }
}

// On click on pet (not drag), show the chat input
petContainer.addEventListener("click", () => {
  if (settingsOpen || wasDragged) return
  showChatInput()
})

chatInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    const text = chatInput.value.trim()
    if (!text) return
    chatInput.value = ""
    pet.sendChatMessage(text)
    hideChatInput()
  }
  // Prevent Escape from closing settings when typing
  e.stopPropagation()
})

// Cancel auto-hide while typing (has content)
chatInput.addEventListener("input", () => {
  if (chatAutoHideTimer) { clearTimeout(chatAutoHideTimer); chatAutoHideTimer = null }
  scheduleChatAutoHide()
})

// Start auto-hide timer when losing focus
chatInput.addEventListener("blur", () => {
  scheduleChatAutoHide()
})

// ── Settings Panel ─────────────────────────────────────────────

let settingsOpen = false
let currentSkin: string | null = null
const overlay = document.getElementById("settings-overlay")!
const closeBtn = document.getElementById("settings-close")!
const skinList = document.getElementById("skin-list")!
const motionListEl = document.getElementById("motion-list")!
const motionFilter = document.getElementById("motion-filter") as HTMLInputElement
const motionCount = document.getElementById("motion-count")!

function openSettings() {
  if (settingsOpen) return
  settingsOpen = true
  overlay.style.display = "flex"
  const w = renderer.displayWidth
  const h = renderer.displayHeight
  pet.resizeWindow(Math.max(w, settingsMinWidth()), h + settingsPanelHeight())
  populateSkins()
  populateMotions()
  updateDebugInfo()
  loadServerConfig()
}

function closeSettings() {
  if (!settingsOpen) return
  settingsOpen = false
  overlay.style.display = "none"
  const w = renderer.displayWidth
  const h = renderer.displayHeight
  pet.resizeWindow(w, h)
}

closeBtn.addEventListener("click", closeSettings)

// Close on Escape
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && settingsOpen) closeSettings()
})

// ── Skin Switching ──────────────────────────────────────────────

async function populateSkins() {
  const skins = await pet.listSkins()
  // Determine current skin from model URL
  const currentUrl = renderer.getCurrentModel()
  currentSkin = currentUrl ? new URL(currentUrl).hostname : null

  skinList.innerHTML = ""
  for (const skin of skins) {
    const btn = document.createElement("button")
    btn.className = "skin-btn" + (skin === currentSkin ? " active" : "")
    btn.textContent = skin
    btn.addEventListener("click", async () => {
      if (skin === currentSkin) return
      const modelUrl = await pet.getSkinModel(skin)
      if (!modelUrl) return
      const ok = await renderer.switchModel(modelUrl)
      if (ok) {
        currentSkin = skin
        pet.setSkin(skin)
        // Update active state
        skinList.querySelectorAll(".skin-btn").forEach((b) => b.classList.remove("active"))
        btn.classList.add("active")
        populateMotions()
      }
    })
    skinList.appendChild(btn)
  }
}

// ── Motion Tester ───────────────────────────────────────────────

function getMotionCategory(name: string): string {
  if (name.startsWith("w-special")) return "special"
  if (name.startsWith("w-")) return "w-body"
  return "face"
}

function populateMotions(filter = "") {
  const motions = renderer.getMotions()
  const filtered = filter
    ? motions.filter((m) => m.toLowerCase().includes(filter.toLowerCase()))
    : motions

  motionCount.textContent = `${filtered.length}/${motions.length}`
  motionListEl.innerHTML = ""

  for (const name of filtered) {
    const btn = document.createElement("button")
    btn.className = "motion-item " + getMotionCategory(name)
    btn.textContent = name
    btn.title = name
    btn.addEventListener("click", () => {
      renderer.triggerMotion(name)
    })
    motionListEl.appendChild(btn)
  }
}

motionFilter.addEventListener("input", () => {
  populateMotions(motionFilter.value)
})

// ── State Trigger Buttons ───────────────────────────────────────

document.querySelectorAll(".state-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    const state = (btn as HTMLElement).dataset.state as PetState
    if (state) setPetState(state)
  })
})

// ── Always on Top Toggle ────────────────────────────────────────

document.getElementById("toggle-always-on-top")!.addEventListener("change", (e) => {
  pet.setAlwaysOnTop((e.target as HTMLInputElement).checked)
})

// ── Scale Slider ───────────────────────────────────────────────

const scaleSlider = document.getElementById("scale-slider") as HTMLInputElement
const scaleValue = document.getElementById("scale-value")!

function applyScale(scale: number) {
  petScale = scale
  localStorage.setItem("pet-scale", scale.toString())

  // All rem-based sizes (font, padding, gap, etc.) scale via root font-size.
  // Dampened to 25% so UI doesn't blow up at high zoom (2x pet → 1.25x UI).
  document.documentElement.style.setProperty("--pet-scale", uiScale().toString())

  // resize() internally computes displayWidth/displayHeight based on model aspect ratio,
  // then calls onResize callback which updates container + window
  renderer.resize(petSize())
}

// Initialize slider from saved scale
scaleSlider.value = String(Math.round(petScale * 10))
scaleValue.textContent = `${petScale.toFixed(1)}x`

// Apply saved scale on startup (resize window to match)
if (petScale !== 1) {
  applyScale(petScale)
}

scaleSlider.addEventListener("input", () => {
  const scale = parseInt(scaleSlider.value) / 10
  scaleValue.textContent = `${scale.toFixed(1)}x`
  applyScale(scale)
})

// ── Debug Info ───────────────────────────────────────────────────

function updateDebugInfo() {
  const el = document.getElementById("debug-info")
  if (!el) return
  const state = renderer.getCurrentAnimation()
  const motion = renderer.getLastMotion()
  const total = renderer.getMotions().length
  const mapped = motionMap[state]?.length ?? 0
  const available = (motionMap[state] ?? []).filter((m: string) => renderer.getMotions().includes(m)).length
  el.innerHTML =
    `State: <b>${state}</b> · Motion: <b>${motion || "—"}</b><br>` +
    `Mapped: ${mapped} · Available: ${available}/${total}`
}

// Refresh debug info every 500ms while settings are open
setInterval(() => {
  if (settingsOpen) updateDebugInfo()
}, 500)

// ── Server Config UI ──────────────────────────────────────────

const configUrl = document.getElementById("config-url") as HTMLInputElement
const configUsername = document.getElementById("config-username") as HTMLInputElement
const configPassword = document.getElementById("config-password") as HTMLInputElement
const configDot = document.getElementById("config-dot") as HTMLSpanElement
const configStatusText = document.getElementById("config-status-text") as HTMLSpanElement
const btnConnect = document.getElementById("btn-connect") as HTMLButtonElement
const btnDisconnect = document.getElementById("btn-disconnect") as HTMLButtonElement

function updateConnectionUI(status: string) {
  configDot.className = "dot " + status
  configStatusText.textContent = status
  btnConnect.style.display = status === "connected" ? "none" : ""
  btnDisconnect.style.display = status === "connected" ? "" : "none"
}

async function loadServerConfig() {
  const config = await pet.getServerConfig()
  configUrl.value = config.url
  configUsername.value = config.username
  configPassword.value = config.password
  const status = await pet.getConnectionStatus()
  updateConnectionUI(status)
}

// Save config on input change (debounced)
let configSaveTimer: ReturnType<typeof setTimeout> | null = null
function scheduleConfigSave() {
  if (configSaveTimer) clearTimeout(configSaveTimer)
  configSaveTimer = setTimeout(() => {
    configSaveTimer = null
    pet.setServerConfig({
      url: configUrl.value || "http://127.0.0.1:4096",
      username: configUsername.value || "opencode",
      password: configPassword.value,
    })
  }, 500)
}

configUrl.addEventListener("input", scheduleConfigSave)
configUsername.addEventListener("input", scheduleConfigSave)
configPassword.addEventListener("input", scheduleConfigSave)

btnConnect.addEventListener("click", async () => {
  // Save first, then connect
  pet.setServerConfig({
    url: configUrl.value || "http://127.0.0.1:4096",
    username: configUsername.value || "opencode",
    password: configPassword.value,
  })
  updateConnectionUI("connecting")
  await pet.connect()
})

btnDisconnect.addEventListener("click", async () => {
  await pet.disconnect()
  updateConnectionUI("disconnected")
})

// Poll connection status while settings open
setInterval(() => {
  if (settingsOpen) {
    pet.getConnectionStatus().then(updateConnectionUI)
  }
}, 2000)
