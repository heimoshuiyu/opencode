import { Show } from "solid-js"
import { Select } from "@opencode/ui/select"
import { Textarea } from "@opencode/ui/textarea"
import { TextInput } from "@opencode/ui/text-input"
import { useLanguage } from "@/runtime/i18n/language"
import { useServerSDK } from "@/runtime/server/client"
import { useSettings } from "@/settings/model"
import { SettingsRow } from "@/settings/row"
import { type TranscriptionPreference, whisperEndpoint } from "@/settings/transcription"

const providers = ["server", "openai", "groq", "custom"] as const
const wordModes = ["append", "replace"] as const
const formats = ["server", "input_audio", "audio_url"] as const
const pairs = ["server", ...Array.from({ length: 21 }, (_, i) => String(i))]

export function TranscriptionOptions() {
  const { t } = useLanguage()
  const { scope } = useServerSDK()
  const prefs = useSettings().transcription
  const value = () => prefs.get(scope)
  const set = (patch: Partial<TranscriptionPreference>) =>
    prefs.set(scope, { ...(value() ?? { backend: "server" }), ...patch })
  const inherit = () => t("settings.transcription.server")

  function LongText(props: { field: "system" | "instruction" | "words" }) {
    const title = () => t(`settings.transcription.${props.field}`)
    return (
      <SettingsRow title={title()} description={t(`settings.transcription.${props.field}Description`)}>
        <div class="w-full sm:w-64">
          <Textarea
            aria-label={title()}
            dir="auto"
            value={value()?.[props.field] ?? ""}
            onInput={(e) => set({ [props.field]: e.currentTarget.value })}
          />
        </div>
      </SettingsRow>
    )
  }

  return (
    <>
      <Show when={value()?.backend === "whisper"}>
        <SettingsRow
          title={t("settings.transcription.provider")}
          description={t("settings.transcription.providerDescription")}
        >
          <Select<(typeof providers)[number]>
            aria-label={t("settings.transcription.provider")}
            options={[...providers]}
            current={value()?.provider ?? "server"}
            label={(id) => t(`settings.transcription.${id}`)}
            onSelect={(id) => id && set({ provider: id === "server" ? undefined : id })}
          />
        </SettingsRow>
        <Show when={value()?.provider === "custom"}>
          <SettingsRow title={t("settings.transcription.url")} description={t("settings.transcription.urlDescription")}>
            <div class="w-full sm:w-64">
              <TextInput
                type="url"
                aria-label={t("settings.transcription.url")}
                dir="ltr"
                spellcheck={false}
                value={value()?.url ?? ""}
                onInput={(e) => set({ url: e.currentTarget.value })}
              />
            </div>
          </SettingsRow>
        </Show>
        <Show when={value()?.provider}>
          <SettingsRow title={t("settings.transcription.key")} description={t("settings.transcription.keyDescription")}>
            <div class="w-full sm:w-64">
              <TextInput
                type="password"
                aria-label={t("settings.transcription.key")}
                autocomplete="off"
                disabled={!whisperEndpoint(value())}
                value={prefs.key(scope) ?? ""}
                onInput={(e) => prefs.setKey(scope, e.currentTarget.value)}
              />
            </div>
          </SettingsRow>
        </Show>
        <SettingsRow
          title={t("settings.transcription.language")}
          description={t("settings.transcription.languageDescription")}
        >
          <div class="w-full sm:w-64">
            <TextInput
              aria-label={t("settings.transcription.language")}
              placeholder={inherit()}
              dir="ltr"
              value={value()?.language ?? ""}
              onInput={(e) => set({ language: e.currentTarget.value })}
            />
          </div>
        </SettingsRow>
      </Show>
      <LongText field="words" />
      <SettingsRow
        title={t("settings.transcription.wordMode")}
        description={t("settings.transcription.wordModeDescription")}
      >
        <Select
          aria-label={t("settings.transcription.wordMode")}
          options={[...wordModes]}
          current={value()?.replaceWords ? "replace" : "append"}
          label={(mode) => t(`settings.transcription.${mode}`)}
          onSelect={(mode) => mode && set({ replaceWords: mode === "replace" })}
        />
      </SettingsRow>
      <details>
        <summary class="cursor-pointer py-3 text-13-medium">{t("settings.transcription.advanced")}</summary>
        <SettingsRow
          title={t("settings.transcription.pairs")}
          description={t("settings.transcription.pairsDescription")}
        >
          <Select
            aria-label={t("settings.transcription.pairs")}
            options={pairs}
            current={String(value()?.pairs ?? "server")}
            label={(n) => (n === "0" ? t("settings.transcription.audioOnly") : n === "server" ? inherit() : n)}
            onSelect={(n) => n && set({ pairs: n === "server" ? undefined : Number(n) })}
          />
        </SettingsRow>
        <Show when={value()?.backend === "lalm"}>
          <LongText field="system" />
          <LongText field="instruction" />
          <SettingsRow
            title={t("settings.transcription.format")}
            description={t("settings.transcription.formatDescription")}
          >
            <Select<(typeof formats)[number]>
              aria-label={t("settings.transcription.format")}
              options={[...formats]}
              current={value()?.format ?? "server"}
              label={(id) => (id === "server" ? inherit() : id)}
              onSelect={(id) => id && set({ format: id === "server" ? undefined : id })}
            />
          </SettingsRow>
        </Show>
      </details>
    </>
  )
}
