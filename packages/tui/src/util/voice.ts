import type { Config } from "../config"
import { getAudio } from "../audio"
import { encodeWav } from "./wav-encode"

type VoiceConfig = NonNullable<Config.Resolved["voice"]>

const pollIntervalMs = 100

export type Recorder = {
  stop: () => Promise<{ mime: string; buffer: ArrayBuffer }>
  kill: () => void
}

const noMicMessage = "Native audio capture unavailable (opentui native library required)"

function nativeRecorderAvailable() {
  const audio = getAudio()
  if (!audio) return false
  return typeof audio.startCapture === "function"
}

export function isEnabled() {
  return nativeRecorderAvailable()
}

export function unavailableMessage() {
  return nativeRecorderAvailable() ? undefined : noMicMessage
}

function startNativeRecording(config?: VoiceConfig): Recorder {
  const audio = getAudio()!
  const channels = config?.channels ?? 1
  const capacityFrames = audio.sampleRate * 60
  const pollFrameChunk = Math.ceil((audio.sampleRate * pollIntervalMs) / 1000)

  const started = audio.startCapture({ channels, capacityFrames })
  if (!started) throw new Error("Failed to start native audio capture")

  const chunks: Float32Array[] = []

  const timer = setInterval(() => {
    const result = audio.readCaptureFrames(pollFrameChunk)
    if (result && result.framesRead > 0) chunks.push(result.frames.subarray(0, result.framesRead * channels))
  }, pollIntervalMs)

  return {
    kill: () => {
      clearInterval(timer)
      audio.stopCapture()
    },
    stop: async () => {
      clearInterval(timer)
      for (;;) {
        const result = audio.readCaptureFrames(pollFrameChunk * 10)
        if (!result || result.framesRead === 0) break
        chunks.push(result.frames.subarray(0, result.framesRead * channels))
      }
      audio.stopCapture()

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

export async function startRecording(config?: VoiceConfig): Promise<Recorder> {
  if (!nativeRecorderAvailable()) throw new Error(noMicMessage)
  return startNativeRecording(config)
}

export async function transcribe(
  recording: { buffer: ArrayBuffer; mime: string },
  send: (audio: string, mime: string, prompt?: string, signal?: AbortSignal) => Promise<{ text: string }>,
  prompt?: string,
  controller?: AbortController,
) {
  const base64 = Buffer.from(recording.buffer).toString("base64")
  const response = await send(base64, recording.mime, prompt, controller?.signal)
  if (controller?.signal.aborted) return { text: "", cancelled: true }
  return { text: response.text, cancelled: false }
}
