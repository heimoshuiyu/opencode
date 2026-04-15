import { tmpdir } from "os"
import path from "path"
import type { TuiConfig } from "../config"
import { errorMessage } from "./error"

export type VoiceConfig = NonNullable<TuiConfig.Resolved["voice"]>

type SdkVoiceConfig = {
  type?: "whisper" | "lalm"
  whisper?: { url?: string; apiKey?: string; model?: string; language?: string }
  lalm?: { model?: string; system?: string; instruction?: string; audio_input_format?: "input_audio" | "audio_url" }
}

const voiceProviderType = (voice: SdkVoiceConfig | undefined): "whisper" | "lalm" =>
  voice?.type ?? "lalm"

const voiceProviderStatus = (voice: SdkVoiceConfig | undefined) => {
  const type = voiceProviderType(voice)
  if (type === "lalm") {
    if (!voice?.lalm?.model) {
      return { ok: false as const, message: "Missing voice.lalm.model (format: provider/model, e.g. openai/gpt-4o-audio-preview)" }
    }
    return { ok: true as const, type }
  }
  if (!voice?.whisper?.apiKey) {
    return { ok: false as const, message: "Missing voice.whisper.apiKey" }
  }
  return { ok: true as const, type }
}

// Platform-specific recording commands.
// ffmpeg flags vary by OS: Linux uses pulse/alsa, macOS uses avfoundation, Windows uses wasapi.
// sox/rec are cross-platform but require installation.
const platformCommands: Record<string, string[][]> = {
  linux: [
    ["ffmpeg", "-y", "-f", "pulse", "-i", "default", "-ac", "1", "-f", "mp3", "{output}"],
    ["ffmpeg", "-y", "-f", "alsa", "-i", "default", "-ac", "1", "-f", "mp3", "{output}"],
    ["arecord", "-f", "S16_LE", "-c", "1", "-r", "48000", "{output}"],
  ],
  darwin: [
    ["ffmpeg", "-y", "-f", "avfoundation", "-i", ":0", "-ac", "1", "-f", "mp3", "{output}"],
  ],
  win32: [
    ["ffmpeg", "-y", "-f", "wasapi", "-i", "default", "-ac", "1", "-f", "mp3", "{output}"],
  ],
}

// Cross-platform fallbacks (sox/rec work on all platforms once installed)
const crossPlatformCommands = [
  ["sox", "-d", "-c", "1", "{output}"],
  ["rec", "-c", "1", "{output}"],
]

const defaultCommands = [
  ...(platformCommands[process.platform] ?? []),
  ...crossPlatformCommands,
]

const defaultMime = "audio/mpeg"

const findCommand = (config?: VoiceConfig) => {
  if (config?.command?.length) return config.command
  for (const candidate of defaultCommands) {
    const bin = candidate[0]
    if (!bin) continue
    if (Bun.which(bin)) return candidate
  }
  return undefined
}

const noCommandMessage = "No recording command available (install ffmpeg or sox)"

const pickCommand = (config?: VoiceConfig) => {
  const cmd = findCommand(config)
  if (!cmd) throw new Error(noCommandMessage)
  return cmd
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

  const availability = () => voiceProviderStatus(input.transcription?.())

  const isEnabled = () => availability().ok && !!findCommand(input.config())

  const unavailableMessage = () => {
    const avail = availability()
    if (!avail.ok) return avail.message
    if (!findCommand(input.config())) return noCommandMessage
    return undefined
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
    console.debug("recorder started", { args, output: state.output })
    return true
  }

  const transcribeBuffer = (buffer: ArrayBuffer, mime: string) => {
    const voice = input.transcription?.()
    const type = voiceProviderType(voice)
    console.debug("transcribe start", {
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
        console.debug("transcribe failed", { error: errorMessage(error), provider: type })
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
    console.debug("recorder output", { stdout, stderr })

    const mime = input.config()?.mime ?? defaultMime
    const buffer = await Bun.file(pathResult).arrayBuffer().catch((err) => {
      throw new Error(`Failed to read voice recording: ${err instanceof Error ? err.message : String(err)}`)
    })
    console.debug("recorder bytes", { bytes: buffer.byteLength })

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
    unavailableMessage,
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
