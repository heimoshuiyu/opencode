import { createEffect, createMemo, createSignal, Match, on, onCleanup, Show, Switch } from "solid-js"
import { Button } from "@opencode/ui/button"
import { Icon } from "@opencode/ui/icon"
import { Keybind } from "@opencode/ui/keybind"
import { Loader } from "@opencode/ui/loader"
import { Tooltip } from "@opencode/ui/tooltip"
import { useLanguage } from "@/runtime/i18n/language"
import type { ServerSDK } from "@/runtime/server/client"
import { useSettings } from "@/settings/model"
import { showToast } from "@/shell/notifications/toast"
import { voiceFeedback } from "./voice-feedback"
import { useTranscribe } from "./transcribe"
import { transitionVoice, voiceView, type VoiceEvent, type VoiceState } from "./voice-machine"

type VoiceInput = {
  sdk: ServerSDK
  directory: () => string
  sessionID: () => string | undefined
  editorText: () => string
  supportsAudio: () => boolean
  addAttachment: (file: File) => Promise<boolean>
  addPart: (part: { type: "text"; content: string; start: number; end: number }) => void
  restoreFocus: () => void
  queueScroll: () => void
}

export function createVoiceInput(input: VoiceInput) {
  const language = useLanguage()
  const settings = useSettings()
  const transcribe = useTranscribe(input.sdk)
  const [state, setState] = createSignal<VoiceState>({ type: "idle" })
  const view = createMemo(() => voiceView(state()))
  const dispatch = (event: VoiceEvent) => setState((current) => transitionVoice(current, event))

  const current = (attempt: AbortController) => {
    const s = state()

    return "attempt" in s && s.attempt === attempt
  }

  const cancel = (dispose = false) => {
    const s = state()
    dispatch({ type: dispose ? "dispose" : "cancel" })

    if ("attempt" in s) s.attempt.abort()
  }

  async function process(blob: Blob, attempt: AbortController) {
    const focus = () => {
      input.restoreFocus()
      input.queueScroll()
    }

    const timeout = setTimeout(() => {
      if (!current(attempt)) return
      dispatch({ type: "failed", attempt, message: language.t("prompt.toast.voiceFailed.description") })
      attempt.abort()
    }, 120_000)

    try {
      if (state().type === "attaching") {
        const attached = await input.addAttachment(
          new File([blob], `recording-${Date.now()}.webm`, { type: blob.type }),
        )

        if (current(attempt) && attached) focus()
      } else {
        const prompt = input.editorText()
        const location = { directory: input.directory() }
        const sessionID = input.sessionID()
        const bytes = new Uint8Array(await blob.arrayBuffer())

        if (!current(attempt)) return
        const chunks: string[] = []

        for (let i = 0; i < bytes.length; i += 8192) chunks.push(String.fromCharCode(...bytes.subarray(i, i + 8192)))

        const result = await transcribe(
          {
            location,
            audio: btoa(chunks.join("")),
            mime: blob.type,
            prompt: prompt.trim() ? prompt : undefined,
            contextSessionID: sessionID,
          },
          { signal: attempt.signal },
        )

        if (!current(attempt)) return
        const text = result.data.text

        if (!text?.trim()) throw new Error(language.t("prompt.toast.voiceRetry.description"))
        input.addPart({ type: "text", content: text, start: 0, end: 0 })
        focus()
      }

      dispatch({ type: "complete", attempt })
    } catch (error) {
      if (!current(attempt)) return
      const message = error instanceof Error ? error.message : String(error)

      if (state().type === "attaching")
        showToast({ variant: "error", title: language.t("common.requestFailed"), description: message })
      dispatch({ type: "failed", attempt, message: message || language.t("prompt.toast.voiceRetry.description") })
    } finally {
      clearTimeout(timeout)
    }
  }

  async function start(output: "text" | "audio") {
    if (state().type !== "idle") return

    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      showToast({
        title: language.t("prompt.toast.voiceUnavailable.title"),
        description: language.t("prompt.toast.voiceUnavailable.description"),
      })

      return
    }

    const mime = ["audio/webm;codecs=opus", "audio/webm"].find((type) => MediaRecorder.isTypeSupported(type))

    if (!mime) {
      showToast({
        title: language.t("prompt.toast.voiceUnavailable.title"),
        description: language.t("prompt.toast.voiceUnsupportedFormat.description"),
      })

      return
    }

    const attempt = new AbortController()
    dispatch({ type: "start", attempt, output })
    const feedback = voiceFeedback(settings.sounds.interfaceEnabled)
    const cleanup = () => feedback?.close(false)
    attempt.signal.addEventListener("abort", cleanup, { once: true })

    const fail = (mic: boolean) => {
      if (!current(attempt)) return
      cancel()
      showToast({
        title: language.t(mic ? "prompt.toast.voiceMicBlocked.title" : "prompt.toast.voiceUnavailable.title"),
        description: language.t(
          mic ? "prompt.toast.voiceMicBlocked.description" : "prompt.toast.voiceFailed.description",
        ),
      })
    }

    const timeout = setTimeout(() => fail(true), 60_000)
    attempt.signal.addEventListener("abort", () => clearTimeout(timeout), { once: true })
    const stream = await navigator.mediaDevices
      .getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
      .catch(() => undefined)
    clearTimeout(timeout)

    if (!stream) return fail(true)
    const stopTracks = () => stream.getTracks().forEach((track) => track.stop())

    // Permission may arrive after a second press or navigation. Never record that late grant.
    if (!current(attempt)) return stopTracks()
    attempt.signal.addEventListener("abort", stopTracks, { once: true })

    try {
      const recorder = new MediaRecorder(stream, { mimeType: mime })
      const chunks: Blob[] = []
      recorder.ondataavailable = ({ data }) => {
        if (data.size) chunks.push(data)
      }

      recorder.onerror = () => fail(false)
      recorder.onstop = () => {
        if (!current(attempt)) return

        if (state().type !== "finishing") return fail(false)
        attempt.signal.removeEventListener("abort", stopTracks)
        attempt.signal.removeEventListener("abort", cleanup)
        stopTracks()
        feedback?.close(true)
        const blob = new Blob(chunks, { type: recorder.mimeType || mime })
        dispatch({ type: "stopped", attempt, blob })

        if (blob.size) void process(blob, attempt)
        else
          showToast({
            title: language.t("prompt.toast.voiceEmpty.title"),
            description: language.t("prompt.toast.voiceEmpty.description"),
          })
      }

      recorder.start()
      feedback?.start(stream)
      dispatch({ type: "ready", attempt, stop: () => recorder.stop(), meter: feedback?.meter })
    } catch {
      fail(false)
    }
  }

  const toggleVoice = () => {
    const s = state()

    if (s.type === "opening" || s.type === "recording") {
      dispatch({ type: "stop" })

      return s.type === "opening" ? s.attempt.abort() : s.stop()
    }

    if (s.type === "transcribing") {
      cancel()
      showToast({
        title: language.t("prompt.toast.voiceCancelled.title"),
        description: language.t("prompt.toast.voiceCancelled.description"),
      })

      return
    }

    if (s.type === "retry") {
      const attempt = new AbortController()
      dispatch({ type: "retry", attempt })

      return void process(s.blob, attempt)
    }

    void start(input.supportsAudio() ? "audio" : "text")
  }

  createEffect(on([input.directory, input.sessionID], () => cancel(), { defer: true }))
  onCleanup(() => cancel(true))

  return {
    view,
    processing: () => view().busy,
    error: () => view().error,
    cancelRetry() {
      cancel()
      input.restoreFocus()
    },
    toggleVoice,
  }
}

export function VoiceButton(props: {
  voice: ReturnType<typeof createVoiceInput>
  keybind: string[]
  disabled?: boolean
}) {
  const language = useLanguage()
  const view = props.voice.view

  const title = () =>
    language.t(
      view().transcribing
        ? "prompt.action.voiceCancel"
        : view().error
          ? "prompt.action.voiceRetry"
          : view().recording || view().phase === "opening"
            ? "prompt.action.voiceStop"
            : "prompt.action.voice",
    )

  return (
    <>
      <Tooltip
        placement="top"
        value={
          <>
            {title()}
            <Show when={props.keybind.length && view().phase !== "attaching"}>
              <Keybind keys={props.keybind} variant="neutral" />
            </Show>
          </>
        }
      >
        <Button
          type="button"
          variant="ghost"
          class={
            view().error
              ? "h-8 px-2 pointer-coarse:h-11"
              : "size-8 p-0 pointer-coarse:size-11 pointer-coarse:rounded-lg"
          }
          onMouseDown={(event: MouseEvent) => event.preventDefault()}
          onClick={props.voice.toggleVoice}
          disabled={props.disabled || view().phase === "attaching"}
          aria-label={title()}
        >
          <Switch fallback={<Icon name="mic" class="size-4.5" />}>
            <Match when={view().error}>{language.t("common.retry")}</Match>
            <Match when={view().transcribing}>
              <Loader class="size-4" />
            </Match>
            <Match when={view().recording || view().phase === "opening"}>
              <Icon name="stop" size="small" />
            </Match>
          </Switch>
        </Button>
      </Tooltip>
      <Show when={view().error}>
        <Button
          type="button"
          variant="ghost-muted"
          class="h-8 px-2 pointer-coarse:h-11"
          onMouseDown={(event: MouseEvent) => event.preventDefault()}
          onClick={props.voice.cancelRetry}
          disabled={props.disabled}
          aria-label={language.t("prompt.action.voiceDiscard")}
        >
          {language.t("common.cancel")}
        </Button>
      </Show>
    </>
  )
}
