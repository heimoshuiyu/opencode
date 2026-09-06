import type { PetState } from "../types"
import motionMap from "./motion-map"

// Live2D model renderer using pixi-live2d-display

export class PetRenderer {
  private app: any = null
  private model: any = null
  private baseSize: number
  private container: HTMLElement
  private live2dCanvas: HTMLCanvasElement | null = null
  private currentAnimation: PetState = "idle"
  private lastMotion: string = ""
  private live2dReady = false
  private Live2DModel: any = null
  private currentModelUrl: string | null = null
  private motionNames: string[] = []
  private loopTimer: ReturnType<typeof setInterval> | null = null
  private originalModelWidth = 0
  private originalModelHeight = 0
  // Actual displayed dimensions (match model aspect ratio, not square)
  displayWidth = 0
  displayHeight = 0
  private resizeCallback: ((w: number, h: number) => void) | null = null

  onResize(cb: (w: number, h: number) => void) {
    this.resizeCallback = cb
  }

  constructor(container: HTMLElement, baseSize: number) {
    this.container = container
    this.baseSize = baseSize
    this.displayWidth = baseSize
    this.displayHeight = baseSize
    this.initLive2D()
  }

  private async initLive2D() {
    // Stub Cubism 2 runtime to prevent bundled code from throwing
    ;(window as any).Live2D = true

    // Load Cubism 4 core via pet:// protocol (fetch + eval)
    console.log("[pet] loading CubismCore...")
    const coreResp = await fetch("pet://lib/live2dcubismcore.min.js")
    if (!coreResp.ok) throw new Error(`Failed to fetch CubismCore: ${coreResp.status}`)
    const coreCode = await coreResp.text()
    // Indirect eval (0,eval)() runs in global scope, required in ES module strict mode
    ;(0, eval)(coreCode)
    console.log("[pet] CubismCore loaded, Live2DCubismCore:", !!(window as any).Live2DCubismCore)

    if (!(window as any).Live2DCubismCore) {
      throw new Error("Live2DCubismCore not set after eval")
    }

    // Load PixiJS v6
    console.log("[pet] loading pixi.js...")
    const PIXI = await import("pixi.js")
    console.log("[pet] pixi.js loaded")

    // Register PIXI on window for pixi-live2d-display
    ;(window as any).PIXI = PIXI

    // Load pixi-live2d-display with Cubism 4 support
    console.log("[pet] loading pixi-live2d-display...")
    const live2dModule = await import("pixi-live2d-display/cubism4")
    this.Live2DModel = live2dModule.Live2DModel
    console.log("[pet] pixi-live2d-display loaded")

    // Create a WebGL canvas for Live2D rendering
    const glCanvas = document.createElement("canvas")
    glCanvas.width = this.displayWidth
    glCanvas.height = this.displayHeight
    glCanvas.style.position = "absolute"
    glCanvas.style.top = "0"
    glCanvas.style.left = "0"
    glCanvas.style.width = "100%"
    glCanvas.style.height = "100%"
    this.container.appendChild(glCanvas)
    this.live2dCanvas = glCanvas

    this.app = new PIXI.Application({
      view: glCanvas,
      transparent: true,
      width: this.displayWidth,
      height: this.displayHeight,
      backgroundColor: 0x000000,
      backgroundAlpha: 0,
    })

    // Cap at 25fps to reduce CPU/GPU load (desktop pet doesn't need 60fps)
    this.app.ticker.maxFPS = 25

    // Try loading models via pet:// protocol (first available wins)
    const modelUrls = [
      "pet://miku/21miku_normal_3.0_f_t03.model3.json",
      "pet://bongo-cat/cat.model3.json",
      "pet://hiyori/Hiyori.model3.json",
    ]

    for (const url of modelUrls) {
      try {
        console.log(`[pet] trying model: ${url}`)
        this.model = await this.Live2DModel.from(url)
        this.currentModelUrl = url
        console.log("[pet] Live2D model loaded:", url)
        break
      } catch (e) {
        console.warn("[pet] Failed to load model:", url, e)
        continue
      }
    }

    if (!this.model) {
      throw new Error("No Live2D model could be loaded")
    }

    this.setupModel()

    this.live2dReady = true
    this.startAnimation("idle")
  }

  private setupModel() {
    if (!this.model) return
    // Store original dimensions BEFORE any scaling
    // At this point model.scale is (1,1) from fresh load
    this.originalModelWidth = this.model.width
    this.originalModelHeight = this.model.height
    this.computeDisplayDimensions()
    const modelScale = Math.min(this.displayWidth / this.originalModelWidth, this.displayHeight / this.originalModelHeight)
    this.model.scale.set(modelScale)
    this.model.anchor.set(0.5, 0.5)
    this.model.x = this.displayWidth / 2
    this.model.y = this.displayHeight / 2
    this.app.stage.addChild(this.model)

    // Resize the WebGL canvas and renderer to match the model aspect ratio
    this.app.renderer.resize(this.displayWidth, this.displayHeight)
    if (this.live2dCanvas) {
      this.live2dCanvas.width = this.displayWidth
      this.live2dCanvas.height = this.displayHeight
    }

    // Extract motion group names from internal model's motion manager
    const motionGroups = this.model.internalModel?.motionManager?.motionGroups
    if (motionGroups) {
      this.motionNames = Object.keys(motionGroups).sort()
    }

    // Notify main.ts to resize the container and window
    this.resizeCallback?.(this.displayWidth, this.displayHeight)
  }

  private computeDisplayDimensions() {
    if (this.originalModelWidth <= 0 || this.originalModelHeight <= 0) {
      this.displayWidth = this.baseSize
      this.displayHeight = this.baseSize
      return
    }
    // Scale the model so its LONGER dimension fits baseSize,
    // then set the window to match the model's actual displayed size.
    const maxDim = Math.max(this.originalModelWidth, this.originalModelHeight)
    const scale = this.baseSize / maxDim
    this.displayWidth = Math.ceil(this.originalModelWidth * scale)
    this.displayHeight = Math.ceil(this.originalModelHeight * scale)
  }

  get isReady() {
    return this.live2dReady
  }

  getMotions(): string[] {
    return this.motionNames
  }

  getCurrentModel(): string | null {
    return this.currentModelUrl
  }

  async switchModel(url: string) {
    if (!this.Live2DModel || !this.app) return

    try {
      console.log(`[pet] switching to model: ${url}`)
      const newModel = await this.Live2DModel.from(url)

      // Remove old model
      if (this.model) {
        this.app.stage.removeChild(this.model)
      }

      this.model = newModel
      this.currentModelUrl = url
      this.motionNames = []
      this.setupModel()

      console.log("[pet] model switched successfully, motions:", this.motionNames.length)
      this.startAnimation("idle")
      return true
    } catch (e) {
      console.warn("[pet] failed to switch model:", e)
      return false
    }
  }

  triggerMotion(name: string) {
    if (!this.model) return false
    try {
      this.model.motion(name, 0, 3)
      return true
    } catch {
      return false
    }
  }

  startAnimation(state: PetState) {
    if (!this.live2dReady) return

    this.currentAnimation = state
    if (!this.model) return

    // Stop any previous loop timer
    if (this.loopTimer) {
      clearInterval(this.loopTimer)
      this.loopTimer = null
    }

    // Play first motion immediately
    this.playRandomMotion(state)

    // Persistent Activity states loop with intervals; Reaction states play once
    const isActivity = state === "idle" || state === "thinking" || state === "generating" || state === "acting" || state === "waiting-user" || state === "retrying" || state === "sleeping" || state === "disconnected"
    if (isActivity) {
      const intervals: Record<string, [number, number]> = {
        idle: [5000, 8000],
        thinking: [4000, 6000],
        generating: [3000, 5000],
        acting: [4000, 6000],
        "waiting-user": [5000, 8000],
        retrying: [5000, 8000],
        sleeping: [8000, 12000],
        disconnected: [6000, 10000],
      }
      const [lo, hi] = intervals[state] ?? [5000, 8000]
      const scheduleNext = () => {
        const delay = lo + Math.random() * (hi - lo)
        this.loopTimer = setTimeout(() => {
          if (this.currentAnimation === state) {
            this.playRandomMotion(state)
            scheduleNext()
          }
        }, delay)
      }
      scheduleNext()
    }
  }

  private playRandomMotion(state: PetState) {
    const motions = motionMap[state]
    if (!motions?.length) return

    // Only play motions that exist in the currently loaded model
    const available = motions.filter((m) => this.motionNames.includes(m))
    if (!available.length) return

    const motion = available[Math.floor(Math.random() * available.length)]
    this.lastMotion = motion
    try {
      this.model.motion(motion, 0)
    } catch {
      // Motion group might not exist
    }
  }

  getLastMotion(): string {
    return this.lastMotion
  }

  getCurrentAnimation(): PetState {
    return this.currentAnimation
  }

  resize(baseSize: number) {
    this.baseSize = baseSize

    if (this.live2dReady && this.model && this.originalModelWidth > 0) {
      this.computeDisplayDimensions()
      this.app.renderer.resize(this.displayWidth, this.displayHeight)
      if (this.live2dCanvas) {
        this.live2dCanvas.width = this.displayWidth
        this.live2dCanvas.height = this.displayHeight
      }
      const modelScale = Math.min(this.displayWidth / this.originalModelWidth, this.displayHeight / this.originalModelHeight)
      this.model.scale.set(modelScale)
      this.model.x = this.displayWidth / 2
      this.model.y = this.displayHeight / 2
      this.resizeCallback?.(this.displayWidth, this.displayHeight)
    } else {
      this.displayWidth = baseSize
      this.displayHeight = baseSize
    }
  }

  destroy() {
    if (this.loopTimer) {
      clearTimeout(this.loopTimer)
      this.loopTimer = null
    }
    try {
      this.app?.destroy(true)
    } catch {
      // ignore
    }
    this.live2dCanvas?.remove()
  }
}
