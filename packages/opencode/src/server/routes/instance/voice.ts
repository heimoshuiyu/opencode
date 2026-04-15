import { describeRoute, resolver, validator } from "hono-openapi"
import z from "zod"
import { Service } from "@/config/config"
import { Lalm } from "@/voice/lalm"
import { Whisper } from "@/voice/whisper"
import { SessionID } from "@/session/schema"
import { lazy } from "@/util/lazy"
import { Hono } from "hono"
import { jsonRequest } from "./trace"

const TranscribeRequest = z.object({
  audio: z.string(),
  mime: z.string().default("audio/mpeg"),
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
        const buffer = Buffer.from(data.audio, "base64")
        const blob = new Blob([buffer], { type: data.mime })
        const file = new File([blob], "audio.mp3", { type: data.mime })
        const svc = yield* Service
        const config = yield* svc.get()
        const voice = config.voice
        const type = voice?.type ?? "whisper"
        const doTranscribe = type === "lalm" ? Lalm.transcribe : Whisper.transcribe
        return yield* doTranscribe({
          file,
          mime: data.mime,
          sessionID: data.sessionID,
          prompt: data.prompt,
          voice,
        })
      }),
  ),
)
