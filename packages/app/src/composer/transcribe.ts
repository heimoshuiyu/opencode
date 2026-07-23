import { ClientError, type TranscriptionTranscribeInput } from "@opencode/client/promise"
import { useLanguage } from "@/runtime/i18n/language"
import { whisperEndpoints } from "@opencode/schema/transcription"
import type { ServerSDK } from "@/runtime/server/client"
import { useSettings } from "@/settings/model"

export function useTranscribe(sdk: ServerSDK) {
  const prefs = useSettings().transcription
  const language = useLanguage()
  return async (audio: Omit<TranscriptionTranscribeInput, "settings">, options?: { signal: AbortSignal }) => {
    const { settings, overrides } = prefs.options(sdk.scope)
    const endpoint = overrides?.whisper
    if (endpoint && !endpoint.url) throw new Error(language.t("settings.transcription.urlRequired"))
    if (
      endpoint &&
      !endpoint.apiKey &&
      (endpoint.url === whisperEndpoints.openai || endpoint.url === whisperEndpoints.groq)
    ) {
      throw new Error(language.t("settings.transcription.keyRequired"))
    }
    const input =
      overrides?.context_pairs === 0 ? { audio: audio.audio, mime: audio.mime, location: audio.location } : audio
    // Never retry an advanced request against a legacy server: it would silently use the wrong destination.
    const request = overrides
      ? sdk.api.experimental.transcription.transcribeWithOverrides({ ...input, settings, overrides }, options)
      : sdk.api.transcription.transcribe({ ...input, settings }, options)
    return request.catch((error) => {
      if (
        error instanceof Error &&
        "code" in error &&
        (error.code === "service_starting" || error.code === "service_stopping")
      )
        throw new Error(language.t("settings.transcription.serverTransition"))
      if (error instanceof Error && endpoint && error.message === "Whisper request failed (401)")
        throw new Error(language.t("settings.transcription.keyRequired"))
      if (
        overrides &&
        ((error instanceof ClientError && ["UnsupportedContentType", "MalformedResponse"].includes(error.reason)) ||
          (error instanceof Error && !("_tag" in error) && /^(Not Found|404 Not Found)$/i.test(error.message)))
      )
        throw new Error(language.t("settings.transcription.unsupported"))
      throw error
    })
  }
}
