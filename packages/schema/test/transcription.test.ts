import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { Transcription } from "../src/transcription.js"

describe("Transcription.Settings", () => {
  test("encodes settings without undefined fields", () => {
    const value = Schema.decodeUnknownSync(Transcription.Settings)({
      type: "lalm",
      lalm: { model: "google/gemini-2.5-flash" },
    })

    expect(Schema.encodeSync(Transcription.Settings)(value)).toEqual({
      type: "lalm",
      lalm: { model: "google/gemini-2.5-flash" },
    })
  })

  test("keeps the endpoint and credentials out of Whisper request overrides", () => {
    const whisper = { model: "whisper-1", language: "en" }
    expect(
      Schema.decodeUnknownSync(Transcription.Settings)({
        type: "whisper",
        whisper: { ...whisper, url: "https://example.com", apiKey: "test-key" },
      }),
    ).toEqual({ type: "whisper", whisper })
  })
})
