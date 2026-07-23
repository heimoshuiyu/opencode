import { Session } from "@opencode/schema/session"
import { Location } from "@opencode/schema/location"
import { optional } from "@opencode/schema/schema"
import { Transcription } from "@opencode/schema/transcription"
import { Context, Schema } from "effect"
import { HttpApiEndpoint, HttpApiGroup, HttpApiMiddleware, OpenApi } from "effect/http-api"
import {
  InvalidRequestError,
  ServiceUnavailableError,
  SessionNotFoundError,
  UnknownError,
} from "../errors.js"
import { LocationQuery, locationQueryOpenApi } from "./location.js"

export const TranscribeRequest = Schema.Struct({
  audio: Schema.String.annotate({
    description: "Base64-encoded audio data to transcribe",
  }),
  mime: Schema.String.annotate({ description: "MIME type of the audio data, e.g. audio/webm or audio/wav" }),
  prompt: Schema.String.pipe(optional).annotate({
    description: "Extra prompt text (e.g. current input box content) appended after server-built context",
  }),
  contextSessionID: Session.ID.pipe(optional).annotate({
    description: "Session ID to use only as recent conversation context for transcription",
  }),
  images: Schema.Array(Schema.String).pipe(optional).annotate({
    description: "Images to provide visual context for transcription, as data URLs (data:image/...;base64,...)",
  }),
  settings: Transcription.Settings.pipe(optional).annotate({
    description: "Transcription settings override",
  }),
}).annotate({ identifier: "TranscriptionTranscribeRequest" })

export const TranscribeResponse = Schema.Struct({
  text: Schema.String,
  usage: optional(
    Schema.Struct({
      input_tokens: Schema.Finite.pipe(optional),
      output_tokens: Schema.Finite.pipe(optional),
    }).annotate({ identifier: "Transcription.Usage" }),
  ),
}).annotate({ identifier: "TranscriptionTranscribeResponse" })

// Transcription only: the server resolves the config location per request, so
// the group is a factory that takes the location middleware.
export const makeTranscriptionGroup = <LocationId extends HttpApiMiddleware.AnyId, LocationService>(
  locationMiddleware: Context.Key<LocationId, LocationService>,
) =>
  HttpApiGroup.make("server.transcription")
    .add(
      HttpApiEndpoint.post("transcribe", "/api/transcription", {
        query: LocationQuery,
        payload: TranscribeRequest,
        success: Location.response(TranscribeResponse),
        error: [InvalidRequestError, ServiceUnavailableError, SessionNotFoundError, UnknownError],
      })
        .middleware(locationMiddleware)
        .annotateMerge(locationQueryOpenApi)
        .annotateMerge(
          OpenApi.annotations({
            identifier: "v2.transcription.transcribe",
            summary: "Transcribe audio",
            description:
              "Transcribe base64-encoded audio data using Whisper or a Large Audio Language Model (Gemini, OpenAI Responses, or OpenAI-compatible). Builds optional conversation context from the requested session.",
          }),
        ),
    )
    .annotateMerge(
      OpenApi.annotations({
        title: "transcription",
        description: "Audio transcription routes.",
      }),
    )
