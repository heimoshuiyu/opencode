import { Transcription } from "@opencode/schema/transcription"
import { Schema } from "effect"
import { Persistence } from "@/runtime/persistence/schema"

export const transcriptionBackends = ["server", "lalm", "whisper"] as const

export const transcriptionPreference = Persistence.struct({
  backend: Schema.Literals(transcriptionBackends),
  lalm: Persistence.optional(Schema.String),
  variant: Persistence.optional(Schema.String),
  whisper: Persistence.optional(Schema.String),
  language: Persistence.optional(Schema.String),
  system: Persistence.optional(Schema.String),
  instruction: Persistence.optional(Schema.String),
  format: Persistence.optional(Schema.Literals(["input_audio", "audio_url"])),
  words: Persistence.optional(Schema.String),
  replaceWords: Persistence.optional(Schema.Boolean),
  pairs: Persistence.optional(Transcription.RequestContextPairs),
  provider: Persistence.optional(Schema.Literals(["openai", "groq", "custom"])),
  url: Persistence.optional(Schema.String),
})

export type TranscriptionPreference = typeof transcriptionPreference.Type

export function whisperEndpoint(value?: TranscriptionPreference) {
  if (value?.backend !== "whisper" || !value.provider) return
  return value.provider === "custom" ? (value.url?.trim() ?? "") : Transcription.whisperEndpoints[value.provider]
}

function hotWords(text?: string) {
  return [
    ...new Set(
      (text ?? "")
        .split(/\r?\n/)
        .map((word) => word.trim())
        .filter(Boolean),
    ),
  ]
}

export function transcriptionOptions(
  value?: TranscriptionPreference,
  key?: string,
): { settings?: Transcription.Settings; overrides?: Transcription.Overrides } {
  if (!value) return {}
  const words = hotWords(value.words)
  const url = whisperEndpoint(value)
  const overrides = {
    ...(url !== undefined && { whisper: { url, ...(key && { apiKey: key }) } }),
    ...(value.replaceWords && { hot_words: words }),
    ...(value.pairs !== undefined && { context_pairs: value.pairs }),
  }
  const settings = { ...backendSettings(value), ...(!value.replaceWords && words.length > 0 && { hot_words: words }) }
  return {
    ...(Object.keys(settings).length > 0 && { settings }),
    // The legacy endpoint can accept the request but ignore the selected backend/model. An empty
    // overrides object still selects the endpoint that understands the nested settings.
    ...((value.backend !== "server" || Object.keys(overrides).length > 0) && { overrides }),
  }
}

function backendSettings(value: TranscriptionPreference): Transcription.Settings | undefined {
  if (value.backend === "server") return
  if (value.backend === "whisper") {
    const model = value.whisper?.trim()
    const language = value.language?.trim()
    return {
      type: "whisper",
      ...(model || language ? { whisper: { ...(model && { model }), ...(language && { language }) } } : {}),
    }
  }

  // A named variant in the server config belongs to its model, not the one picked in the app.
  const lalm = {
    ...(value.lalm && { model: value.lalm, variant: value.variant ?? "default" }),
    ...(value.system?.trim() && { system: value.system }),
    ...(value.instruction?.trim() && { instruction: value.instruction }),
    ...(value.format && { audio_input_format: value.format }),
  }
  return { type: "lalm", ...(Object.keys(lalm).length > 0 && { lalm }) }
}
