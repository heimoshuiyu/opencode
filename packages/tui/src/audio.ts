import { Audio, type AudioErrorContext, type AudioPlayOptions, type AudioSound } from "@opentui/core"
import { readFile } from "node:fs/promises"
import { encodeWav } from "./util/wav-encode"

let audio: Audio | null | undefined
const sounds = new Map<string, Promise<AudioSound | null>>()
const cues = new Map<"start" | "stop", AudioSound>()

export function getAudio() {
  if (audio !== undefined) return audio
  try {
    const next = Audio.create({ autoStart: false })
    next.on("error", (error: Error, context: AudioErrorContext) => {
      console.debug("tui audio error", { error, context })
    })
    audio = next
    return next
  } catch (error) {
    console.debug("failed to create tui audio", { error })
    audio = null
    return null
  }
}

export function loadSoundFile(file: string) {
  const current = getAudio()
  if (!current) return Promise.resolve(null)
  const cached = sounds.get(file)
  if (cached) return cached
  const task = readFile(file)
    .then((bytes) => current.loadSound(bytes))
    .catch((error) => {
      console.debug("failed to load tui sound", { file, error })
      return null
    })
  sounds.set(file, task)
  return task
}

export function play(sound: AudioSound, options?: AudioPlayOptions) {
  const current = getAudio()
  if (!current) return null
  if (!current.isStarted() && !current.start()) return null
  return current.play(sound, options)
}

/** The composer's short rising/falling sine taps, not terminal bells or notifications. */
export function playDictationCue(kind: "start" | "stop", volume: number) {
  if (volume <= 0) return
  try {
    const current = getAudio()
    if (!current) return
    let sound = cues.get(kind)
    if (sound === undefined) {
      const rate = 24000
      const start = kind === "start" ? 480 : 650
      const end = kind === "start" ? 720 : 420
      let phase = 0
      const samples = Float32Array.from({ length: rate * 0.09 }, (_, i) => {
        const t = i / rate
        phase += (2 * Math.PI * start * (end / start) ** Math.min(1, t / 0.065)) / rate
        return Math.sin(phase) * Math.min(1, t / 0.006) * 0.2 * Math.exp(-Math.max(0, t - 0.006) * 80)
      })
      sound = current.loadSound(encodeWav(samples, rate, 1)) ?? undefined
      if (sound === undefined) return
      cues.set(kind, sound)
    }
    play(sound, { volume })
  } catch (error) {
    console.debug("dictation cue unavailable", { error })
  }
}

export function dispose() {
  audio?.dispose()
  audio = undefined
  sounds.clear()
  cues.clear()
}
