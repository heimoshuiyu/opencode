import { SessionID } from "@/session/schema"
import { Session } from "@/session"
import { AppRuntime } from "@/effect/app-runtime"
import { MessageV2 } from "@/session/message-v2"
import { tmpdir } from "os"
import path from "path"
import { Schema } from "effect"
import z from "zod"
import { ZodOverride, zod } from "@/util/effect-zod"
import { withStatics } from "@/util/schema"

const FileSchema: Schema.Schema<File> = Schema.Unknown.annotate({ [ZodOverride]: z.instanceof(File) }) as Schema.Schema<File>

export const TranscribeRequest = Schema.Struct({
  file: FileSchema,
  mime: Schema.String,
  sessionID: Schema.optional(SessionID),
  prompt: Schema.optional(Schema.String),
}).pipe(withStatics((s) => ({ zod: zod(s) })))

export const TranscribeResponse = Schema.Struct({
  text: Schema.String,
}).pipe(withStatics((s) => ({ zod: zod(s) })))

export type TranscribeResponse = Schema.Schema.Type<typeof TranscribeResponse>

export const toWavOrMp3 = async (input: { buffer: ArrayBuffer; mime: string }) => {
  const isWav = input.mime.includes("wav")
  const isMp3 = input.mime.includes("mpeg") || input.mime.includes("mp3")
  if (isWav || isMp3) {
    const name = isWav ? "audio.wav" : "audio.mp3"
    const mime = isWav ? "audio/wav" : "audio/mpeg"
    return { buffer: input.buffer, name, mime }
  }

  const outPath = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.mp3`)
  const proc = Bun.spawn(
    [
      "ffmpeg",
      "-y",
      "-f",
      "webm",
      "-i",
      "pipe:0",
      "-ac",
      "1",
      "-ar",
      "16000",
      "-f",
      "mp3",
      outPath,
    ],
    {
      stdin: "pipe",
      stdout: "ignore",
      stderr: "pipe",
    },
  )
  proc.stdin?.write(new Uint8Array(input.buffer))
  proc.stdin?.end()
  await proc.exited

  const stderr = await new Response(proc.stderr).text().catch(() => "")

  if (proc.exitCode !== 0) {
    await Bun.file(outPath).delete().catch(() => {})
    throw new Error(`ffmpeg conversion failed (exit code ${proc.exitCode}): ${stderr}`)
  }

  const file = Bun.file(outPath, { type: "audio/mpeg" })
  const buffer = await file.arrayBuffer().catch(() => undefined)
  await Bun.file(outPath).delete().catch(() => {})
  if (!buffer) throw new Error("Failed to convert audio: output file was not created")
  return { buffer, name: "audio.mp3", mime: "audio/mpeg" }
}

export const getLastAssistantText = async (sessionID?: SessionID) => {
  if (!sessionID) return ""
  return AppRuntime.runPromise(Session.Service.use((s) => s.messages({ sessionID, limit: 50 })))
    .then((messages) => {
      for (let i = messages.length - 1; i >= 0; i -= 1) {
        const msg = messages[i]
        if (msg.info.role !== "assistant") continue
        const text = msg.parts
          .filter((part: MessageV2.Part) => part.type === "text")
          .map((part: MessageV2.TextPart) => part.text)
          .join(" ")
          .trim()
        if (text) return text
      }
      return ""
    })
    .catch((error) => {
      console.log("whisper session lookup failed", { error: String(error) })
      return ""
    })
}

export const buildPrompt = (input: { prompt?: string; assistant?: string }) => {
  const head = input.assistant?.trim() ?? ""
  const tail = input.prompt?.trim() ?? ""
  if (!head) return tail
  if (!tail) return head
  return `${head} ${tail}`
}

export const prepareAudio = async (file: File, mime: string) => {
  const content = await file.arrayBuffer()
  return toWavOrMp3({ buffer: content, mime })
}

export const buildTranscriptionContext = async (input: {
  sessionID?: SessionID
  userPrompt?: string
  systemPrompt?: string
}) => {
  const assistant = await getLastAssistantText(input.sessionID)
  const userContext = buildPrompt({ assistant, prompt: input.userPrompt })

  if (input.systemPrompt) {
    return buildPrompt({ assistant: input.systemPrompt, prompt: userContext })
  }

  return userContext
}
