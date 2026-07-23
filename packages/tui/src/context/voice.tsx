import { createMemo, createSignal, onCleanup } from "solid-js"
import { createSimpleContext } from "./helper"
import { useClient } from "./client"
import { useConfig } from "../config"
import { useToast } from "../ui/toast"
import { usePromptRef } from "./prompt"
import { useRoute } from "./route"
import { useData } from "./data"
import { errorMessage } from "../util/error"
import {
  isEnabled as voiceEnabled,
  unavailableMessage as voiceUnavailable,
  startRecording,
  transcribe,
  type Recorder,
} from "../util/voice"

// App-level recording lifecycle so voice input survives session switches: the
// recorder outlives the Prompt that started it, and a finished transcription
// inserts into whichever prompt is active when it completes.
export const { use: useVoice, provider: VoiceProvider } = createSimpleContext({
  name: "Voice",
  init: () => {
    const client = useClient()
    const config = useConfig()
    const toast = useToast()
    const promptRef = usePromptRef()
    const route = useRoute()
    const data = useData()

    const [recording, setRecording] = createSignal(false)
    const [processing, setProcessing] = createSignal(false)
    const [pendingRetry, setPendingRetry] = createSignal(false)
    let recorder: Recorder | undefined
    let controller: AbortController | undefined
    let lastRecording: { buffer: ArrayBuffer; mime: string } | undefined
    // Finished text that had no active prompt to land in; the retry action inserts it directly.
    let unplacedText: string | undefined

    // Captured when recording stops: the session the user is looking at steers the
    // transcription, and its active prompt text seeds the hint.
    function context() {
      const sessionID = route.data.type === "session" ? route.data.sessionID : undefined
      const workspaceID =
        (sessionID ? data.session.get(sessionID)?.location.workspaceID : undefined) ??
        data.location.default().workspaceID
      return {
        sessionID,
        workspaceID,
        directory: data.location.default().directory,
        prompt: promptRef.current?.current.text ?? "",
      }
    }

    function insert(text: string) {
      return promptRef.current?.insert(text) ?? false
    }

    function handleResult(result: { text: string; cancelled: boolean } | null) {
      setProcessing(false)
      if (!result || result.cancelled) return
      const text = result.text.trim()
      if (insert(text)) return
      unplacedText = text
      setPendingRetry(true)
      toast.show({
        variant: "warning",
        message: "No prompt open for transcription — press the voice keybind to insert",
        duration: 5000,
      })
    }

    const runTranscribe = async (recordingData: { buffer: ArrayBuffer; mime: string }) => {
      const ctx = context()
      const ctl = new AbortController()
      controller = ctl
      const result = await transcribe(
        recordingData,
        (audio, mime, prompt) =>
          client.api.voice
            .transcribe({
              location: { directory: ctx.directory, workspace: ctx.workspaceID },
              audio,
              mime,
              ...(prompt?.trim() ? { prompt } : {}),
              ...(ctx.sessionID ? { contextSessionID: ctx.sessionID } : {}),
            })
            .then((res) => ({ text: res.data?.text ?? "" })),
        ctx.prompt,
        ctl,
      ).catch((error: unknown) => {
        toast.show({ variant: "error", message: errorMessage(error), duration: 5000 })
        return null
      })
      if (controller === ctl) controller = undefined
      if (result && !result.cancelled && !result.text.trim()) {
        toast.show({ variant: "error", message: "Transcription returned empty text", duration: 5000 })
        return null
      }
      return result
    }

    async function toggle() {
      if (processing()) {
        controller?.abort()
        controller = undefined
        setProcessing(false)
        toast.show({ message: "Transcription cancelled", variant: "info", duration: 1500 })
        return
      }

      if (recording()) {
        setRecording(false)
        setProcessing(true)
        const rec = recorder
        recorder = undefined
        if (!rec) {
          setProcessing(false)
          return
        }
        const recordingData = await rec.stop().catch((error: unknown) => {
          toast.show({ variant: "error", message: errorMessage(error), duration: 5000 })
          return null
        })
        if (!recordingData) {
          setProcessing(false)
          return
        }
        lastRecording = recordingData
        const result = await runTranscribe(recordingData)
        setPendingRetry(!result)
        handleResult(result)
        return
      }

      if (!voiceEnabled()) {
        toast.show({
          message: `Voice input unavailable: ${voiceUnavailable() ?? "missing transcription configuration"}`,
          variant: "warning",
        })
        return
      }

      setRecording(true)
      toast.show({ message: "Recording... press keybind again to stop", variant: "info", duration: 2000 })
      const rec = await startRecording(config.data.voice).catch((error: unknown) => {
        toast.show({ variant: "error", message: errorMessage(error), duration: 5000 })
        return null
      })
      if (!rec) {
        setRecording(false)
        return
      }
      recorder = rec
    }

    async function confirmRetry() {
      const text = unplacedText
      if (text !== undefined) {
        if (insert(text)) {
          unplacedText = undefined
          lastRecording = undefined
          setPendingRetry(false)
        }
        return
      }
      const recordingData = lastRecording
      if (!recordingData) return
      setPendingRetry(false)
      setProcessing(true)
      const result = await runTranscribe(recordingData)
      setPendingRetry(!result)
      handleResult(result)
    }

    function cancelRetry() {
      lastRecording = undefined
      unplacedText = undefined
      setPendingRetry(false)
    }

    onCleanup(() => {
      controller?.abort()
      recorder?.kill()
    })

    const label = createMemo(() => {
      if (processing()) return "Transcribing"
      if (recording()) return "Stop"
      return "Record"
    })

    return {
      toggle,
      confirmRetry,
      cancelRetry,
      enabled: () => voiceEnabled(),
      pendingRetry,
      label,
      recording,
      processing,
    }
  },
})
