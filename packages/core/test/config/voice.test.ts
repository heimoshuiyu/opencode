import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { ConfigVoice } from "@opencode/schema/config/voice"

describe("ConfigVoice.Info", () => {
  test("bounds conversation context", () => {
    expect(() => Schema.decodeUnknownSync(ConfigVoice.Info)({ context_pairs: 21 })).toThrow()
    expect(Schema.decodeUnknownSync(ConfigVoice.Info)({ context_pairs: 5 }).context_pairs).toBe(5)
  })
})
