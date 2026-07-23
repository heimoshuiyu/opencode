import { Session } from "@opencode/schema/session"
import { optional } from "@opencode/schema/schema"
import { Location } from "@opencode/schema/location"
import { Voice } from "@opencode/schema/voice"
import { Schema } from "effect"
import { HttpApiEndpoint, HttpApiGroup, OpenApi } from "effect/unstable/httpapi"
import { InvalidRequestError, ServiceUnavailableError, SessionNotFoundError, UnknownError } from "../errors.js"
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
  voice: Voice.Settings.pipe(optional).annotate({ description: "Voice transcription settings override" }),
}).annotate({ identifier: "VoiceTranscribeRequest" })

export const TranscribeResponse = Schema.Struct({
  text: Schema.String,
  usage: optional(
    Schema.Struct({
      input_tokens: Schema.Finite.pipe(optional),
      output_tokens: Schema.Finite.pipe(optional),
    }).annotate({ identifier: "Voice.Usage" }),
  ),
}).annotate({ identifier: "VoiceTranscribeResponse" })

export const VoiceGroup = HttpApiGroup.make("server.voice")
  .add(
    HttpApiEndpoint.post("voice.transcribe", "/api/voice/transcribe", {
      query: LocationQuery,
      payload: TranscribeRequest,
      success: Location.response(TranscribeResponse),
      error: [InvalidRequestError, ServiceUnavailableError, SessionNotFoundError, UnknownError],
    })
      .annotateMerge(locationQueryOpenApi)
      .annotateMerge(
        OpenApi.annotations({
          identifier: "v2.voice.transcribe",
          summary: "Transcribe audio",
          description:
            "Transcribe base64-encoded audio data using Whisper or a Large Audio Language Model (Gemini, OpenAI Responses, or OpenAI-compatible). Builds optional conversation context from the requested session.",
        }),
      ),
  )
  .annotateMerge(
    OpenApi.annotations({
      title: "voice",
      description: "Audio transcription routes.",
    }),
  )
