import { Config } from "@/config/config"
import { Session } from "@/session"
import { toWavOrMp3, getLastAssistantText, buildPrompt, prepareAudio, buildTranscriptionContext } from "@/voice/common"
import z from "zod"

export { toWavOrMp3, getLastAssistantText, buildPrompt }

export namespace Whisper {
  export const Request = z.object({
    file: z.instanceof(File),
    mime: z.string(),
    sessionID: Session.Info.shape.id.optional(),
    prompt: z.string().optional(),
  })

  export const Response = z.object({
    text: z.string().default(""),
  })

  export type Response = z.infer<typeof Response>

  export async function transcribe(
    input: z.infer<typeof Request> & { signal?: AbortSignal; voice?: Config.Info["voice"] },
  ) {
    const voice = input.voice ?? (await Config.get()).voice
    const whisper = voice?.whisper
    const apiKey = whisper?.apiKey
    if (!apiKey) {
      throw new Error("Missing voice.whisper.apiKey")
    }

    const prepared = await prepareAudio(input.file, input.mime)
    const prompt = await buildTranscriptionContext({
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
    console.log("whisper request", {
      url,
      model: whisper?.model ?? "whisper-1",
      language: whisper?.language,
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
    return Response.parse({ text })
  }
}
