import { Location } from "@opencode/core/location"
import { Session } from "@opencode/core/session"
import { SessionMessage } from "@opencode/core/session/message"
import { Voice } from "@opencode/core/voice/index"
import {
  InvalidRequestError,
  ServiceUnavailableError,
  SessionNotFoundError,
  UnknownError,
} from "@opencode/protocol/errors"
import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { Api } from "../api"
import { response } from "../location"

const toHttpError = (error: Voice.Error) => {
  if (error._tag === "Voice.UnavailableError") {
    return new ServiceUnavailableError({ message: error.message })
  }
  return new InvalidRequestError({ message: error.message })
}

const sessionNotFound = (error: Session.NotFoundError) =>
  new SessionNotFoundError({
    sessionID: error.sessionID,
    message: `Session not found: ${error.sessionID}`,
  })

const messageDecodeError = (error: Session.MessageDecodeError) => {
  const ref = `err_${crypto.randomUUID().slice(0, 8)}`
  return Effect.logError("failed to decode voice context session message").pipe(
    Effect.annotateLogs({ ref, sessionID: error.sessionID, messageID: error.messageID }),
    Effect.andThen(
      Effect.fail(new UnknownError({ message: "Unexpected server error. Check server logs for details.", ref })),
    ),
  )
}

const PAGE_SIZE = 20

const assistantText = (msg: SessionMessage.Info) =>
  msg.type === "assistant"
    ? msg.content
        .filter((part): part is SessionMessage.AssistantText => part.type === "text")
        .map((part) => part.text)
        .join(" ")
        .trim()
    : undefined

const buildConversationContext = Effect.fn("VoiceHandler.buildConversationContext")(function* (input: {
  sessions: Session.Interface
  sessionID: Session.ID
  limit: number
}) {
  const pairs: Array<{ user: string; assistant?: string }> = []
  let pendingAssistant: string | undefined
  let cursor: { id: SessionMessage.ID; direction: "next" } | undefined

  while (pairs.length < input.limit) {
    const messages = yield* input.sessions.messages({
      sessionID: input.sessionID,
      order: "desc",
      limit: PAGE_SIZE,
      ...(cursor ? { cursor } : {}),
    })
    if (messages.length === 0) break

    for (const msg of messages) {
      if (msg.type === "assistant" && pendingAssistant === undefined) {
        const text = assistantText(msg)
        if (text) pendingAssistant = text
      }
      if (msg.type === "user") {
        const text = (msg.text ?? "").trim()
        if (!text) continue
        pairs.push({ user: text, assistant: pendingAssistant })
        pendingAssistant = undefined
        if (pairs.length >= input.limit) break
      }
    }

    cursor = { id: messages.at(-1)!.id, direction: "next" }
  }

  if (pendingAssistant !== undefined && pairs.length < input.limit) {
    pairs.push({ user: "", assistant: pendingAssistant })
  }
  return pairs
    .reverse()
    .flatMap((pair) => [
      pair.user ? `User: ${pair.user}` : undefined,
      pair.assistant ? `Assistant: ${pair.assistant}` : undefined,
    ])
    .filter((line): line is string => line !== undefined)
    .join("\n")
})

const buildPrompt = Effect.fn("VoiceHandler.buildPrompt")(function* (input: {
  sessions: Session.Interface
  contextSession?: Session.Info
  extraPrompt?: string
  pairs: number
}) {
  const location = yield* Location.Service
  const parts: string[] = []
  if (location.directory) parts.push(`directory: ${location.directory}`)
  if (input.contextSession) {
    const context = yield* buildConversationContext({
      sessions: input.sessions,
      sessionID: input.contextSession.id,
      limit: input.pairs,
    })
    if (context) parts.push(context)
  }
  if (input.extraPrompt?.trim()) parts.push(input.extraPrompt)
  return parts.filter((line) => line.trim()).join("\n")
})

export const VoiceHandler = HttpApiBuilder.group(Api, "server.voice", (handlers) =>
  Effect.gen(function* () {
    const sessions = yield* Session.Service
    return handlers.handle(
      "voice.transcribe",
      Effect.fn("VoiceHandler.transcribe")(function* (ctx) {
        const voice = yield* Voice.Service
        const audio = ctx.payload.audio.trim()
        if (!audio) {
          return yield* new InvalidRequestError({ message: "Audio data is empty" })
        }
        if (!ctx.payload.mime.startsWith("audio/")) {
          return yield* new InvalidRequestError({ message: "Voice input MIME type must start with audio/" })
        }
        const audioBuffer = Buffer.from(audio, "base64")
        if (!audioBuffer.byteLength) {
          return yield* new InvalidRequestError({ message: "Voice audio is empty" })
        }

        const settings = yield* voice.resolve(ctx.payload.voice).pipe(Effect.mapError(toHttpError))
        const contextSession = ctx.payload.contextSessionID
          ? yield* sessions.get(ctx.payload.contextSessionID).pipe(Effect.mapError(sessionNotFound))
          : undefined
        const prompt = yield* buildPrompt({
          sessions,
          contextSession,
          extraPrompt: ctx.payload.prompt,
          pairs: settings.context_pairs,
        }).pipe(
          Effect.catchTag("Session.NotFoundError", (error) => Effect.fail(sessionNotFound(error))),
          Effect.catchTag("Session.MessageDecodeError", messageDecodeError),
        )

        const result = yield* voice
          .transcribe(
            {
              audio: new Uint8Array(audioBuffer),
              mime: ctx.payload.mime,
              prompt,
              images: ctx.payload.images ? [...ctx.payload.images] : undefined,
            },
            settings,
          )
          .pipe(Effect.mapError(toHttpError))

        return yield* response(
          Effect.succeed({
            text: result.text,
            usage: result.usage
              ? { input_tokens: result.usage.inputTokens, output_tokens: result.usage.outputTokens }
              : undefined,
          }),
        )
      }),
    )
  }),
)
