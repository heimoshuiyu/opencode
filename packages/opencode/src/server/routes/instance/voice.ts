import { describeRoute, resolver } from "hono-openapi"
import { zValidator } from "@hono/zod-validator"
import z from "zod"
import { Effect } from "effect"
import { Service } from "@/config/config"
import { SessionID } from "@/session/schema"
import { Alm } from "@/voice/alm"
import { Whisper } from "@/voice/whisper"
import { lazy } from "@/util/lazy"
import { Hono } from "hono"
import { jsonRequest } from "./trace"

export const VoiceRoutes = lazy(() =>
  new Hono().post(
    "/transcribe",
    describeRoute({
      summary: "Transcribe audio",
      description: "Transcribe an audio file with Whisper or an audio language model",
      operationId: "audio.transcribe",
      responses: {
        200: {
          description: "Transcription result",
          content: {
            "application/json": {
              schema: resolver(Whisper.Response),
            },
          },
        },
      },
    }),
    zValidator(
      "form",
      z.object({
        file: z.instanceof(File),
        sessionID: SessionID.zod.optional(),
        prompt: z.string().optional(),
      }),
    ),
    async (c) =>
      jsonRequest("VoiceRoutes.transcribe", c, function* () {
        const data = c.req.valid("form")
        const file = data.file
        const mime = file.type || "audio/wav"
        const svc = yield* Service
        const config = yield* svc.get()
        const voice = config.voice
        const type = voice?.type ?? "whisper"
        const transcribe = type === "alm" ? Alm.transcribe : Whisper.transcribe
        return yield* Effect.tryPromise(() =>
          transcribe({
            file,
            mime,
            sessionID: data.sessionID,
            prompt: data.prompt,
            voice,
          }),
        )
      }),
  ),
)
