import { expect, test } from "bun:test"
import { transitionVoice, type VoiceState } from "./voice-machine"

test("a second press before microphone permission cancels the take and ignores a late grant", () => {
  const attempt = new AbortController()
  const start = { type: "start", attempt } as const
  const pending = transitionVoice({ type: "idle" }, start)
  const stopped = transitionVoice(pending, { type: "stop" })
  expect(stopped.type).toBe("idle")
  const next = transitionVoice(stopped, { ...start, attempt: new AbortController() })
  const late = { type: "ready", attempt, stop: () => {} } as const
  expect(transitionVoice(next, late)).toBe(next)
  expect(transitionVoice(transitionVoice(next, { type: "dispose" }), late).type).toBe("disposed")
})

test("transcription retry keeps the take; a cancelled result cannot settle the next attempt", () => {
  const attempt = new AbortController()
  const blob = new Blob(["audio"])
  const finishing: VoiceState = { type: "finishing", attempt }
  const pending = transitionVoice(finishing, { type: "stopped", attempt, blob })
  const retry = transitionVoice(pending, { type: "failed", attempt, message: "offline" })
  expect(retry).toMatchObject({ type: "retry", blob, message: "offline" })
  const again = new AbortController()
  const next = transitionVoice(retry, { type: "retry", attempt: again })
  expect(transitionVoice(next, { type: "complete", attempt })).toBe(next)
  expect(transitionVoice(next, { type: "complete", attempt: again }).type).toBe("idle")
  expect(transitionVoice(next, { type: "cancel" }).type).toBe("idle")
})
