import type { Config } from "../config"
import { getAudio } from "../audio"
import { encodeWav } from "./wav-encode"

type TranscriptionConfig = NonNullable<Config.Resolved["transcription"]>

const pollIntervalMs = 100

export type Recorder = {
  stop: () => { mime: string; buffer: ArrayBuffer }
  kill: () => void
  level: () => number
}

export function startRecording(config?: TranscriptionConfig): Recorder {
  const audio = getAudio()
  if (!audio?.startCapture) throw new Error("Native audio capture unavailable (opentui native library required)")
  const channels = config?.channels ?? 1
  const capacityFrames = audio.sampleRate * 60
  const pollFrameChunk = Math.ceil((audio.sampleRate * pollIntervalMs) / 1000)

  const started = audio.startCapture({ channels, capacityFrames })
  if (!started) throw new Error("Failed to start native audio capture")

  const chunks: Float32Array[] = []

  let level = 0
  let peak = -32
  const timer = setInterval(() => {
    const result = audio.readCaptureFrames(pollFrameChunk)
    if (!result || !result.framesRead) {
      level = 0
      return
    }
    const samples = result.frames.subarray(0, result.framesRead * channels)
    chunks.push(samples)
    let energy = 0
    let sum = 0
    for (const sample of samples) {
      sum += sample
      energy += sample * sample
    }
    // Remove DC offset; follow recent peaks without amplifying the noise floor.
    const db = 10 * Math.log10(Math.max(1e-10, energy / samples.length - (sum / samples.length) ** 2))
    peak = Math.max(-40, db, peak - 0.5)
    const floor = Math.max(-55, peak - 24)
    const target = Math.max(0, Math.min(1, (db - floor) / (peak - floor)))
    level += (target - level) * (target > level ? 0.94 : 0.5)
  }, pollIntervalMs)

  return {
    level: () => level,
    kill: () => {
      clearInterval(timer)
      audio.stopCapture()
    },
    stop: () => {
      clearInterval(timer)
      try {
        for (;;) {
          const result = audio.readCaptureFrames(pollFrameChunk * 10)
          if (!result || result.framesRead === 0) break
          chunks.push(result.frames.subarray(0, result.framesRead * channels))
        }
      } finally {
        audio.stopCapture()
      }

      let totalSamples = 0
      for (const chunk of chunks) totalSamples += chunk.length
      const merged = new Float32Array(totalSamples)
      let offset = 0
      for (const chunk of chunks) {
        merged.set(chunk, offset)
        offset += chunk.length
      }

      const buffer = encodeWav(merged, audio.sampleRate, channels)
      console.debug("native recorder samples", { samples: totalSamples, bytes: buffer.byteLength })
      return { mime: "audio/wav", buffer }
    },
  }
}
