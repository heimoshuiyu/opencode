/** @jsxImportSource @opentui/solid */
import { testRender, useTerminalDimensions } from "@opentui/solid"
import { expect, test } from "bun:test"
import { createRoot } from "solid-js"
import { PromptMetadataRow } from "../../src/component/prompt/metadata"
import { VoiceButton } from "../../src/component/prompt/voice-button"
import { ConfigProvider } from "../../src/config"
import { ThemeProvider, useTheme } from "../../src/context/theme"
import { createVoiceInput } from "../../src/context/voice-input"
import { emptyThemeSource } from "../fixture/fixture"
import { TestTuiContexts } from "../fixture/tui-environment"
import { createTuiResolvedConfig } from "../fixture/tui-runtime"

const wav = { mime: "audio/wav", buffer: new ArrayBuffer(48) }

test("Record and its shortcut share the model's output choice, even when switching controls", async () => {
  const output: string[] = []
  const cues: string[] = []
  const result = Promise.withResolvers<string>()
  let acceptsAudio = false
  let voice!: ReturnType<typeof createVoiceInput>

  function Prompt() {
    const size = useTerminalDimensions()
    voice = createVoiceInput({
      record: () => ({ stop: () => wav, level: () => 0.8, kill() {} }),
      acceptsAudio: () => acceptsAudio,
      attach: (audio) => {
        expect(audio).toBe(wav)
        output.push("attachment")
        return true
      },
      insert: (text) => {
        output.push(text)
        return true
      },
      transcribe: () => result.promise,
      cue: (kind) => cues.push(kind),
      notify: (message) => {
        throw new Error(message)
      },
    })
    const theme = useTheme()
    return (
      <box flexDirection="row" gap={1}>
        <PromptMetadataRow
          mode="normal"
          agent="Build"
          auto={false}
          model="GPT-6.1 Sol Fast"
          provider="OpenAI"
          variant="high"
          muted={false}
          highlight={theme.text.base}
          agentAlpha={1}
          modelAlpha={1}
          variantAlpha={1}
        />
        <VoiceButton voice={voice} width={size().width} animations />
      </box>
    )
  }
  await using app = await testRender(
    () => (
      <TestTuiContexts>
        <ConfigProvider config={createTuiResolvedConfig()}>
          <ThemeProvider mode="dark" source={emptyThemeSource}>
            <Prompt />
          </ThemeProvider>
        </ConfigProvider>
      </TestTuiContexts>
    ),
    { width: 64, height: 3 },
  )
  await app.flush()
  expect(app.captureCharFrame().trim()).toMatch(/^Build · GPT-6\.1 Sol Fast OpenAI · high +Record$/)
  await voice.toggle()
  await app.flush()
  expect(app.captureCharFrame().trim()).toMatch(
    /^Build · GPT-6\.1 Sol Fast OpenAI · high +[\u2800-\u28ff]{11} \d+:\d\d$/,
  )
  const bar = app.captureCharFrame().search(/[\u2800-\u28ff]/)
  await app.mockMouse.click(bar, 0)
  await app.renderOnce()
  expect(app.captureCharFrame().trim()).toMatch(/^Build · GPT-6\.1 Sol Fast OpenAI · high +[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]$/)
  // The empty cells to the left of the spinner still belong to the same button.
  await app.mockMouse.click(bar + 2, 0)
  expect(voice.state().type).toBe("idle")
  expect(output).toEqual([])

  await voice.toggle()
  app.resize(38, 3)
  await app.renderOnce()
  expect(app.captureCharFrame().trim()).toMatch(/^[^\n]+ ● \d+:\d\d$/)
  expect(app.captureCharFrame()).not.toMatch(/[\u2800-\u28ff]/)
  await app.mockMouse.click(app.captureCharFrame().search(/\d+:\d\d/), 0)
  await app.renderOnce()
  expect(app.captureCharFrame().trim()).toMatch(/[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]$/)
  result.resolve("dictated text")
  await app.renderOnce()
  expect(output).toEqual(["dictated text"])

  acceptsAudio = true
  await app.mockMouse.click(app.captureCharFrame().indexOf("Record"), 0)
  acceptsAudio = false
  await voice.toggle()
  expect(output).toEqual(["dictated text", "attachment"])
  acceptsAudio = true
  await voice.toggle()
  await app.renderOnce()
  await app.mockMouse.click(app.captureCharFrame().indexOf("●"), 0)
  expect(output).toEqual(["dictated text", "attachment", "attachment"])
  expect(cues).toEqual(["start", "stop", "start", "stop", "start", "stop", "start", "stop"])
})

test("cancel aborts transcription and a late result cannot overwrite a new recording", async () => {
  const response = Promise.withResolvers<string>()
  let signal: AbortSignal | undefined
  const inserted: string[] = []
  const root = createRoot((dispose) => ({
    dispose,
    voice: createVoiceInput({
      record: () => ({ stop: () => wav, level: () => 0, kill() {} }),
      acceptsAudio: () => false,
      attach: () => false,
      insert: (text) => {
        inserted.push(text)
        return true
      },
      transcribe: (_audio, received) => {
        signal = received
        return response.promise
      },
      cue() {},
      notify() {},
    }),
  }))
  try {
    await root.voice.toggle()
    const pending = root.voice.toggle()
    expect(signal?.aborted).toBe(false)
    await root.voice.toggle()
    expect(signal?.aborted).toBe(true)
    await root.voice.toggle()
    response.resolve("obsolete text")
    await pending
    expect(root.voice.state().type).toBe("recording")
    expect(inserted).toEqual([])
  } finally {
    root.dispose()
  }
})
