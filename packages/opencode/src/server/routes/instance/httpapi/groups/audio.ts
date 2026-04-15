import { Schema } from "effect"
import { HttpApi, HttpApiEndpoint, HttpApiGroup, OpenApi } from "effect/unstable/httpapi"
import { Authorization } from "../middleware/authorization"
import { InstanceContextMiddleware } from "../middleware/instance-context"
import { WorkspaceRoutingMiddleware } from "../middleware/workspace-routing"
import { described } from "./metadata"
import { SessionID } from "@/session/schema"
import { VoiceError } from "@/voice/error"

export const TranscribeRequest = Schema.Struct({
  audio: Schema.String,
  mime: Schema.String,
  sessionID: Schema.optional(SessionID),
  prompt: Schema.optional(Schema.String),
})

const TranscribeResponse = Schema.Struct({
  text: Schema.String,
})

export class ApiVoiceTranscriptionError extends Schema.ErrorClass<ApiVoiceTranscriptionError>(VoiceError.PublicErrorName)(
  {
    name: Schema.Literal(VoiceError.PublicErrorName),
    data: Schema.Struct({
      message: Schema.String,
    }),
  },
  { httpApiStatus: 400 },
) {}

export const AudioApi = HttpApi.make("audio")
  .add(
    HttpApiGroup.make("audio")
      .add(
        HttpApiEndpoint.post("transcribe", "/voice/transcribe", {
          payload: TranscribeRequest,
          success: described(TranscribeResponse, "Transcription result"),
          error: ApiVoiceTranscriptionError,
        }).annotateMerge(
          OpenApi.annotations({
            identifier: "audio.transcribe",
            summary: "Transcribe audio",
            description:
              "Transcribe base64-encoded audio data with Whisper or an audio language model",
          }),
        ),
      )
      .annotateMerge(
        OpenApi.annotations({
          title: "audio",
          description: "Audio transcription routes.",
        }),
      )
      .middleware(InstanceContextMiddleware)
      .middleware(WorkspaceRoutingMiddleware)
      .middleware(Authorization),
  )
  .annotateMerge(
    OpenApi.annotations({
      title: "opencode HttpApi",
      version: "0.0.1",
      description: "Effect HttpApi surface for audio routes.",
    }),
  )
