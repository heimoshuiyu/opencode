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

  test("rejects client-provided Whisper endpoints and credentials", () => {
    const decode = Schema.decodeUnknownSync(Transcription.Settings, { onExcessProperty: "error" })

    expect(() =>
      decode({
        type: "whisper",
        whisper: { url: "https://example.com", apiKey: "secret" },
      }),
    ).toThrow()
  })
})
