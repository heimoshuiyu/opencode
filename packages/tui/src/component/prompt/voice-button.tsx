import { Show } from "solid-js"
import { useTheme } from "../../context/theme"
import type { useVoice } from "../../context/voice"

export function VoiceButton(props: { voice: ReturnType<typeof useVoice> }) {
  const theme = useTheme()
  const voice = props.voice

  return (
    <Show
      when={voice.pendingRetry()}
      fallback={
        <box
          onMouseUp={async () => {
            if (!voice.enabled() && !voice.recording() && !voice.processing()) return
            await voice.toggle()
          }}
        >
          <text fg={theme.text.default}>{voice.label()}</text>
        </box>
      }
    >
      <box flexDirection="row" gap={1}>
        <box onMouseUp={() => voice.confirmRetry()}>
          <text fg={theme.text.feedback.warning.default}>Retry</text>
        </box>
        <box onMouseUp={() => voice.cancelRetry()}>
          <text fg={theme.text.subdued}>Cancel</text>
        </box>
      </box>
    </Show>
  )
}
