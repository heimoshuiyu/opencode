import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { HttpServerRequest } from "effect/unstable/http"
import { InstanceHttpApi } from "../api"
import { Voice } from "@/voice"
import { AudioApiError, TranscribeRequest } from "../groups/audio"
import type { Info } from "@/config/config"
import { Config } from "@/config/config"
import { Database } from "@opencode-ai/core/database/database"
import { Vcs } from "@/project/vcs"
import { WorkspaceRouteContext } from "../middleware/workspace-routing"
import { MessageV2 } from "@/session/message-v2"
import { SessionID } from "@/session/schema"

const toVoiceOverride = (
  payload: typeof TranscribeRequest.Type,
  serverVoice: Info["voice"],
): Info["voice"] | undefined => {
  const v = payload.voice
  if (!v) return undefined
  return {
    ...serverVoice,
    ...v.type && { type: v.type },
    ...v.hot_words && { hot_words: v.hot_words },
    ...v.whisper && {
      whisper: {
        ...serverVoice?.whisper,
        ...v.whisper,
      },
    },
    ...v.lalm && {
      lalm: {
        ...serverVoice?.lalm,
        ...v.lalm.model && { model: `${v.lalm.model.providerID}/${v.lalm.model.modelID}` },
        ...v.lalm.system && { system: v.lalm.system },
        ...v.lalm.instruction && { instruction: v.lalm.instruction },
        ...v.lalm.audio_input_format && { audio_input_format: v.lalm.audio_input_format },
      },
    },
  }
}

const buildConversationContext = Effect.fn("AudioHttpApi.buildConversationContext")(function* (input: {
  sessionID: SessionID
  limit: number
}) {
  const database = yield* Database.Service
  const pairs: Array<{ user: string; assistant?: string }> = []
  let pendingAssistant: string | undefined
  let before: string | undefined
  const size = 50
  while (pairs.length < input.limit) {
    const page = yield* MessageV2.page({ sessionID: input.sessionID, limit: size, before }).pipe(
      Effect.provideService(Database.Service, database),
    )
    if (page.items.length === 0) break
    for (let i = page.items.length - 1; i >= 0; i--) {
      const msg = page.items[i]
      if (msg.info.role === "assistant" && pendingAssistant === undefined && !msg.info.summary) {
        const text = msg.parts
          .filter((p): p is MessageV2.TextPart => p.type === "text")
          .map((p) => p.text)
          .join(" ")
          .trim()
        if (text) pendingAssistant = text
      }
      if (msg.info.role === "user") {
        const text = msg.parts
          .filter((p): p is MessageV2.TextPart => p.type === "text" && !p.synthetic)
          .map((p) => p.text)
          .join(" ")
          .trim()
        if (!text) continue
        pairs.push({ user: text, assistant: pendingAssistant })
        pendingAssistant = undefined
        if (pairs.length >= input.limit) break
      }
    }
    if (pairs.length >= input.limit) break
    if (!page.more || !page.cursor) break
    before = page.cursor
  }
  if (pendingAssistant !== undefined && pairs.length < input.limit) {
    pairs.push({ user: "", assistant: pendingAssistant })
  }
  return pairs
    .reverse()
    .flatMap((p) => [
      p.user ? `User: ${p.user}` : undefined,
      p.assistant ? `Assistant: ${p.assistant}` : undefined,
    ])
    .filter((s): s is string => s !== undefined)
    .join("\n")
})

const buildPrompt = Effect.fn("AudioHttpApi.buildPrompt")(function* (input: {
  sessionID?: SessionID
  extraPrompt?: string
}) {
  const route = yield* WorkspaceRouteContext
  const vcs = yield* Vcs.Service

  const parts: string[] = []

  if (route.directory) parts.push(`directory: ${route.directory}`)
  const branch = yield* vcs.branch().pipe(Effect.catch(() => Effect.succeed(undefined)))
  if (branch) parts.push(`branch: ${branch}`)

  if (input.sessionID) {
    const config = yield* Config.Service
    const cfg = yield* config.get()
    const limit = cfg.voice?.context_pairs ?? 3
    const context = yield* buildConversationContext({ sessionID: input.sessionID, limit })
    if (context) parts.push(context)
  }

  if (input.extraPrompt?.trim()) parts.push(input.extraPrompt)

  return parts.filter((s) => s.trim()).join("\n")
})

export const audioHandlers = HttpApiBuilder.group(InstanceHttpApi, "audio", (handlers) =>
  Effect.gen(function* () {
    const voice = yield* Voice.Service
    const config = yield* Config.Service

    const transcribe = Effect.fn("AudioHttpApi.transcribe")(function* (ctx: {
      payload: typeof TranscribeRequest.Type
    }) {
      const buffer = new Uint8Array(Buffer.from(ctx.payload.audio, "base64"))
      const blob = new Blob([buffer], { type: ctx.payload.mime })
      const file = new File([blob], "audio.mp3", { type: ctx.payload.mime })
      const request = yield* HttpServerRequest.HttpServerRequest
      const signal = request.source instanceof Request ? request.source.signal : undefined

      const prompt = yield* buildPrompt({
        sessionID: ctx.payload.sessionID,
        extraPrompt: ctx.payload.prompt,
      }).pipe(
        Effect.mapError((cause) =>
          new AudioApiError({ name: "AudioError", data: { message: cause.message } }),
        ),
      )

      const cfg = yield* config.get()

      const images = ctx.payload.images?.length ? [...ctx.payload.images] : undefined

      return yield* voice.transcribe({
        file,
        mime: ctx.payload.mime,
        prompt,
        signal,
        images,
        voice: toVoiceOverride(ctx.payload, cfg.voice),
      }).pipe(
        Effect.mapError((error) =>
          new AudioApiError({ name: "AudioError", data: { message: error.message } }),
        ),
      )
    })

    return handlers.handle("transcribe", transcribe)
  }),
)
