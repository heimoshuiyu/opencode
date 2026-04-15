import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { HttpServerRequest } from "effect/unstable/http"
import { InstanceHttpApi } from "../api"
import { Voice } from "@/voice"
import { AudioApiError, TranscribeRequest } from "../groups/audio"

export const audioHandlers = HttpApiBuilder.group(InstanceHttpApi, "audio", (handlers) =>
  Effect.gen(function* () {
    const voice = yield* Voice.Service

    const transcribe = Effect.fn("AudioHttpApi.transcribe")(function* (ctx: {
      payload: typeof TranscribeRequest.Type
    }) {
      const buffer = new Uint8Array(Buffer.from(ctx.payload.audio, "base64"))
      const blob = new Blob([buffer], { type: ctx.payload.mime })
      const file = new File([blob], "audio.mp3", { type: ctx.payload.mime })
      const request = yield* HttpServerRequest.HttpServerRequest
      const signal = request.source instanceof Request ? request.source.signal : undefined
      return yield* voice.transcribe({ file, mime: ctx.payload.mime, prompt: ctx.payload.prompt, signal }).pipe(
        Effect.mapError((error) =>
          new AudioApiError({ name: "AudioError", data: { message: error.message } }),
        ),
      )
    })

    return handlers.handle("transcribe", transcribe)
  }),
)
