import { Effect, Schema } from "effect"
import { errorMessage } from "@/util/error"

export class VoiceError extends Schema.TaggedErrorClass<VoiceError>()("VoiceError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect),
}) {}

export type Error = VoiceError

export function is(input: unknown): input is VoiceError {
  return input instanceof VoiceError
}

export function message(input: unknown) {
  if (is(input)) return input.message
  return errorMessage(input)
}

export function abortable<A, E, R>(effect: Effect.Effect<A, E, R>, signal?: AbortSignal) {
  if (!signal) return effect
  return effect.pipe(
    Effect.raceFirst(
      Effect.callback<never, VoiceError>((resume) => {
        if (signal.aborted) {
          resume(Effect.fail(new VoiceError({ message: "Voice transcription aborted" })))
          return
        }
        const abort = () => resume(Effect.fail(new VoiceError({ message: "Voice transcription aborted" })))
        signal.addEventListener("abort", abort, { once: true })
        return Effect.sync(() => signal.removeEventListener("abort", abort))
      }),
    ),
  )
}


