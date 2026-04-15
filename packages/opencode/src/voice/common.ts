import { Effect } from "effect"
import { SessionID } from "@/session/schema"
import { Session } from "@/session"
import { MessageV2 } from "@/session/message-v2"
import { tmpdir } from "os"
import path from "path"
import { Log } from "@/util"
import { spawn } from "child_process"
import { readFile, unlink } from "fs/promises"

const log = Log.create({ service: "voice" })

export const toWavOrMp3 = Effect.fn("Voice.toWavOrMp3")(function* (input: {
  buffer: ArrayBuffer
  mime: string
}) {
  const isWav = input.mime.includes("wav")
  const isMp3 = input.mime.includes("mpeg") || input.mime.includes("mp3")
  if (isWav || isMp3) {
    const name = isWav ? "audio.wav" : "audio.mp3"
    const mime = isWav ? "audio/wav" : "audio/mpeg"
    return { buffer: input.buffer, name, mime }
  }

  const outPath = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.mp3`)

  yield* Effect.callback<void, Error>((resume) => {
    const proc = spawn("ffmpeg", ["-y", "-f", "webm", "-i", "pipe:0", "-ac", "1", "-ar", "16000", "-f", "mp3", outPath], {
      stdio: ["pipe", "ignore", "pipe"],
    })

    proc.stdin!.write(new Uint8Array(input.buffer))
    proc.stdin!.end()

    let stderr = ""
    proc.stderr!.on("data", (chunk: Buffer) => {
      stderr += chunk.toString()
    })

    proc.on("close", (code) => {
      if (code !== 0) {
        void unlink(outPath).catch(() => {})
        resume(Effect.fail(new Error(`ffmpeg conversion failed (exit code ${code}): ${stderr}`)))
      } else {
        resume(Effect.succeed(void 0))
      }
    })

    proc.on("error", (err) => {
      resume(Effect.fail(err instanceof Error ? err : new Error(String(err))))
    })
  })

  const buffer = yield* Effect.tryPromise({
    try: () => readFile(outPath) as Promise<Buffer>,
    catch: () => new Error("Failed to convert audio: output file was not created"),
  })

  yield* Effect.tryPromise({ try: () => unlink(outPath), catch: () => undefined as void }).pipe(Effect.ignore)

  return { buffer: buffer.buffer as ArrayBuffer, name: "audio.mp3", mime: "audio/mpeg" } as const
})

export const getLastAssistantText = Effect.fn("Voice.getLastAssistantText")(function* (sessionID?: SessionID) {
  if (!sessionID) return ""

  const svc = yield* Session.Service
  const messages = yield* svc.messages({ sessionID, limit: 50 }).pipe(
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

export const buildPrompt = (input: { prompt?: string; assistant?: string }) => {
  const head = input.assistant?.trim() ?? ""
  const tail = input.prompt?.trim() ?? ""
  if (!head) return tail
  if (!tail) return head
  return `${head} ${tail}`
}

export const prepareAudio = Effect.fn("Voice.prepareAudio")(function* (file: File, mime: string) {
  const content = yield* Effect.tryPromise({ try: () => file.arrayBuffer(), catch: (e) => e })
  return yield* toWavOrMp3({ buffer: content, mime })
})

export const buildTranscriptionContext = Effect.fn("Voice.buildTranscriptionContext")(function* (input: {
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
