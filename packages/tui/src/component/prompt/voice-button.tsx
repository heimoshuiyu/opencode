import { createEffect, createMemo, createSignal, Match, onCleanup, Show, Switch } from "solid-js"
import { useTheme } from "../../context/theme"
import type { useVoice } from "../../context/voice"
import { Spinner } from "../spinner"

export function VoiceButton(props: { voice: ReturnType<typeof useVoice>; width: number; animations: boolean }) {
  const theme = useTheme()
  const phase = () => props.voice.state().type
  const animated = createMemo(() => props.width >= 64 && props.animations)
  const [wave, setWave] = createSignal("")
  const [elapsed, setElapsed] = createSignal("0:00")
  createEffect(() => {
    const s = props.voice.state()
    if (s.type !== "recording") return
    const motion = animated()
    const history = new Float32Array(11)
    let tick = 0
    const frame = () => {
      const seconds = Math.floor((Date.now() - s.since) / 1000)
      setElapsed(`${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`)
      if (!motion) return
      history.copyWithin(1, 0)
      history[0] = s.recorder.level()
      setWave(scope(history, tick++))
    }
    frame()
    const timer = setInterval(frame, motion ? 100 : 1000)
    onCleanup(() => clearInterval(timer))
  })

  return (
    <box flexDirection="row" gap={1} height={1} flexShrink={0}>
      <Switch>
        <Match when={phase() === "recording" || phase() === "transcribing"}>
          <box
            flexDirection="row"
            justifyContent="flex-end"
            minWidth={(animated() ? 11 : 1) + 1 + elapsed().length}
            onMouseUp={props.voice.toggle}
          >
            <Show when={phase() === "recording"} fallback={<Spinner color={theme.text.feedback.info.base} />}>
              <text fg={theme.text.feedback.info.base} wrapMode="none">
                {animated() ? wave() : "●"} <span style={{ fg: theme.text.muted }}>{elapsed()}</span>
              </text>
            </Show>
          </box>
        </Match>
        <Match when={phase() === "retry"}>
          <text fg={theme.text.action.primary.base} onMouseUp={props.voice.toggle}>
            Retry
          </text>
          <text fg={theme.text.muted} onMouseUp={props.voice.cancel}>
            Discard
          </text>
        </Match>
        <Match when={phase() === "idle"}>
          <text fg={theme.text.action.primary.base} onMouseUp={props.voice.toggle}>
            Record
          </text>
        </Match>
      </Switch>
    </box>
  )
}

const dots = [
  [1, 2, 4, 64],
  [8, 16, 32, 128],
]

// Each character packs two columns × four dots. The centre reads the newest level;
// the ends trail behind it.
function scope(history: Float32Array, tick: number) {
  let text = ""
  for (let cell = 0; cell < 11; cell++) {
    let mask = 0
    for (let col = 0; col < 2; col++) {
      const x = cell * 2 + col - 10.5
      const phase = x * 0.52 - tick * 0.51
      const amp = 0.18 + 1.32 * history[Math.floor(Math.abs(x))]
      mask |= dots[col][Math.round(1.5 + amp * Math.sin(phase))]
      mask |= dots[col][Math.round(1.5 - amp * Math.sin(phase + 0.6))]
    }
    text += String.fromCharCode(0x2800 + mask)
  }
  return text
}
