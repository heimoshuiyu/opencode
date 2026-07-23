import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { ConfigTranscription } from "@opencode/schema/config/transcription"

describe("ConfigTranscription.Info", () => {
  test("bounds conversation context", () => {
    expect(() => Schema.decodeUnknownSync(ConfigTranscription.Info)({ context_pairs: 21 })).toThrow()
    expect(Schema.decodeUnknownSync(ConfigTranscription.Info)({ context_pairs: 5 }).context_pairs).toBe(5)
  })
})
