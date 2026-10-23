import { createEffect, onCleanup } from "solid-js"
import { createMediaQuery } from "@solid-primitives/media"
import { useLanguage } from "@/runtime/i18n/language"
import type { voiceView } from "./voice-machine"
import "./voice-scope.css"

// One envelope per column, newest at the centre. A quiet voice moves the same braid as a loud one;
// the floor keeps room noise from filling the plate. No audio is retained by the renderer.
export function VoiceScope(props: { view: () => ReturnType<typeof voiceView> }) {
  const language = useLanguage()
  const reduced = createMediaQuery("(prefers-reduced-motion: reduce)")
  let canvas!: HTMLCanvasElement
  createEffect(() => {
    const { meter, recording, phase } = props.view()
    const still = reduced()
    const ctx = canvas.getContext("2d")

    if (!ctx) return
    const scale = Math.min(window.devicePixelRatio || 1, 3)
    canvas.width = 123 * scale
    canvas.height = 27 * scale
    ctx.scale(scale, scale)
    ctx.fillStyle = getComputedStyle(canvas).color
    const samples = new Float32Array(meter?.fftSize ?? 0)
    const history = new Float32Array(21)
    let peak = -32
    let level = 0
    let last = performance.now()
    let tick = 0
    let frame = 0
    let time = 0

    const draw = (now: number) => {
      const dt = Math.min(0.06, (now - last) / 1000)
      last = now
      time += dt

      if (meter && !still) {
        meter.getFloatTimeDomainData(samples)
        let energy = 0

        for (const sample of samples) energy += sample * sample
        const db = 10 * Math.log10(energy / samples.length + 1e-10)
        peak = Math.max(-40, db, peak - dt * 5)
        const floor = Math.max(-55, peak - 24)
        const target = Math.max(0, Math.min(1, (db - floor) / (peak - floor)))
        level += (target - level) * (1 - Math.exp(-dt / (target > level ? 0.035 : 0.095)))
        tick += dt

        while (tick >= 0.022) {
          history.copyWithin(1, 0, 20)
          tick -= 0.022
        }

        history[0] = level
      }

      ctx.clearRect(0, 0, 123, 27)

      for (let col = 0; col < 41; col++) {
        const x = (col - 20) / 20
        const envelope = history[Math.abs(col - 20)]
        const width = (0.65 + envelope * 3.4) * (1 - 0.45 * x * x)
        const a = width * Math.sin(x * 6 - time * 4)
        const b = -width * Math.sin(x * 6 - time * 4 + 0.65)
        const sweep = 0.5 + 0.5 * Math.cos(x * 4 - time * 5)

        for (let row = -4; row <= 4; row++) {
          const distance = Math.min(Math.abs(row - a), Math.abs(row - b))
          const light = Math.exp(-distance * distance * 1.6)
          ctx.globalAlpha = 0.1 + light * (recording ? 0.52 + envelope * 0.35 : 0.25 + sweep * 0.6)
          ctx.beginPath()
          ctx.arc(col * 3 + 1.5, 13.5 + row * 3, 0.65 + light * 0.45, 0, Math.PI * 2)
          ctx.fill()
        }
      }

      if (!still && phase !== "opening") frame = requestAnimationFrame(draw)
    }

    draw(last)
    onCleanup(() => cancelAnimationFrame(frame))
  })

  const label = () => {
    switch (props.view().phase) {
      case "opening":
        return language.t("prompt.voice.opening")
      case "recording":
        return language.t("prompt.voice.recording")
      case "attaching":
        return language.t("prompt.voice.attaching")
      default:
        return language.t("prompt.voice.transcribing")
    }
  }

  return (
    <div class="composer-voice-scope" role="status">
      <canvas ref={canvas} aria-hidden="true" />
      <span>{label()}</span>
    </div>
  )
}
