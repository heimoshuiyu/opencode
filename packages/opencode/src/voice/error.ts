import { Effect, Schema } from "effect"
import { errorMessage } from "@/util/error"

export class ConfigurationError extends Schema.TaggedErrorClass<ConfigurationError>()("VoiceConfigurationError", {
  message: Schema.String,
}) {}

export class ConversionError extends Schema.TaggedErrorClass<ConversionError>()("VoiceConversionError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect),
}) {}

export class FileError extends Schema.TaggedErrorClass<FileError>()("VoiceFileError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect),
}) {}

export class TranscriptionError extends Schema.TaggedErrorClass<TranscriptionError>()("VoiceTranscriptionError", {
  message: Schema.String,
  status: Schema.optional(Schema.Number),
  body: Schema.optional(Schema.String),
  cause: Schema.optional(Schema.Defect),
}) {}

export class ResponseError extends Schema.TaggedErrorClass<ResponseError>()("VoiceResponseError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect),
}) {}

export class UnsupportedModelError extends Schema.TaggedErrorClass<UnsupportedModelError>()("VoiceUnsupportedModelError", {
  message: Schema.String,
  model: Schema.String,
}) {}

export class AbortedError extends Schema.TaggedErrorClass<AbortedError>()("VoiceAbortedError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect),
}) {}

export type Error =
  | ConfigurationError
  | ConversionError
  | FileError
  | TranscriptionError
  | ResponseError
  | UnsupportedModelError
  | AbortedError

export const PublicErrorName = "VoiceTranscriptionError"

export function is(input: unknown): input is Error {
  return (
    input instanceof ConfigurationError ||
    input instanceof ConversionError ||
    input instanceof FileError ||
    input instanceof TranscriptionError ||
    input instanceof ResponseError ||
    input instanceof UnsupportedModelError ||
    input instanceof AbortedError
  )
}

export function message(input: unknown) {
  if (is(input)) return input.message
  return errorMessage(input)
}

export function toObject(input: unknown) {
  return {
    name: PublicErrorName,
    data: {
      message: message(input),
    },
  } as const
}

export function abortable<A, E, R>(effect: Effect.Effect<A, E, R>, signal?: AbortSignal) {
  if (!signal) return effect
  return effect.pipe(
    Effect.raceFirst(
      Effect.callback<never, AbortedError>((resume) => {
        if (signal.aborted) {
          resume(Effect.fail(new AbortedError({ message: "Voice transcription aborted" })))
          return
        }
        const abort = () => resume(Effect.fail(new AbortedError({ message: "Voice transcription aborted" })))
        signal.addEventListener("abort", abort, { once: true })
        return Effect.sync(() => signal.removeEventListener("abort", abort))
      }),
    ),
  )
}

export * as VoiceError from "./error"
