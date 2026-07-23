import { createMemo, Show } from "solid-js"
import { Select } from "@opencode/ui/select"
import { TextInput } from "@opencode/ui/text-input"
import { useLanguage } from "@/runtime/i18n/language"
import { useServerSDK } from "@/runtime/server/client"
import { useData } from "@/runtime/server/current"
import { SettingsList } from "@/settings/list"
import { useSettings } from "@/settings/model"
import { SettingsRow } from "@/settings/row"
import { transcriptionBackends } from "@/settings/transcription"
import { TranscriptionOptions } from "./transcription-options"
import { transcriptionModelIssue } from "@opencode/util/transcription-model"

export function VoiceInputSettings() {
  const language = useLanguage()
  const sdk = useServerSDK()
  const data = useData()
  const settings = useSettings().transcription
  const current = () => settings.get(sdk.scope)
  const backend = () => current()?.backend ?? "server"
  const catalog = () => data.location.model.list()

  const models = createMemo(() =>
    (catalog() ?? [])
      .filter((model) => model.status !== "deprecated" && !transcriptionModelIssue(model))
      .map((model) => `${model.providerID}/${model.id}`)
      .sort(),
  )

  const options = createMemo(() => {
    const saved = current()?.lalm

    // Keep the saved ID visible if the catalog is still loading or the provider has disconnected.
    return ["server", ...(saved && !models().includes(saved) ? [saved] : []), ...models()]
  })

  const variants = createMemo(() => {
    const id = current()?.lalm
    const model = id && catalog()?.find((item) => `${item.providerID}/${item.id}` === id)
    const saved = current()?.variant

    return [...new Set(["default", ...(model ? model.variants.map((v) => v.id) : []), ...(saved ? [saved] : [])])]
  })

  return (
    <section class="settings-section" aria-label={language.t("settings.transcription.title")}>
      <h3 class="settings-section-title">{language.t("settings.transcription.title")}</h3>
      <SettingsList>
        <SettingsRow
          title={language.t("settings.transcription.backend")}
          description={language.t("settings.transcription.description")}
        >
          <Select
            data-action="settings-transcription"
            aria-label={language.t("settings.transcription.backend")}
            options={[...transcriptionBackends]}
            current={backend()}
            label={(option) => language.t(`settings.transcription.${option}`)}
            onSelect={(option) => option && settings.set(sdk.scope, { ...current(), backend: option })}
          />
        </SettingsRow>
        <Show when={backend() === "lalm"}>
          <SettingsRow
            title={language.t("settings.transcription.lalmModel")}
            description={language.t(
              catalog() && !models().length ? "settings.transcription.empty" : "settings.transcription.lalmDescription",
            )}
          >
            <div class="min-w-0 max-w-full sm:max-w-64">
              <Select
                aria-label={language.t("settings.transcription.lalmModel")}
                valueClass={current()?.lalm ? "[direction:ltr]" : undefined}
                options={options()}
                current={current()?.lalm || "server"}
                label={(id) => (id === "server" ? language.t("settings.transcription.server") : id)}
                onSelect={(id) =>
                  id &&
                  settings.set(sdk.scope, {
                    ...current(),
                    backend: "lalm",
                    lalm: id === "server" ? undefined : id,
                    variant: undefined,
                  })
                }
              >
                {(id) => (id === "server" ? language.t("settings.transcription.server") : <bdi dir="ltr">{id}</bdi>)}
              </Select>
            </div>
          </SettingsRow>
          <Show when={current()?.lalm}>
            <SettingsRow
              title={language.t("settings.transcription.variant")}
              description={language.t("settings.transcription.variantDescription")}
            >
              <Select
                aria-label={language.t("settings.transcription.variant")}
                options={variants()}
                current={current()?.variant ?? "default"}
                disabled={variants().length === 1}
                label={(id) => (id === "default" ? language.t("common.default") : id)}
                onSelect={(id) =>
                  id &&
                  settings.set(sdk.scope, { ...current(), backend: "lalm", variant: id === "default" ? undefined : id })
                }
              />
            </SettingsRow>
          </Show>
        </Show>
        <Show when={backend() === "whisper"}>
          <SettingsRow
            title={language.t("settings.transcription.whisperModel")}
            description={language.t("settings.transcription.whisperDescription")}
          >
            <div class="w-full sm:w-[220px]">
              <TextInput
                aria-label={language.t("settings.transcription.whisperModel")}
                placeholder={language.t("settings.transcription.server")}
                dir="ltr"
                spellcheck={false}
                autocapitalize="off"
                value={current()?.whisper ?? ""}
                onInput={(event) =>
                  settings.set(sdk.scope, { ...current(), backend: "whisper", whisper: event.currentTarget.value })
                }
              />
            </div>
          </SettingsRow>
        </Show>
        <TranscriptionOptions />
      </SettingsList>
    </section>
  )
}
