import { tmpdir } from "os"
import path from "path"
import type { Info } from "@/config/config"
import type { SessionID } from "@/session/schema"
import { errorMessage } from "@/util/error"
import * as Log from "@opencode-ai/core/util/log"
import { Whisper } from "@/voice/whisper"
import { AppRuntime } from "@/effect/app-runtime"

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

export type ServerTransport = {
  transcribe: (params: {
    audio: string
    mime: string
    sessionID?: string
    prompt?: string
  }) => Promise<{ data?: { text: string }; error?: unknown }>
}

export function create(input: {
  config: () => VoiceConfig | undefined
  transcription?: () => SdkVoiceConfig | undefined
  sessionID?: () => SessionID | undefined
  prompt?: () => string | undefined
  server?: ServerTransport
}) {
  const state = {
    proc: undefined as ReturnType<typeof Bun.spawn> | undefined,
    output: undefined as string | undefined,
    controller: undefined as AbortController | undefined,
    cancelled: false,
  }

  const isEnabled = () => {
    const voice = input.transcription?.()
    const type = voice?.type ?? "whisper"
    if (type === "lalm") return !!voice?.lalm?.model
    return !!voice?.whisper?.apiKey
  }

  const start = async () => {
    if (state.proc) return false
    const config = input.config()
    const command = pickCommand(config)
    state.output = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.mp3`)
    const args = command.map((entry) => entry.replaceAll("{output}", state.output!))
    state.proc = Bun.spawn(args, { stdout: "pipe", stderr: "pipe" })
    log.info("recorder started", { args, output: state.output })
    return true
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
    await Bun.file(pathResult).delete().catch(() => {})

    const blob = new Blob([buffer], { type: mime })
    const apiFile = new File([blob], "audio.mp3", { type: mime })
    const voice = input.transcription?.()
    const type = voice?.type ?? "whisper"
    log.info("transcribe start", {
      provider: type,
      bytes: buffer.byteLength,
      ...(type === "lalm" ? { model: voice?.lalm?.model } : { url: voice?.whisper?.url, model: voice?.whisper?.model, language: voice?.whisper?.language }),
    })
    state.cancelled = false
    state.controller = new AbortController()

    let result: { text: string; cancelled: boolean }
    try {
      let response: { text: string }
      if (type === "lalm" && input.server) {
        const base64 = Buffer.from(buffer!).toString("base64")
        const resp = await input.server.transcribe({
          audio: base64,
          mime,
          sessionID: input.sessionID?.(),
          prompt: input.prompt?.(),
        })
        if (resp.error || !resp.data) throw new Error(errorMessage(resp.error) || "Voice transcription failed")
        response = { text: resp.data.text }
      } else {
        response = await AppRuntime.runPromise(
          Whisper.transcribe({
            file: apiFile,
            mime,
            sessionID: input.sessionID?.(),
            prompt: input.prompt?.(),
            signal: state.controller!.signal,
            voice,
          }),
        )
      }
      result = { text: response.text, cancelled: false }
    } catch (error: unknown) {
      log.error("transcribe failed", { error: errorMessage(error), provider: type })
      if ((error instanceof Error && error.name === "AbortError") || state.cancelled) {
        result = { text: "", cancelled: true }
      } else {
        throw error instanceof Error ? error : new Error(errorMessage(error))
      }
    }
    state.controller = undefined
    return result
  }

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
  }

  return {
    isEnabled,
    start,
    stop,
    cancel,
    destroy,
  }
}

export * as Voice from "./voice"
