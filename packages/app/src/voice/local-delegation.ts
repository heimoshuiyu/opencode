import type { SessionInfo } from "@opencode/client/promise"
import type { ServerCtx } from "@/runtime/server/runtime"
import { messageExcerpts } from "./messages"
import type { VoiceRecorder } from "./recorder"
import { clientSession, transcriptNotice } from "./spec"
import type { TranscriptTail } from "./tail"
import type { VoiceTransport } from "./transport"

/** Immutable dial-time selection, independent of the tab visible later in the call. */
export type VoiceBinding = {
  origin: Pick<SessionInfo, "id" | "title" | "location" | "model" | "agent">
  agent: string
  model: SessionInfo["model"]
  audio: boolean
}

export function createLocalDelegation(ctx: ServerCtx, binding: VoiceBinding, tail: TranscriptTail) {
  const origin = binding.origin
  const reuse =
    origin.agent === binding.agent &&
    origin.model?.providerID === binding.model?.providerID &&
    origin.model?.id === binding.model?.id &&
    origin.model?.variant === binding.model?.variant
  const seen = new Set<string>()
  const outbox: string[] = []
  let sessionID: string | undefined
  let admitted = Promise.resolve()
  let nextAppend = 0

  const unlisten = ctx.sdk.event.on("session.text.ended", ({ data }) => {
    if (data.sessionID !== sessionID) return
    const text = data.text.trim()
    if (text) outbox.push(text.slice(0, 800))
  })

  return {
    async session(signal: AbortSignal) {
      const page = await ctx.sdk.api.message
        .list({ sessionID: origin.id, order: "desc", limit: 30 }, { signal })
        .catch(() => undefined)
      signal.throwIfAborted()
      const history = messageExcerpts(page?.data.toReversed() ?? [])
        .filter((line) => line.role !== "synthetic" && line.text.trim())
        .slice(-8)
        .map((line) => `${line.role}: ${line.text.trim().replace(/\s*\n\s*/g, " ").slice(0, 300)}`)
        .join("\n")
      return clientSession(
        `Session ${JSON.stringify(origin.title ?? origin.id)} in ${JSON.stringify(origin.location.directory)}\n${history}`,
      )
    },
    receive(id: string, signal: AbortSignal, recorder?: VoiceRecorder) {
      if (seen.has(id)) return
      seen.add(id)
      // Live can announce a delegation just before the last input transcript delta. Capture once,
      // after settling: a second capture with this ID would return the original reservation.
      setTimeout(() => {
        if (signal.aborted) return
        const lines = tail.capture(id)
        const file = recorder?.take()
        if (!lines.some((line) => line.startsWith("user:") || line.startsWith("user (updated utterance):"))) {
          tail.release(id)
          return
        }
        // Serialize admission, not execution: a spoken correction can steer a still-running agent.
        admitted = admitted.then(async () => {
          signal.throwIfAborted()
          if (!sessionID) {
            const created = reuse ? undefined : ctx.data.session.create({
              location: origin.location,
              agent: binding.agent,
              model: binding.model,
            })
            if (created) await created.request
            sessionID = created?.id ?? origin.id
            signal.throwIfAborted()
            await ctx.sdk.api.session.synthetic(
              { sessionID, text: transcriptNotice, delivery: "steer", resume: false },
              { signal },
            ).catch(() => undefined)
          }
          const audio = await file
          signal.throwIfAborted()
          await ctx.data.session.prompt({
            sessionID,
            text: lines.join("\n"),
            delivery: "steer",
            files: audio ? [audio] : undefined,
          })
          tail.accept(id)
        }).catch((error: unknown) => {
          tail.release(id)
          if (signal.aborted) return
          console.warn("[voice] delegation failed", error)
          outbox.push("The voice delegate could not be reached.")
        })
      }, 300)
    },
    flush(transport: VoiceTransport, now: number) {
      const content = outbox[0]
      if (!content || now < nextAppend || !transport.canSend) return
      transport.send({ type: "session.commentary.append", delegation_id: null, content })
      outbox.shift()
      nextAppend = now + 1000
    },
    dispose: unlisten,
  }
}
