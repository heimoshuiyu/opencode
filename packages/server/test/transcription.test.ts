import { expect } from "bun:test"
import { Transcription } from "@opencode/core/transcription/transcribe"
import { Effect, Layer } from "effect"
import { tmpdir } from "../../core/test/fixture/tmpdir"
import { it } from "../../core/test/lib/effect"
import { ServerFetch } from "../src/fetch"

it.live("audio-only transcription ignores even explicitly supplied context before looking up the session", () =>
  Effect.gen(function* () {
    const tmp = yield* Effect.acquireDisposable(Effect.promise(() => tmpdir("opencode-audio-only-")))
    const calls: Transcription.TranscribeInput[] = []
    const handler = yield* ServerFetch.make(
      {
        database: { path: ":memory:" },
        config: { directory: tmp.path },
        fs: { filewatcher: false },
        models: { fetch: false },
      },
      {
        overrides: [
          Transcription.node.replace(
            Layer.mock(Transcription.Service, {
              resolve: (settings, overrides) =>
                Effect.succeed(Transcription.resolve([{ context_pairs: overrides?.context_pairs }], settings)),
              transcribe: (input) => {
                calls.push(input)
                return Effect.succeed({ text: "recording" })
              },
            }),
          ),
        ],
      },
    )
    const url = new URL("http://opencode.local/api/experimental/transcription")
    url.searchParams.set("location[directory]", tmp.path)
    const post = (pairs: number) =>
      Effect.promise(() =>
        handler(
          new Request(url, {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
              audio: "AQ==",
              mime: "audio/wav",
              overrides: { context_pairs: pairs },
              contextSessionID: "ses_missing",
              prompt: "private draft",
              images: ["data:image/png;base64,AQ=="],
            }),
          }),
        ),
      )
    expect((yield* post(0)).status).toBe(200)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ audio: new Uint8Array([1]), mime: "audio/wav" })
    expect(calls[0]?.session).toBeUndefined()
    expect(calls[0]?.prompt).toBeUndefined()
    expect(calls[0]?.images).toBeUndefined()
    expect((yield* post(1)).status).toBe(404)
    expect(calls).toHaveLength(1)
  }),
)
