import { describeRoute, resolver, validator } from "hono-openapi"
import z from "zod"
import { Voice } from "@/voice"
import { VoiceError } from "@/voice/error"
import { SessionID } from "@/session/schema"
import { lazy } from "@/util/lazy"
import { Hono } from "hono"
import { jsonRequest } from "./trace"
import { Effect } from "effect"

const TranscribeRequest = z.object({
  audio: z.string(),
  mime: z.string(),
  sessionID: SessionID.zod.optional(),
  prompt: z.string().optional(),
})

const TranscribeResponse = z.object({
  text: z.string(),
})

export const VoiceRoutes = lazy(() =>
  new Hono().post(
    "/transcribe",
    describeRoute({
      summary: "Transcribe audio",
      description: "Transcribe base64-encoded audio data with Whisper or an audio language model",
      operationId: "audio.transcribe",
      responses: {
        200: {
          description: "Transcription result",
          content: {
            "application/json": {
              schema: resolver(TranscribeResponse),
            },
          },
        },
      },
    }),
    validator("json", TranscribeRequest),
    async (c) =>
      jsonRequest("VoiceRoutes.transcribe", c, function* () {
        const data = c.req.valid("json")
        const buffer = new Uint8Array(Buffer.from(data.audio, "base64"))
        const blob = new Blob([buffer], { type: data.mime })
        const file = new File([blob], "audio.mp3", { type: data.mime })
        const svc = yield* Voice.Service
        return yield* svc.transcribe({
          file,
          mime: data.mime,
          sessionID: data.sessionID,
          prompt: data.prompt,
          signal: c.req.raw.signal,
        }).pipe(
          Effect.mapError((error) => new VoiceError.TranscriptionError({ message: VoiceError.message(error) })),
        )
      }),
  ),
)
