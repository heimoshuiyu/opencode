import { createSimpleContext } from "./helper"
import { useClient } from "./client"
import { useConfig } from "../config"
import { useToast } from "../ui/toast"
import { usePromptRef } from "./prompt"
import { useRoute } from "./route"
import { useData } from "./data"
import { startRecording } from "../util/voice"
import { playDictationCue } from "../audio"
import { createVoiceInput } from "./voice-input"

export const { use: useVoice, provider: VoiceProvider } = createSimpleContext({
  name: "Voice",
  init: () => {
    const client = useClient()
    const config = useConfig()
    const toast = useToast()
    const prompt = usePromptRef()
    const route = useRoute()
    const data = useData()

    return createVoiceInput({
      record: () => startRecording(config.data.transcription),
      insert: (text) => prompt.current?.insert(text) ?? false,
      async transcribe(audio, signal) {
        const text = prompt.current?.current.text
        const res = await client.api.transcription.transcribe(
          {
            location: { directory: data.location.default().directory },
            audio: Buffer.from(audio.buffer).toString("base64"),
            mime: audio.mime,
            prompt: text?.trim() ? text : undefined,
            contextSessionID: route.data.type === "session" ? route.data.sessionID : undefined,
          },
          { signal },
        )
        return res.data.text
      },
      cue(kind) {
        if (config.data.attention.sound) playDictationCue(kind, config.data.attention.volume)
      },
      notify: (message, variant) => toast.show({ message, variant, duration: 5000 }),
    })
  },
})
