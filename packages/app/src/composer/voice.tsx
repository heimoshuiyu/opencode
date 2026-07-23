import { createMemo, Match, onCleanup, Show, Switch, type Component, type JSX } from "solid-js"
import { createStore } from "solid-js/store"
import { showToast } from "@/shell/notifications/toast"
import { Button } from "@opencode/ui/button"
import { Icon } from "@opencode/ui/icon"
import { Spinner } from "@opencode/ui/spinner"
import { Keybind } from "@opencode/ui/keybind"
import { Tooltip } from "@opencode/ui/tooltip"
import { useLanguage } from "@/runtime/i18n/language"
import type { ServerSDK } from "@/runtime/server/client"

type VoiceInput = {
  sdk: ServerSDK
  directory: () => string
  sessionID: () => string | undefined
  editorText: () => string
  addPart: (part: { type: "text"; content: string; start: number; end: number }) => void
  editorRef: () => HTMLDivElement | undefined
  queueScroll: () => void
}

const isVoiceSupported = () =>
  typeof navigator !== "undefined" &&
  typeof window !== "undefined" &&
  Boolean(navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices)) &&
  typeof MediaRecorder !== "undefined"

export function createVoiceInput(input: VoiceInput) {
  const language = useLanguage()

  let disposed = false

  const [state, setState] = createStore({
    recording: false,
    transcribing: false,
    error: "",
  })
  const recording = () => state.recording
  const transcribing = () => state.transcribing
  const audio: {
    recorder?: MediaRecorder
    starting: boolean
    stream?: MediaStream
    controller?: AbortController
    lastRecording?: Blob
    chunks: Blob[]
    mime: string
  } = {
    starting: false,
    chunks: [],
    mime: "",
  }

  const stopStream = () => {
    audio.stream?.getTracks().forEach((track) => track.stop())
    audio.stream = undefined
  }

  const recordStart = async () => {
    if (!isVoiceSupported()) {
      showToast({
        title: language.t("prompt.toast.voiceUnavailable.title"),
        description: language.t("prompt.toast.voiceUnavailable.description"),
      })
      return false
    }
    if (audio.recorder || audio.starting) return false
    audio.starting = true

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true }).catch(() => undefined)
    if (!stream) {
      audio.starting = false
      showToast({
        title: language.t("prompt.toast.voiceMicBlocked.title"),
        description: language.t("prompt.toast.voiceMicBlocked.description"),
      })
      return false
    }

    if (disposed) {
      stream.getTracks().forEach((t) => t.stop())
      audio.starting = false
      return false
    }

    audio.stream = stream

    const preferred = "audio/webm;codecs=opus"
    const fallback = "audio/webm"
    const mime = MediaRecorder.isTypeSupported(preferred)
      ? preferred
      : MediaRecorder.isTypeSupported(fallback)
        ? fallback
        : ""
    if (!mime) {
      stopStream()
      audio.starting = false
      showToast({
        title: language.t("prompt.toast.voiceUnavailable.title"),
        description: language.t("prompt.toast.voiceUnsupportedFormat.description"),
      })
      return false
    }
    let recorder: MediaRecorder
    try {
      recorder = new MediaRecorder(stream, { mimeType: mime })
    } catch {
      audio.starting = false
      stopStream()
      showToast({
        title: language.t("prompt.toast.voiceUnavailable.title"),
        description: language.t("prompt.toast.voiceUnsupportedFormat.description"),
      })
      return false
    }

    audio.mime = recorder.mimeType || mime
    audio.chunks = []
    audio.recorder = recorder
    audio.starting = false

    recorder.ondataavailable = (event) => {
      if (event.data.size === 0) return
      audio.chunks.push(event.data)
    }

    recorder.start()
    setState("recording", true)
    return true
  }

  const recordStop = async (): Promise<Blob | undefined> => {
    if (!audio.recorder) return undefined
    const recorder = audio.recorder
    audio.recorder = undefined

    const result = new Promise<Blob>((resolve) => {
      recorder.onstop = () => {
        resolve(new Blob(audio.chunks, { type: audio.mime || "audio/webm" }))
      }
    })

    recorder.stop()
    const blob = await result
    audio.chunks = []
    stopStream()
    setState("recording", false)
    return blob
  }

  const transcribeAudio = async (blob: Blob) => {
    if (!blob.size) {
      showToast({
        title: language.t("prompt.toast.voiceEmpty.title"),
        description: language.t("prompt.toast.voiceEmpty.description"),
      })
      return
    }

    const mime = blob.type || "audio/webm"
    const prompt = input.editorText()
    const controller = new AbortController()
    audio.controller = controller
    audio.lastRecording = blob
    setState({ transcribing: true, error: "" })

    const arrayBuffer = await blob.arrayBuffer()
    const bytes = new Uint8Array(arrayBuffer)
    const chunks: string[] = []
    for (let i = 0; i < bytes.length; i += 8192) {
      chunks.push(String.fromCharCode(...bytes.subarray(i, i + 8192)))
    }
    const base64 = btoa(chunks.join(""))

    const sdk = input.sdk
    const sessionID = input.sessionID()
    const result = await sdk.api.transcription
      .transcribe(
        {
          location: { directory: input.directory() },
          audio: base64,
          mime,
          ...(prompt.trim() ? { prompt } : {}),
          ...(sessionID ? { contextSessionID: sessionID } : {}),
        },
        { signal: controller.signal },
      )
      .then((res) => {
        const text = res.data.text ?? ""
        return text.trim()
          ? { ok: true as const, text }
          : { ok: false as const, message: language.t("prompt.toast.voiceRetry.description") }
      })
      .catch((err) => ({
        ok: false as const,
        message: err instanceof Error ? err.message : String(err),
      }))

    if (audio.controller !== controller) return

    if (disposed) return

    audio.controller = undefined
    setState("transcribing", false)

    if (controller.signal.aborted) return

    if (!result.ok) {
      setState("error", result.message || language.t("prompt.toast.voiceRetry.description"))
      return
    }

    audio.lastRecording = undefined
    input.addPart({ type: "text", content: result.text, start: 0, end: 0 })
    requestAnimationFrame(() => {
      const el = input.editorRef()
      if (!el) return
      el.focus()
      input.queueScroll()
    })
  }

  const toggleVoice = async () => {
    if (transcribing()) {
      const controller = audio.controller
      if (controller) {
        controller.abort()
        audio.controller = undefined
        audio.lastRecording = undefined
        setState("transcribing", false)
        showToast({
          title: language.t("prompt.toast.voiceCancelled.title"),
          description: language.t("prompt.toast.voiceCancelled.description"),
        })
      }
      return
    }

    if (state.error) {
      if (audio.lastRecording) await transcribeAudio(audio.lastRecording)

      return
    }

    if (recording()) {
      const blob = await recordStop()
      if (!blob) return
      await transcribeAudio(blob)
      return
    }

    await recordStart()
  }

  const cancelRetry = () => {
    audio.lastRecording = undefined
    setState("error", "")
    input.editorRef()?.focus()
  }

  const stopRecordingImmediate = () => {
    const recorder = audio.recorder
    audio.recorder = undefined
    if (recorder && recorder.state !== "inactive") {
      try {
        recorder.stop()
      } catch {}
    }
    stopStream()
    if (state.recording) setState("recording", false)
  }

  const voiceTitle = createMemo(() =>
    transcribing()
      ? language.t("prompt.action.voiceCancel")
      : state.error
        ? language.t("prompt.action.voiceRetry")
        : recording()
          ? language.t("prompt.action.voiceStop")
          : language.t("prompt.action.voice"),
  )

  onCleanup(() => {
    disposed = true
    if (transcribing()) {
      audio.controller?.abort()
    }

    audio.controller = undefined
    audio.lastRecording = undefined
    audio.chunks = []

    stopRecordingImmediate()
    setState({ transcribing: false, error: "" })
  })

  return {
    recording,
    transcribing,
    pendingRetry: () => !!state.error,
    error: () => state.error,
    voiceTitle,
    toggleVoice,
    cancelRetry,
  }
}

export const VoiceButton: Component<{
  voiceTitle: () => string
  toggleVoice: () => void
  recording: () => boolean
  transcribing: () => boolean
  pendingRetry: () => boolean
  cancelRetry: () => void
  keybind: string[]
  disabled?: boolean
  style?: JSX.CSSProperties
}> = (props) => {
  const language = useLanguage()
  return (
    <>
      <Tooltip
        placement="top"
        value={
          <>
            {props.voiceTitle()}
            <Keybind keys={props.keybind} variant="neutral" />
          </>
        }
      >
        <Button
          type="button"
          variant="ghost"
          class={props.pendingRetry() ? "h-8 px-2" : "size-8 p-0"}
          style={props.style}
          onClick={props.toggleVoice}
          disabled={props.disabled}
          aria-label={props.voiceTitle()}
        >
          <Switch>
            <Match when={props.pendingRetry()}>{language.t("common.retry")}</Match>
            <Match when={props.transcribing()}>
              <Spinner class="size-4 text-icon-base" />
            </Match>
            <Match when={props.recording()}>
              <Icon name="stop" size="small" />
            </Match>
            <Match when={true}>
              <Icon name="mic" class="size-4.5" />
            </Match>
          </Switch>
        </Button>
      </Tooltip>
      <Show when={props.pendingRetry()}>
        <Button
          type="button"
          variant="ghost-muted"
          class="h-8 px-2"
          style={props.style}
          onClick={props.cancelRetry}
          disabled={props.disabled}
          aria-label={language.t("prompt.action.voiceDiscard")}
        >
          {language.t("common.cancel")}
        </Button>
      </Show>
    </>
  )
}
