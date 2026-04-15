import { Service, type Info } from "@/config/config"
import { prepareAudio, buildTranscriptionContext, TranscribeRequest, TranscribeResponse } from "@/voice/common"
import { AppRuntime } from "@/effect/app-runtime"
import { Schema } from "effect"

export { TranscribeRequest, TranscribeResponse }

export async function transcribe(
  input: Schema.Schema.Type<typeof TranscribeRequest> & { signal?: AbortSignal; voice?: Info["voice"] },
) {
  const validated = TranscribeRequest.zod.parse(input)
  const voice = input.voice ?? (await AppRuntime.runPromise(Service.use((cfg) => cfg.get()))).voice
  const whisper = voice?.whisper
  const apiKey = whisper?.apiKey
  if (!apiKey) {
    throw new Error("Missing voice.whisper.apiKey")
  }

  const prepared = await prepareAudio(validated.file, validated.mime)
  const prompt = await buildTranscriptionContext({
    sessionID: validated.sessionID,
    userPrompt: validated.prompt,
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
  console.log("whisper request", {
    url,
    model: whisper?.model ?? "whisper-1",
    bytes: prepared.buffer.byteLength,
  })
  const result = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
    },
    body: form,
    signal: input.signal,
  })

  if (!result.ok) {
    const message = await result.text().catch(() => "")
    throw new Error(message || "Whisper request failed")
  }

  const contentType = result.headers.get("content-type") ?? ""
  const body = await result.text().catch(() => "")
  console.log("whisper response", { contentType, body })
  const payload = body ? JSON.parse(body) : { text: "" }
  const text = typeof payload?.text === "string" ? payload.text : ""
  return TranscribeResponse.zod.parse({ text })
}

export * as Whisper from "./whisper"
