import { Effect, Stream } from "effect"
import { ChildProcess, ChildProcessSpawner } from "effect/unstable/process"
import { SessionID } from "@/session/schema"
import { Session } from "@/session/session"
import { MessageV2 } from "@/session/message-v2"
import * as Log from "@opencode-ai/core/util/log"
import { VoiceError } from "@/voice/error"

const log = Log.create({ service: "voice" })

export interface Deps {
  spawner: ChildProcessSpawner.ChildProcessSpawner["Service"]
  session: Session.Interface
}

export const make = (deps: Deps) => {
  const toWavOrMp3 = Effect.fn("Voice.toWavOrMp3")(
    function* (input: { buffer: ArrayBuffer; mime: string }) {
      const isWav = input.mime.includes("wav")
      const isMp3 = input.mime.includes("mpeg") || input.mime.includes("mp3")
      if (isWav || isMp3) {
        const name = isWav ? "audio.wav" : "audio.mp3"
        const mime = isWav ? "audio/wav" : "audio/mpeg"
        return { buffer: input.buffer, name, mime }
      }

      const handle = yield* deps.spawner
        .spawn(
          ChildProcess.make("ffmpeg", [
            "-y", "-f", "webm", "-i", "pipe:0",
            "-ac", "1", "-ar", "16000", "-f", "mp3", "pipe:1",
          ], {
            stdin: Stream.make(new Uint8Array(input.buffer)),
            stdout: "pipe",
            stderr: "pipe",
          }),
        )
        .pipe(
          Effect.mapError((cause) =>
            new VoiceError.ConversionError({
              message: "Failed to start ffmpeg for voice audio conversion",
              cause,
            }),
          ),
        )

      const [stdout, stderr, code] = yield* Effect.all(
        [
          Stream.runFold(
            handle.stdout,
            () => ({ chunks: Array<Uint8Array>(), bytes: 0 }),
            (acc, chunk) => {
              acc.chunks.push(chunk)
              acc.bytes += chunk.length
              return acc
            },
          ).pipe(Effect.map((result) => Buffer.concat(result.chunks, result.bytes))),
          Stream.mkString(Stream.decodeText(handle.stderr)),
          handle.exitCode,
        ],
        { concurrency: 3 },
      ).pipe(
        Effect.mapError((cause) =>
          new VoiceError.ConversionError({
            message: "Failed to convert voice audio with ffmpeg",
            cause,
          }),
        ),
      )

      if (code !== 0) {
        return yield* new VoiceError.ConversionError({
          message: `ffmpeg conversion failed (exit code ${code}): ${stderr.trim() || "unknown error"}`,
        })
      }
      if (!stdout.byteLength) {
        return yield* new VoiceError.ConversionError({ message: "ffmpeg conversion produced no audio output" })
      }

      return {
        buffer: stdout.buffer.slice(stdout.byteOffset, stdout.byteOffset + stdout.byteLength),
        name: "audio.mp3",
        mime: "audio/mpeg",
      } as const
    },
    Effect.scoped,
  )

  const getLastAssistantText = Effect.fn("Voice.getLastAssistantText")(function* (sessionID?: SessionID) {
    if (!sessionID) return ""

    const messages = yield* deps.session.messages({ sessionID, limit: 50 }).pipe(
      Effect.catch(() => {
        log.error("session lookup failed", {})
        return Effect.succeed([] as Array<{ info: { role: string }; parts: Array<MessageV2.Part> }>)
      }),
    )

    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const msg = messages[i]
      if (msg.info.role !== "assistant") continue
      const text = msg.parts
        .filter((part): part is MessageV2.TextPart => part.type === "text")
        .map((part) => part.text)
        .join(" ")
        .trim()
      if (text) return text
    }
    return ""
  })

  const prepareAudio = Effect.fn("Voice.prepareAudio")(function* (file: File, mime: string) {
    const content = yield* Effect.tryPromise({
      try: () => file.arrayBuffer(),
      catch: (cause) => new VoiceError.FileError({ message: "Failed to read voice audio file", cause }),
    })
    return yield* toWavOrMp3({ buffer: content, mime })
  })

  const buildTranscriptionContext = Effect.fn("Voice.buildTranscriptionContext")(function* (input: {
    sessionID?: SessionID
    userPrompt?: string
    systemPrompt?: string
  }) {
    const assistant = yield* getLastAssistantText(input.sessionID)
    const userContext = buildPrompt({ assistant, prompt: input.userPrompt })

    if (input.systemPrompt) {
      return buildPrompt({ assistant: input.systemPrompt, prompt: userContext })
    }

    return userContext
  })

  return { toWavOrMp3, getLastAssistantText, prepareAudio, buildTranscriptionContext }
}

export const buildPrompt = (input: { prompt?: string; assistant?: string }) => {
  const head = input.assistant?.trim() ?? ""
  const tail = input.prompt?.trim() ?? ""
  if (!head) return tail
  if (!tail) return head
  return `${head} ${tail}`
}

export * as VoiceCommon from "./common"
