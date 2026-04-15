import { Effect, Schema } from "effect"
import { Config, type Info } from "@/config/config"
import { VoiceCommon } from "@/voice/common"
import * as Log from "@opencode-ai/core/util/log"
import { SessionID } from "@/session/schema"
import { errorMessage } from "@/util/error"
import { HttpClient, HttpClientRequest, HttpClientResponse } from "effect/unstable/http"
import { VoiceError } from "@/voice/error"

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
    sessionID?: SessionID
    prompt?: string
    signal?: AbortSignal
    voice?: Info["voice"]
  }) {
    const config = yield* deps.config.get()
    const voice = input.voice ?? config.voice
    const whisper = voice?.whisper
    const apiKey = whisper?.apiKey
    if (!apiKey) {
      return yield* new VoiceError.ConfigurationError({ message: "Missing voice.whisper.apiKey" })
    }

    const prepared = yield* common.prepareAudio(input.file, input.mime)
    const prompt = yield* common.buildTranscriptionContext({
      sessionID: input.sessionID,
      userPrompt: input.prompt,
    })

    const form = new FormData()
    const audioBytes = new Uint8Array(prepared.buffer.byteLength)
    audioBytes.set(new Uint8Array(prepared.buffer))
    form.append("file", new Blob([audioBytes], { type: prepared.mime }), prepared.name)
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

    const result = yield* VoiceError.abortable(
      deps.http
        .execute(
          HttpClientRequest.post(url).pipe(
            HttpClientRequest.bearerToken(apiKey),
            HttpClientRequest.bodyFormData(form),
          ),
        )
        .pipe(
          Effect.mapError((cause) =>
            new VoiceError.TranscriptionError({ message: errorMessage(cause), cause }),
          ),
        ),
      input.signal,
    )

    if (result.status < 200 || result.status >= 300) {
      const body = yield* result.text.pipe(Effect.catch(() => Effect.succeed("")))
      return yield* new VoiceError.TranscriptionError({
        message: body || `Whisper request failed (${result.status})`,
        status: result.status,
        body,
      })
    }

    log.debug("whisper response", { contentType: result.headers["content-type"] ?? "" })
    const payload = yield* HttpClientResponse.schemaBodyJson(WhisperResponse)(result).pipe(
      Effect.mapError((cause) =>
        new VoiceError.ResponseError({
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
