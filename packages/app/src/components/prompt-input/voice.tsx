import { createMemo, createSignal, onCleanup, type Component } from "solid-js"
import { Switch, Match } from "solid-js"
import { showToast } from "@opencode-ai/ui/toast"
import { Button } from "@opencode-ai/ui/button"
import { Icon } from "@opencode-ai/ui/icon"
import { Spinner } from "@opencode-ai/ui/spinner"
import { TooltipKeybind } from "@opencode-ai/ui/tooltip"
import type { Prompt } from "@/context/prompt"
import type { useSDK } from "@/context/sdk"
import type { useCommand } from "@/context/command"

type VoiceInput = {
  sdk: ReturnType<typeof useSDK>
  params: { id?: string }
  promptText: () => string
  addPart: (part: { type: "text"; content: string; start: number; end: number }) => void
  editorRef: HTMLDivElement
  queueScroll: () => void
}

const isVoiceSupported = () =>
  typeof navigator !== "undefined" &&
  typeof window !== "undefined" &&
  Boolean(navigator.mediaDevices?.getUserMedia) &&
  typeof MediaRecorder !== "undefined"

export function createVoiceInput(input: VoiceInput) {
  const [recording, setRecording] = createSignal(false)
  const [transcribing, setTranscribing] = createSignal(false)
  const audio = {
    recorder: undefined as MediaRecorder | undefined,
    stream: undefined as MediaStream | undefined,
    controller: undefined as AbortController | undefined,
    chunks: [] as Blob[],
    mime: "",
  }

  const stopStream = () => {
    audio.stream?.getTracks().forEach((track) => track.stop())
    audio.stream = undefined
  }

  const recordStart = async () => {
    if (!isVoiceSupported()) {
      showToast({
        title: "Voice input unavailable",
        description: "Your browser does not support audio recording.",
      })
      return false
    }
    if (audio.recorder) return false

    const stream = await navigator.mediaDevices
      .getUserMedia({ audio: true })
      .catch(() => undefined)
    if (!stream) {
      showToast({
        title: "Microphone blocked",
        description: "Allow microphone access to start recording.",
      })
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
      showToast({
        title: "Voice input unavailable",
        description: "This browser does not support the available audio formats.",
      })
      return false
    }
    const recorder = new MediaRecorder(stream, { mimeType: mime })

    audio.mime = recorder.mimeType || mime
    audio.chunks = []
    audio.recorder = recorder

    recorder.ondataavailable = (event) => {
      if (event.data.size === 0) return
      audio.chunks.push(event.data)
    }

    recorder.start()
    setRecording(true)
    return true
  }

  const recordStop = async () => {
    if (!audio.recorder) return
    const recorder = audio.recorder
    audio.recorder = undefined

    const result = new Promise<Blob>((resolve) => {
      recorder.onstop = () => {
        resolve(new Blob(audio.chunks, { type: audio.mime || "audio/webm" }))
      }
    })

    recorder.stop()
    const blob = await result
    stopStream()
    setRecording(false)
    return blob
  }

  const transcribeAudio = async (blob: Blob) => {
    if (!blob.size) {
      showToast({
        title: "No audio captured",
        description: "Try recording again.",
      })
      return
    }

    const mime = blob.type || "audio/webm"
    const promptText = input.promptText()
    const arrayBuffer = await blob.arrayBuffer()
    const bytes = new Uint8Array(arrayBuffer)
    const chunks: string[] = []
    for (let i = 0; i < bytes.length; i += 8192) {
      chunks.push(String.fromCharCode(...bytes.subarray(i, i + 8192)))
    }
    const base64 = btoa(chunks.join(""))

    const controller = new AbortController()
    audio.controller = controller
    setTranscribing(true)

    const response = await input.sdk.client
      .audio.transcribe(
        {
          audio: base64,
          mime,
          ...(input.params.id ? { sessionID: input.params.id } : {}),
          ...(promptText.trim() ? { prompt: promptText } : {}),
        },
        { signal: controller.signal },
      )
      .catch((_: unknown) => {
        if (controller.signal.aborted) return undefined
        return undefined
      })

    audio.controller = undefined

    if (!response || ("error" in response && response.error)) {
      setTranscribing(false)
      if (controller.signal.aborted) return
      showToast({
        title: "Transcription failed",
        description: "Failed to reach the server.",
      })
      return
    }

    setTranscribing(false)

    if (controller.signal.aborted) return

    const text = "data" in response && response.data ? response.data.text : "text" in response ? (response as { text: string }).text : ""

    if (!text.trim()) {
      showToast({
        title: "No speech detected",
        description: "Try speaking closer to the microphone.",
      })
      return
    }

    input.addPart({ type: "text", content: text, start: 0, end: 0 })
    requestAnimationFrame(() => {
      input.editorRef.focus()
      input.queueScroll()
    })
  }

  const toggleVoice = async () => {
    if (transcribing()) {
      const controller = audio.controller
      if (controller) {
        controller.abort()
        setTranscribing(false)
        showToast({
          title: "Transcription cancelled",
          description: "Stopped the current transcription.",
        })
      }
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

  const voiceTitle = createMemo(() =>
    transcribing() ? "Cancel transcription" : recording() ? "Stop recording" : "Voice input",
  )

  onCleanup(() => {
    if (transcribing()) {
      const controller = audio.controller
      if (controller) controller.abort()
      setTranscribing(false)
    }
    if (!recording()) return
    void recordStop()
  })

  return {
    recording,
    transcribing,
    voiceTitle,
    toggleVoice,
  }
}

export const VoiceButton: Component<{
  voiceTitle: () => string
  toggleVoice: () => void
  recording: () => boolean
  transcribing: () => boolean
  keybind: string
}> = (props) => (
  <TooltipKeybind placement="top" title={props.voiceTitle()} keybind={props.keybind}>
    <Button type="button" variant="ghost" class="h-6 w-6 shrink-0" onClick={props.toggleVoice}>
      <Switch>
        <Match when={props.transcribing()}>
          <Spinner class="size-4 text-icon-base" />
        </Match>
        <Match when={props.recording()}>
          <Icon name="stop" size="small" />
        </Match>
        <Match when={true}>
          <Icon name="mic" size="small" />
        </Match>
      </Switch>
    </Button>
  </TooltipKeybind>
)
