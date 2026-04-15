import { Effect, Schema } from "effect"
import { Config, type Info } from "@/config/config"
import { VoiceCommon } from "@/voice/common"
import { VoiceConfig } from "@/voice/config"
import * as Log from "@opencode-ai/core/util/log"
import { errorMessage } from "@/util/error"
import { HttpClient, HttpClientRequest, HttpClientResponse } from "effect/unstable/http"
import { VoiceError, abortable } from "@/voice/error"

const log = Log.create({ service: "voice.whisper" })

const WhisperResponse = Schema.Struct({
  text: Schema.optional(Schema.String),
})

export interface Deps extends VoiceCommon.Deps {
  config: Config.Interface
  http: HttpClient.HttpClient
}

export const make = (deps: Deps) => {
  const common = VoiceCommon.make(deps)

  const transcribe = Effect.fn("Whisper.transcribe")(function* (input: {
    file: File
    mime: string
    prompt?: string
    signal?: AbortSignal
    voice?: Info["voice"]
  }) {
    const config = yield* deps.config.get()
    const voice = input.voice ?? config.voice
    const whisper = VoiceConfig.whisper(voice)
    if (!whisper.ok) return yield* new VoiceError({ message: whisper.message })

    const prepared = yield* common.prepareAudio(input.file, input.mime)
    const prompt = input.prompt?.trim() ?? ""

    const form = new FormData()
    const audioBytes = new Uint8Array(prepared.buffer.byteLength)
    audioBytes.set(new Uint8Array(prepared.buffer))
    form.append("file", new Blob([audioBytes], { type: prepared.mime }), prepared.name)
    form.append("model", whisper.config.model ?? "whisper-1")
    form.append("response_format", "json")
    if (whisper.config.language) {
      form.append("language", whisper.config.language)
    }
    if (prompt) {
      form.append("prompt", prompt)
    }

    const url = whisper.config.url ?? "https://api.openai.com/v1/audio/transcriptions"
    log.debug("whisper request", {
      url,
      model: whisper.config.model ?? "whisper-1",
      bytes: prepared.buffer.byteLength,
    })

    const result = yield* abortable(
      deps.http
        .execute(
          HttpClientRequest.post(url).pipe(
            HttpClientRequest.bearerToken(whisper.config.apiKey),
            HttpClientRequest.bodyFormData(form),
          ),
        )
        .pipe(
          Effect.mapError((cause) =>
            new VoiceError({ message: errorMessage(cause), cause }),
          ),
        ),
      input.signal,
    )

    if (result.status < 200 || result.status >= 300) {
      const body = yield* result.text.pipe(Effect.catch(() => Effect.succeed("")))
      return yield* new VoiceError({
        message: body || `Whisper request failed (${result.status})`,
      })
    }

    log.debug("whisper response", { contentType: result.headers["content-type"] ?? "" })
    const payload = yield* HttpClientResponse.schemaBodyJson(WhisperResponse)(result).pipe(
      Effect.mapError((cause) =>
        new VoiceError({
          message: "Failed to decode Whisper transcription response",
          cause,
        }),
      ),
    )
    return { text: payload.text ?? "" }
  })

  return { transcribe }
}

export * as Whisper from "./whisper"
