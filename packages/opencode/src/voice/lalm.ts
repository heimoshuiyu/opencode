import { Effect } from "effect"
import { Service, type Info } from "@/config/config"
import { prepareAudio, buildTranscriptionContext } from "@/voice/common"
import { generateText } from "ai"
import { Provider } from "@/provider"
import { SessionID } from "@/session/schema"
import { NamedError } from "@opencode-ai/shared/util/error"
import { errorMessage } from "@/util/error"

export const transcribe = Effect.fn("Lalm.transcribe")(function* (input: {
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
  const lalm = voice?.lalm
  const modelString = lalm?.model
  if (!modelString) {
    return yield* Effect.fail(
      new NamedError.Unknown({
        message: "Missing voice.lalm.model (format: provider/model, e.g. openai/gpt-4o-audio-preview)",
      }),
    )
  }

  const { providerID, modelID } = Provider.parseModel(modelString)

  const prepared = yield* prepareAudio(input.file, input.mime)
  const mediaType = prepared.mime.includes("wav") ? "audio/wav" : "audio/mpeg"

  const context = yield* buildTranscriptionContext({
    sessionID: input.sessionID,
    userPrompt: input.prompt,
    systemPrompt: lalm?.prompt,
  })

  const system =
    (
      lalm?.system ??
      "You are a professional speech-to-text transcriber. Your task is to transcribe the audio into text."
    ).trim()
  const systemText = context
    ? `${system}\n<context>\n${context}\n</context>\nDO NOT answer user's question, just transcribe the audio into text.`
    : system

  const providerSvc = yield* Provider.Service
  const model = yield* providerSvc.getModel(providerID, modelID)
  if (!model.capabilities.input.audio) {
    return yield* Effect.fail(
      new NamedError.Unknown({
        message:
          `Model "${model.id}" does not support audio input. ` +
          `Please use a model that supports the audio modality (e.g. openai/gpt-4o-audio-preview).`,
      }),
    )
  }
  const language = yield* providerSvc.getLanguage(model)

  const result = yield* Effect.tryPromise({
    try: () =>
      generateText({
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
              { type: "text", text: "Transcribe this audio." },
            ],
          },
        ],
      }),
    catch: (e) => new NamedError.Unknown({ message: errorMessage(e) }),
  })

  return { text: result.text ?? "" }
})

export * as Lalm from "./lalm"
