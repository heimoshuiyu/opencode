import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { HttpServerRequest } from "effect/unstable/http"
import { InstanceHttpApi } from "../api"
import { Voice } from "@/voice"
import { TranscribeRequest } from "../groups/audio"
import { VoiceError } from "@/voice/error"
import { ApiVoiceTranscriptionError } from "../groups/audio"

export const audioHandlers = HttpApiBuilder.group(InstanceHttpApi, "audio", (handlers) =>
  Effect.gen(function* () {
    const voice = yield* Voice.Service

    const transcribe = Effect.fn("AudioHttpApi.transcribe")(function* (ctx: {
      payload: typeof TranscribeRequest.Type
    }) {
      const { audio, mime, sessionID, prompt } = ctx.payload
      const buffer = new Uint8Array(Buffer.from(audio, "base64"))
      const blob = new Blob([buffer], { type: mime })
      const file = new File([blob], "audio.mp3", { type: mime })
      const request = yield* HttpServerRequest.HttpServerRequest
      const signal = request.source instanceof Request ? request.source.signal : undefined
      return yield* voice.transcribe({ file, mime, sessionID, prompt, signal }).pipe(
        Effect.mapError((error) => new ApiVoiceTranscriptionError(VoiceError.toObject(error))),
      )
    })

    return handlers.handle("transcribe", transcribe)
  }),
)
