import { Service, type Info } from "@/config/config"
import { prepareAudio, buildTranscriptionContext, TranscribeRequest, TranscribeResponse } from "@/voice/common"
import { AppRuntime } from "@/effect/app-runtime"
import { generateText } from "ai"
import { Provider } from "@/provider"
import { Schema } from "effect"

export { TranscribeRequest, TranscribeResponse }

export async function transcribe(
  input: Schema.Schema.Type<typeof TranscribeRequest> & { signal?: AbortSignal; voice?: Info["voice"] },
) {
  const validated = TranscribeRequest.zod.parse(input)
  const voice = input.voice ?? (await AppRuntime.runPromise(Service.use((cfg) => cfg.get()))).voice
  const alm = voice?.alm
  const modelString = alm?.model
  if (!modelString) {
    throw new Error("Missing voice.alm.model (format: provider/model, e.g. openai/gpt-4o-audio-preview)")
  }

  const { providerID, modelID } = Provider.parseModel(modelString)

  const prepared = await prepareAudio(validated.file, validated.mime)
  const mediaType = prepared.mime.includes("wav") ? "audio/wav" : "audio/mpeg"

  const context = await buildTranscriptionContext({
    sessionID: validated.sessionID,
    userPrompt: validated.prompt,
    systemPrompt: alm?.prompt,
  })

  const system = (alm?.system ?? "You are a professional speech-to-text transcriber. Your task is to transcribe the audio into text.").trim()
  const systemText = context
    ? `${system}\n<context>\n${context}\n</context>\nDO NOT answer user's question, just transcribe the audio into text.`
    : system

  console.log("alm request", {
    model: modelString,
    bytes: prepared.buffer.byteLength,
  })

  const model = await AppRuntime.runPromise(
    Provider.Service.use((s) => s.getModel(providerID, modelID)),
  )
  const language = await AppRuntime.runPromise(
    Provider.Service.use((s) => s.getLanguage(model)),
  )

  const result = await generateText({
    model: language,
    temperature: model.capabilities.temperature ? 0 : undefined,
    abortSignal: input.signal,
    system: systemText,
    messages: [
      {
        role: "user",
        content: [
          {
            type: "file",
            data: prepared.buffer,
            mediaType,
          },
          { type: "text", text: "You are a professional speech to text transcriber, your task is to transcribe the audio into text." },
        ],
      },
    ],
  })

  return TranscribeResponse.zod.parse({ text: result.text ?? "" })
}

export * as Alm from "./alm"
