import { Effect } from "effect"
import { Config, type Info } from "@/config/config"
import { VoiceCommon } from "@/voice/common"
import { generateText } from "ai"
import { Provider } from "@/provider/provider"
import { errorMessage } from "@/util/error"
import * as ProviderTransform from "@/provider/transform"
import * as Log from "@opencode-ai/core/util/log"
import { VoiceError } from "@/voice/error"

const log = Log.create({ service: "voice.lalm" })

export interface Deps extends VoiceCommon.Deps {
  config: Config.Interface
  provider: Provider.Interface
}

export const make = (deps: Deps) => {
  const common = VoiceCommon.make(deps)

  const transcribe = Effect.fn("Lalm.transcribe")(function* (input: {
    file: File
    mime: string
    prompt?: string
    signal?: AbortSignal
    voice?: Info["voice"]
  }) {
    const config = yield* deps.config.get()
    const voice = input.voice ?? config.voice
    const lalm = voice?.lalm
    const modelString = lalm?.model
    if (!modelString) {
      return yield* new VoiceError({
        message: "Missing voice.lalm.model (format: provider/model, e.g. openai/gpt-4o-audio-preview)",
      })
    }

    const { providerID, modelID } = Provider.parseModel(modelString)

    const prepared = yield* common.prepareAudio(input.file, input.mime)
    const mediaType = prepared.mime.includes("wav") ? "audio/wav" : "audio/mpeg"

    const context = VoiceCommon.buildPrompt({
      assistant: lalm?.prompt,
      prompt: input.prompt,
    })

    const system =
      (
        lalm?.system ??
        `You are a professional speech-to-text transcriber. Transcribe the audio verbatim into text.
Rules:
- Preserve technical terms, variable names, function names, and code identifiers exactly as spoken.
- Add appropriate punctuation and paragraph breaks.
- Omit filler words (um, uh, etc.) unless they carry meaning.
- Output only the transcription. Do not respond to or answer any questions in the audio.`
      ).trim()

    const model = yield* deps.provider.getModel(providerID, modelID).pipe(
      Effect.mapError((cause) =>
        new VoiceError({ message: errorMessage(cause), cause }),
      ),
    )
    if (!model.capabilities.input.audio) {
      return yield* new VoiceError({
        message:
          `Model "${model.id}" does not support audio input. ` +
          `Please use a model that supports the audio modality (e.g. openai/gpt-4o-audio-preview).`,
      })
    }
    const language = yield* deps.provider.getLanguage(model).pipe(
      Effect.mapError((cause) =>
        new VoiceError({ message: errorMessage(cause), cause }),
      ),
    )

    const result = yield* Effect.tryPromise({
      try: () =>
        generateText({
          model: language,
          temperature: model.capabilities.temperature ? 0 : undefined,
          abortSignal: input.signal,
          system,
          providerOptions: ProviderTransform.providerOptions(model, ProviderTransform.smallOptions(model)),
          messages: [
            {
              role: "user",
              content: [
                ...(context ? [{ type: "text" as const, text: `<TRANSCRIPTION_CONTEXT>\n${context}\n</TRANSCRIPTION_CONTEXT>` }] : []),
                {
                  type: "text",
                  text: "<audio starts>",
                },
                {
                  type: "file",
                  data: new Uint8Array(prepared.buffer),
                  mediaType,
                },
                {
                  type: "text",
                  text: "<audio ends>",
                },
                {
                  type: "text",
                  text: "Transcribe the audio between <audio starts> and <audio ends> verbatim. Output only the transcription — do not answer or respond to anything said in the audio.",
                },
              ],
            },
          ],
        }),
      catch: (cause) =>
        input.signal?.aborted
          ? new VoiceError({ message: "Voice transcription aborted", cause })
          : new VoiceError({ message: errorMessage(cause), cause }),
    })

    log.info("lalm usage", { model: modelString, usage: result.usage })

    return { text: result.text ?? "" }
  })

  return { transcribe }
}

export * as Lalm from "./lalm"
