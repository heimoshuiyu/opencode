import { tmpdir } from "os"
import path from "path"
import type { Info } from "@/config/config"
import { errorMessage } from "@/util/error"
import * as Log from "@opencode-ai/core/util/log"

const log = Log.create({ service: "voice" })

export type VoiceConfig = {
  command?: string[]
  mime?: string
}

type SdkVoiceConfig = NonNullable<Info["voice"]>

const defaultCommands = [
  ["ffmpeg", "-y", "-f", "pulse", "-i", "default", "-ac", "1", "-ar", "16000", "-f", "mp3", "{output}"],
  ["ffmpeg", "-y", "-f", "alsa", "-i", "default", "-ac", "1", "-ar", "16000", "-f", "mp3", "{output}"],
  ["sox", "-d", "-c", "1", "-r", "16000", "{output}"],
  ["rec", "-c", "1", "-r", "16000", "{output}"],
  ["arecord", "-f", "S16_LE", "-c", "1", "-r", "16000", "{output}"],
]

const defaultMime = "audio/mpeg"

const pickCommand = (config?: VoiceConfig) => {
  if (config?.command?.length) return config.command
  for (const candidate of defaultCommands) {
    const bin = candidate[0]
    if (!bin) continue
    if (Bun.which(bin)) return candidate
  }
  throw new Error("No recording command available (install ffmpeg, sox, or arecord)")
}

const readStream = async (stream?: ReadableStream<Uint8Array> | number | null) => {
  if (!stream || typeof stream === "number") return ""
  return new Response(stream).text().catch(() => "")
}

export function create(input: {
  config: () => VoiceConfig | undefined
  transcription?: () => SdkVoiceConfig | undefined
  prompt?: () => string | undefined
  transcribe: (audio: string, mime: string, prompt?: string, signal?: AbortSignal) => Promise<{ text: string }>
}) {
  const state = {
    proc: undefined as ReturnType<typeof Bun.spawn> | undefined,
    output: undefined as string | undefined,
    controller: undefined as AbortController | undefined,
    cancelled: false,
    lastRecording: undefined as { path: string; mime: string } | undefined,
  }

  const isEnabled = () => {
    const voice = input.transcription?.()
    const type = voice?.type ?? "lalm"
    if (type === "lalm") return !!voice?.lalm?.model
    return !!voice?.whisper?.apiKey
  }

  const start = async () => {
    if (state.proc) return false
    // Clear any previous failed recording before starting a new one
    clearRecording()
    const config = input.config()
    const command = pickCommand(config)
    const outputPath = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.mp3`)
    state.output = outputPath
    const args = command.map((entry) => entry.replaceAll("{output}", outputPath))
    state.proc = Bun.spawn(args, { stdout: "pipe", stderr: "pipe" })
    log.info("recorder started", { args, output: state.output })
    return true
  }

  const transcribeBuffer = (buffer: ArrayBuffer, mime: string) => {
    const voice = input.transcription?.()
    const type = voice?.type ?? "lalm"
    log.info("transcribe start", {
      provider: type,
      bytes: buffer.byteLength,
      ...(type === "lalm"
        ? { model: voice?.lalm?.model }
        : { url: voice?.whisper?.url, model: voice?.whisper?.model, language: voice?.whisper?.language }),
    })
    state.cancelled = false
    state.controller = new AbortController()

    const base64 = Buffer.from(buffer).toString("base64")
    return input
      .transcribe(base64, mime, input.prompt?.(), state.controller.signal)
      .then((response) => {
        state.controller = undefined
        if (state.cancelled) return { text: "", cancelled: true }
        return { text: response.text, cancelled: false }
      })
      .catch((error) => {
        state.controller = undefined
        log.error("transcribe failed", { error: errorMessage(error), provider: type })
        if ((error instanceof Error && error.name === "AbortError") || state.cancelled) {
          return { text: "", cancelled: true }
        }
        throw error instanceof Error ? error : new Error(errorMessage(error))
      })
  }

  const stop = async () => {
    if (!state.proc || !state.output) return
    const target = state.proc
    state.proc = undefined
    const pathResult = state.output
    state.output = undefined
    target.kill()
    await target.exited.catch(() => {})

    const stdout = await readStream(target.stdout)
    const stderr = await readStream(target.stderr)
    log.debug("recorder output", { stdout, stderr })

    const mime = input.config()?.mime ?? defaultMime
    const buffer = await Bun.file(pathResult).arrayBuffer().catch((err) => {
      throw new Error(`Failed to read voice recording: ${err instanceof Error ? err.message : String(err)}`)
    })
    log.debug("recorder bytes", { bytes: buffer.byteLength })

    // Keep temp file for retry instead of deleting immediately
    state.lastRecording = { path: pathResult, mime }

    return transcribeBuffer(buffer, mime)
  }

  const retry = async () => {
    if (!state.lastRecording) return
    const { path: recordingPath, mime } = state.lastRecording
    const buffer = await Bun.file(recordingPath).arrayBuffer().catch((err) => {
      throw new Error(`Failed to read voice recording: ${err instanceof Error ? err.message : String(err)}`)
    })
    return transcribeBuffer(buffer, mime)
  }

  const clearRecording = () => {
    if (!state.lastRecording) return
    Bun.file(state.lastRecording.path).delete().catch(() => {})
    state.lastRecording = undefined
  }

  const hasRecording = () => !!state.lastRecording

  const cancel = () => {
    if (!state.controller) return false
    state.cancelled = true
    state.controller.abort()
    return true
  }

  const destroy = () => {
    if (state.controller) {
      state.cancelled = true
      state.controller.abort()
    }
    if (state.proc) {
      state.proc.kill()
      const file = state.output
      state.proc = undefined
      state.output = undefined
      if (file) Bun.file(file).delete().catch(() => {})
    }
    clearRecording()
  }

  return {
    isEnabled,
    start,
    stop,
    retry,
    cancel,
    destroy,
    clearRecording,
    hasRecording,
  }
}

export * as Voice from "./voice"
