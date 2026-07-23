import { createSignal, onCleanup } from "solid-js"
import type { Recorder } from "../util/voice"
import { errorMessage } from "../util/error"

type Audio = ReturnType<Recorder["stop"]>
type Result = { audio: Audio } | { text: string }
type State =
  | { type: "idle" }
  | { type: "recording"; recorder: Recorder; since: number }
  | { type: "transcribing"; controller: AbortController }
  | { type: "retry"; result: Result }

/** Owned by the TUI, not one prompt. The currently open prompt supplies context and receives the result. */
export function createVoiceInput(input: {
  record(): Recorder
  insert(text: string): boolean
  transcribe(audio: Audio, signal: AbortSignal): Promise<string>
  cue(kind: "start" | "stop"): void
  notify(message: string, variant: "warning" | "error"): void
}) {
  const [state, setState] = createSignal<State>({ type: "idle" })

  async function deliver(result: Result): Promise<void> {
    const controller = new AbortController()
    try {
      if ("audio" in result) {
        setState({ type: "transcribing", controller })
        const text = (await input.transcribe(result.audio, controller.signal)).trim()
        if (controller.signal.aborted) return
        if (!text) throw new Error("Transcription returned empty text")
        // A closed prompt can retry insertion without paying for transcription again.
        result = { text }
      }
      const placed = input.insert(result.text)
      setState(placed ? { type: "idle" } : { type: "retry", result })
      if (!placed) input.notify("No prompt open — choose Retry to insert the text", "warning")
    } catch (error) {
      if (controller.signal.aborted) return
      setState({ type: "retry", result })
      input.notify(errorMessage(error), "error")
    }
  }

  function cancel() {
    const s = state()
    if (s.type === "transcribing") s.controller.abort()
    if (s.type === "recording") s.recorder.kill()
    setState({ type: "idle" })
  }
  onCleanup(cancel)

  return {
    state,
    cancel,
    async toggle() {
      const s = state()
      try {
        if (s.type === "transcribing") return cancel()
        if (s.type === "retry") return deliver(s.result)
        if (s.type === "recording") {
          const audio = s.recorder.stop()
          input.cue("stop")
          return deliver({ audio })
        }
        const recorder = input.record()
        setState({ type: "recording", recorder, since: Date.now() })
        input.cue("start")
      } catch (error) {
        setState({ type: "idle" })
        input.notify(errorMessage(error), "error")
      }
    },
  }
}
