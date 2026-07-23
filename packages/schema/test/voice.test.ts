import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { Voice } from "../src/voice.js"

describe("Voice.Settings", () => {
  test("encodes settings without undefined fields", () => {
    const value = Schema.decodeUnknownSync(Voice.Settings)({
      type: "lalm",
      lalm: { model: "google/gemini-2.5-flash" },
    })

    expect(Schema.encodeSync(Voice.Settings)(value)).toEqual({
      type: "lalm",
      lalm: { model: "google/gemini-2.5-flash" },
    })
  })

  test("rejects client-provided Whisper endpoints and credentials", () => {
    const decode = Schema.decodeUnknownSync(Voice.Settings, { onExcessProperty: "error" })

    expect(() =>
      decode({
        type: "whisper",
        whisper: { url: "https://example.com", apiKey: "secret" },
      }),
    ).toThrow()
  })
})
