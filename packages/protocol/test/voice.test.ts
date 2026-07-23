import { describe, expect, test } from "bun:test"
import { Schema } from "effect"
import { Session } from "@opencode/schema/session"
import { TranscribeRequest, TranscribeResponse } from "../src/groups/voice.js"

describe("VoiceTranscribeRequest", () => {
  test("uses an explicitly named context session", () => {
    expect(
      Schema.decodeUnknownSync(TranscribeRequest)({
        audio: "YXVkaW8=",
        mime: "audio/wav",
        contextSessionID: "ses_context",
      }),
    ).toEqual({
      audio: "YXVkaW8=",
      mime: "audio/wav",
      contextSessionID: Session.ID.make("ses_context"),
    })
  })

  test("rejects the ambiguous legacy session field", () => {
    const decode = Schema.decodeUnknownSync(TranscribeRequest, { onExcessProperty: "error" })
    expect(() => decode({ audio: "YXVkaW8=", mime: "audio/wav", sessionID: "ses_context" })).toThrow()
  })
})

describe("VoiceTranscribeResponse", () => {
  test("omits the usage object entirely when the backend reports no tokens", () => {
    expect(Schema.encodeSync(TranscribeResponse)({ text: "hi" })).toEqual({ text: "hi" })
  })

  test("encodes usage as snake_case integers and omits unknown counts", () => {
    expect(
      Schema.encodeSync(TranscribeResponse)({
        text: "hi",
        usage: { input_tokens: 12, output_tokens: undefined },
      }),
    ).toEqual({ text: "hi", usage: { input_tokens: 12 } })
  })

  test("keeps both counts when the backend reports them", () => {
    expect(
      Schema.encodeSync(TranscribeResponse)({ text: "hi", usage: { input_tokens: 1234, output_tokens: 567 } }),
    ).toEqual({ text: "hi", usage: { input_tokens: 1234, output_tokens: 567 } })
  })
})
