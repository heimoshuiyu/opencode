import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { ConfigVoice } from "@opencode/schema/config/voice"
import { resolve } from "@opencode/core/voice/index"

const voice = Schema.decodeUnknownSync(ConfigVoice.Info)
const whisper = Schema.decodeUnknownSync(ConfigVoice.Whisper)

describe("Voice.resolve", () => {
  test("merges nested settings from low to high priority", () => {
    const resolved = resolve([
      voice({
        type: "lalm",
        lalm: { model: "google/gemini-2.5-flash", system: "system" },
        whisper: whisper({ url: "https://example.com", apiKey: "secret" }),
        context_pairs: 5,
        hot_words: ["global"],
      }),
      voice({
        lalm: { instruction: "instruction" },
        hot_words: ["opencode"],
      }),
    ])

    expect(resolved).toEqual({
      type: "lalm",
      lalm: {
        model: "google/gemini-2.5-flash",
        system: "system",
        instruction: "instruction",
      },
      whisper: { url: "https://example.com", apiKey: "secret" },
      hot_words: ["global", "opencode"],
      context_pairs: 5,
    })
  })

  test("overlays request settings without replacing server credentials", () => {
    const resolved = resolve(
      [
        voice({
          type: "whisper",
          whisper: whisper({
            url: "https://example.com/transcribe",
            apiKey: "secret",
            model: "whisper-1",
          }),
          hot_words: ["server"],
        }),
      ],
      {
        whisper: { model: "distil-whisper", language: "zh" },
        hot_words: ["request"],
      },
    )

    expect(resolved).toEqual({
      type: "whisper",
      whisper: {
        url: "https://example.com/transcribe",
        apiKey: "secret",
        model: "distil-whisper",
        language: "zh",
      },
      lalm: {},
      hot_words: ["server", "request"],
      context_pairs: 3,
    })
  })

  test("supports request-only LALM selection and absent configuration", () => {
    expect(resolve([], { type: "lalm", lalm: { model: "google/gemini-2.5-flash" } })).toEqual({
      type: "lalm",
      whisper: {},
      lalm: { model: "google/gemini-2.5-flash" },
      hot_words: [],
      context_pairs: 3,
    })
    expect(resolve([])).toEqual({
      type: "lalm",
      whisper: {},
      lalm: {},
      hot_words: [],
      context_pairs: 3,
    })
  })
})
