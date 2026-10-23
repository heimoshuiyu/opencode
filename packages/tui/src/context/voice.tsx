import { createMemo, createSignal, onCleanup } from "solid-js"
import { createSimpleContext } from "./helper"
import { useClient } from "./client"
import { useConfig } from "../config"
import { useToast } from "../ui/toast"
import { usePromptRef } from "./prompt"
import { useRoute } from "./route"
import { useData } from "./data"
import { useLocal } from "./local"
import { useLocation } from "./location"
import { errorMessage } from "../util/error"
import {
  isEnabled as voiceEnabled,
  unavailableMessage as voiceUnavailable,
  startRecording,
  transcribe,
  type Recorder,
} from "../util/voice"

// App-level recording lifecycle so voice input survives session switches: the
// recorder outlives the Prompt that started it, and finished audio enters the
// active prompt as an attachment or transcription, depending on the model.
export const { use: useVoice, provider: VoiceProvider } = createSimpleContext({
  name: "Voice",
  init: () => {
    const client = useClient()
    const config = useConfig()
    const toast = useToast()
    const promptRef = usePromptRef()
    const route = useRoute()
    const data = useData()
    const local = useLocal()
    const location = useLocation()

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
      return {
        sessionID,
        directory: data.location.default().directory,
        prompt: promptRef.current?.current.text ?? "",
      }
    }

    function insert(text: string) {
      return promptRef.current?.insert(text) ?? false
    }

    function supportsAudio() {
      const selection = local.model.selection()
      if (!selection) return false
      const model = data.location.model
        .list(location.ref)
        ?.find((item) => item.providerID === selection.providerID && item.id === selection.modelID)
      return model?.capabilities.input.some((item) => item.startsWith("audio")) ?? false
    }

    function canAttachAudio() {
      return supportsAudio() && promptRef.current?.mode !== "shell"
    }

    function attach(recordingData: { buffer: ArrayBuffer; mime: string }) {
      const uri = `data:${recordingData.mime};base64,${Buffer.from(recordingData.buffer).toString("base64")}`
      return promptRef.current?.attach({ uri, filename: "recording.wav" }) ?? false
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
          client.api.transcription
            .transcribe({
              location: { directory: ctx.directory },
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

    async function processRecording(recordingData: { buffer: ArrayBuffer; mime: string }) {
      if (canAttachAudio()) {
        const attached = attach(recordingData)
        setProcessing(false)
        setPendingRetry(!attached)
        if (attached) {
          lastRecording = undefined
          return
        }
        toast.show({
          variant: "warning",
          message: "No prompt open for audio attachment — press the voice keybind to insert",
          duration: 5000,
        })
        return
      }
      const result = await runTranscribe(recordingData)
      setPendingRetry(!result)
      handleResult(result)
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
        await processRecording(recordingData)
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
      const rec = await startRecording(config.data.transcription).catch((error: unknown) => {
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
      await processRecording(recordingData)
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
      if (processing()) return canAttachAudio() ? "Attaching" : "Transcribing"
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
