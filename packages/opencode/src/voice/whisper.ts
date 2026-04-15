import { Effect } from "effect"
import { Service, type Info } from "@/config/config"
import { prepareAudio, buildTranscriptionContext } from "@/voice/common"
import * as Log from "@opencode-ai/core/util/log"
import { SessionID } from "@/session/schema"
import { errorMessage } from "@/util/error"
import { NamedError } from "@opencode-ai/core/util/error"

const log = Log.create({ service: "voice.whisper" })

export const transcribe = Effect.fn("Whisper.transcribe")(function* (input: {
  file: File
  mime: string
  sessionID?: SessionID
  prompt?: string
  signal?: AbortSignal
  voice?: Info["voice"]
}) {
  const svc = yield* Service
  const config = yield* svc.get()
  const voice = input.voice ?? config.voice
  const whisper = voice?.whisper
  const apiKey = whisper?.apiKey
  if (!apiKey) {
    return yield* Effect.fail(new NamedError.Unknown({ message: "Missing voice.whisper.apiKey" }))
  }

  const prepared = yield* prepareAudio(input.file, input.mime)
  const prompt = yield* buildTranscriptionContext({
    sessionID: input.sessionID,
    userPrompt: input.prompt,
  })

  const form = new FormData()
  form.append("file", new Blob([prepared.buffer], { type: prepared.mime }), prepared.name)
  form.append("model", whisper?.model ?? "whisper-1")
  form.append("response_format", "json")
  if (whisper?.language) {
    form.append("language", whisper.language)
  }
  if (prompt) {
    form.append("prompt", prompt)
  }

  const url = whisper?.url ?? "https://api.openai.com/v1/audio/transcriptions"
  log.debug("whisper request", {
    url,
    model: whisper?.model ?? "whisper-1",
    bytes: prepared.buffer.byteLength,
  })

  const result = yield* Effect.tryPromise({
    try: () =>
      fetch(url, {
        method: "POST",
        headers: { Authorization: `Bearer ${apiKey}` },
        body: form,
        signal: input.signal,
      }),
    catch: (e) => new NamedError.Unknown({ message: errorMessage(e) }),
  })

  if (!result.ok) {
    const body = yield* Effect.tryPromise({ try: () => result.text(), catch: () => "" })
    return yield* Effect.fail(new NamedError.Unknown({ message: body || "Whisper request failed" }))
  }

  const body = yield* Effect.tryPromise({ try: () => result.text(), catch: () => "" })
  log.debug("whisper response", { contentType: result.headers.get("content-type") ?? "" })
  const payload = body ? JSON.parse(body) : { text: "" }
  const text = typeof payload?.text === "string" ? payload.text : ""
  return { text }
})

export * as Whisper from "./whisper"
