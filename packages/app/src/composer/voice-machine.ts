export type VoiceState =
  | { type: "idle" | "disposed" }
  | { type: "opening"; attempt: AbortController }
  | { type: "recording"; attempt: AbortController; stop: () => void; meter?: AnalyserNode }
  | { type: "finishing"; attempt: AbortController }
  | { type: "transcribing"; attempt: AbortController; blob: Blob }
  | { type: "retry"; blob: Blob; message: string }

export type VoiceEvent =
  | { type: "start"; attempt: AbortController }
  | { type: "ready"; attempt: AbortController; stop: () => void; meter?: AnalyserNode }
  | { type: "stop" }
  | { type: "stopped"; attempt: AbortController; blob: Blob }
  | { type: "retry"; attempt: AbortController }
  | { type: "failed"; attempt: AbortController; message: string }
  | { type: "complete"; attempt: AbortController }
  | { type: "cancel" | "dispose" }

export function transitionVoice(state: VoiceState, event: VoiceEvent): VoiceState {
  if (state.type === "disposed") return state

  switch (event.type) {
    case "dispose":
      return { type: "disposed" }
    case "cancel":
      return { type: "idle" }
    case "start":
      return state.type === "idle" ? { ...event, type: "opening" } : state
    case "ready":
      return state.type === "opening" && state.attempt === event.attempt
        ? { ...state, type: "recording", stop: event.stop, meter: event.meter }
        : state
    case "stop":
      if (state.type === "opening") return { type: "idle" }

      return state.type === "recording" ? { type: "finishing", attempt: state.attempt } : state
    case "stopped":
      if (state.type !== "finishing" || state.attempt !== event.attempt) return state

      if (!event.blob.size) return { type: "idle" }

      return { type: "transcribing", attempt: state.attempt, blob: event.blob }
    case "retry":
      return state.type === "retry" ? { type: "transcribing", attempt: event.attempt, blob: state.blob } : state
    case "failed":
      if (!("attempt" in state) || state.attempt !== event.attempt) return state

      return state.type === "transcribing"
        ? { type: "retry", blob: state.blob, message: event.message }
        : { type: "idle" }
    case "complete":
      return state.type === "transcribing" && state.attempt === event.attempt ? { type: "idle" } : state
  }
}

export function voiceView(state: VoiceState) {
  return {
    busy: !["idle", "retry", "disposed"].includes(state.type),
    recording: state.type === "recording",
    transcribing: state.type === "transcribing",
    error: state.type === "retry" ? state.message : "",
    meter: state.type === "recording" ? state.meter : undefined,
    phase: state.type === "finishing" ? "transcribing" : state.type,
  }
}
