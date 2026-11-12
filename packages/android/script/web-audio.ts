import { readFileSync, readdirSync } from "node:fs"
import { fileURLToPath } from "node:url"

// The APK owns these two media boundaries. Never replace browser globals: GPT-Live uses them too.
const app = fileURLToPath(new URL("../../app/src/", import.meta.url))
const client = JSON.stringify(fileURLToPath(new URL("./web-audio-client.ts", import.meta.url)))
const assets = new URL("../../ui/src/assets/audio/", import.meta.url)

// Short rising/falling capture cues, rendered at build time instead of opening Web Audio.
function cue(start: boolean) {
  const rate = 24000
  const frames = Math.round(rate * 0.09)
  const wav = Buffer.alloc(44 + frames * 2)
  wav.write("RIFF"); wav.writeUInt32LE(wav.length - 8, 4); wav.write("WAVEfmt ", 8)
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22)
  wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28); wav.writeUInt16LE(2, 32)
  wav.writeUInt16LE(16, 34); wav.write("data", 36); wav.writeUInt32LE(frames * 2, 40)
  let phase = 0
  for (let i = 0; i < frames; i++) {
    const t = i / rate
    phase += 2 * Math.PI / rate * (start ? 480 : 650) * (start ? 720/480 : 420/650) ** Math.min(1, t/0.065)
    const gain = Math.min(1, t/0.006) * Math.exp(-80 * Math.max(0, t - 0.006))
    wav.writeInt16LE(Math.round(2100 * gain * Math.sin(phase)), 44 + 2 * i)
  }
  return wav
}

function replace(source: string, from: string, to: string, body: string) {
  const start = source.indexOf(from)
  const end = source.indexOf(to, start + from.length)
  if (start < 0 || end < 0) throw new Error(`Update Android audio adapter: missing ${from} or ${to}`)
  return source.slice(0, start) + body + source.slice(end)
}

export function androidAudio() {
  return {
    name: "opencode-android:audio",
    enforce: "pre" as const,
    transform(source: string, id: string) {
      if (id === app + "shell/notifications/sound.ts") {
        // Keep the actual settings catalogue, not a second hand-maintained copy of 45 IDs.
        return `export { playSoundById } from ${client}\n` + replace(source, "let files:", "export const SOUND_OPTIONS", "").split("type SoundOption =")[0]
      }
      if (id !== app + "composer/voice.tsx") return
      const code = replace(source, "    if (!navigator.mediaDevices", "  const toggleVoice", `    const attempt = new AbortController()
    dispatch({ type: "start", attempt, output })
    await recordComposer(attempt.signal, settings.sounds.interfaceEnabled, {
      ready(stop, meter) { if (current(attempt)) dispatch({ type: "ready", attempt, stop, meter }) },
      done(blob) {
        if (!current(attempt)) return
        dispatch({ type: "stopped", attempt, blob }); if (blob.size) void process(blob, attempt)
      },
      error(message) {
        if (!current(attempt)) return
        cancel(); showToast({ title: language.t("prompt.toast.voiceUnavailable.title"), description: message })
      },
    })
  }

`)
      if (!code.includes('recording-${Date.now()}.webm')) throw new Error("Update Android recording filename adapter")
      return `import { recordComposer } from ${client}\n` + code.replace('recording-${Date.now()}.webm', 'recording-${Date.now()}.ogg')
    },
    transformIndexHtml() {
      return [{ tag: "script", attrs: { src: "/android-audio-port.js" }, injectTo: "head-prepend" as const }]
    },
    generateBundle(this: { emitFile(asset: {type: "asset"; fileName: string; source: Buffer}): unknown }) {
      for (const start of [true, false]) {
        this.emitFile({ type: "asset", fileName: `media-sounds/cue-${start ? "start" : "end"}.wav`, source: cue(start) })
      }
      for (const name of readdirSync(assets).filter((name) => name.endsWith(".aac"))) {
        this.emitFile({ type: "asset", fileName: `media-sounds/${name}`, source: readFileSync(new URL(name, assets)) })
      }
    },
  }
}
