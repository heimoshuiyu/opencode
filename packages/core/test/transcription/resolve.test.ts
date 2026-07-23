import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { ConfigTranscription } from "@opencode/schema/config/transcription"
import { resolve } from "@opencode/core/transcription/transcribe"

const voice = Schema.decodeUnknownSync(ConfigTranscription.Info)

describe("Transcription.resolve", () => {
  test("merges backend settings and appends hot words in priority order", () => {
    const resolved = resolve([
      voice({
        type: "whisper",
        whisper: { url: "https://example.com/transcribe", apiKey: "test-key", model: "asr" },
        lalm: { model: "google/gemini-2.5-flash", variant: "none", system: "system" },
        context_pairs: 5,
        hot_words: ["global"],
      }),
      voice({
        type: "lalm",
        whisper: { language: "zh" },
        lalm: { instruction: "instruction" },
        hot_words: ["opencode"],
      }),
    ])

    expect(resolved).toEqual({
      type: "lalm",
      whisper: { url: "https://example.com/transcribe", apiKey: "test-key", model: "asr", language: "zh" },
      lalm: { model: "google/gemini-2.5-flash", variant: "none", system: "system", instruction: "instruction" },
      hot_words: ["global", "opencode"],
      context_pairs: 5,
    })
  })

  test("overlays request settings on server configuration", () => {
    const resolved = resolve(
      [
        voice({
          type: "lalm",
          whisper: { url: "https://example.com/transcribe", apiKey: "test-key" },
          lalm: { model: "google/gemini-2.5-flash", variant: "low", system: "system" },
          hot_words: ["server"],
        }),
      ],
      {
        type: "whisper",
        whisper: { model: "distil-whisper", language: "zh" },
        lalm: { model: "test-provider/voice-model", variant: "high" },
        hot_words: ["request"],
      },
    )

    expect(resolved).toEqual({
      type: "whisper",
      whisper: { url: "https://example.com/transcribe", apiKey: "test-key", model: "distil-whisper", language: "zh" },
      lalm: { model: "test-provider/voice-model", variant: "high", system: "system" },
      hot_words: ["server", "request"],
      context_pairs: 3,
    })
  })

  test("supports request-only model selection and absent configuration", () => {
    expect(resolve([], { lalm: { model: "google/gemini-2.5-flash" } })).toEqual({
      type: "lalm",
      lalm: { model: "google/gemini-2.5-flash" },
      whisper: {},
      hot_words: [],
      context_pairs: 3,
    })
    expect(resolve([])).toEqual({
      type: "lalm",
      lalm: {},
      whisper: {},
      hot_words: [],
      context_pairs: 3,
    })
  })
})
