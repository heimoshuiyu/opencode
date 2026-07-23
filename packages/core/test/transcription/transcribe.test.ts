import { beforeEach, describe, expect } from "bun:test"
import type { LLMRequest } from "@opencode/ai/effect"
import { compileRequest, TestLLM } from "@opencode/ai/testing"
import { AppProcess } from "@opencode/util/process"
import { httpClient } from "@opencode/util/effect/app-node-platform"
import { Bus } from "@opencode/core/bus"
import { Config } from "@opencode/core/config"
import { Document } from "@opencode/schema/config"
import { AppNodeBuilder } from "@opencode/core/effect/app-node-builder"
import { llmClient } from "@opencode/core/effect/app-node-platform"
import { ID, Info, Model } from "@opencode/core/model"
import { ModelResolver } from "@opencode/core/model-resolver"
import { Project } from "@opencode/core/project"
import { Provider } from "@opencode/core/provider"
import { Session } from "@opencode/core/session"
import { Transcription } from "@opencode/core/transcription/transcribe"
import { Effect, Layer } from "effect"
import { HttpClient, HttpClientRequest, HttpClientResponse } from "effect/http"
import { testEffect } from "../lib/effect"

const sessionID = Session.ID.make("ses_voice_headers")

const catalog = Info.make({
  ...Info.default(Provider.ID.make("test-provider"), ID.make("voice-model")),
  package: "@opencode/ai/providers/google",
  settings: { apiKey: "test-key" },
  variants: [
    { id: Model.VariantID.make("none"), settings: { thinkingConfig: { includeThoughts: false, thinkingBudget: 0 } } },
  ],
  capabilities: { tools: false, input: ["audio"], output: ["text"] },
})

const requests: LLMRequest[] = []
const uploads: HttpClientRequest.HttpClientRequest[] = []
const client = TestLLM.testLayer({
  fallback: TestLLM.text("transcribed", "t0"),
  transformRequest: (request) => {
    requests.push(request)
    return request
  },
})
const it = testEffect(
  AppNodeBuilder.build(Transcription.node, [
    Config.node.replace(
      Layer.mock(Config.Service, {
        entries: () =>
          Effect.succeed([
            new Document({
              type: "document",
              info: {
                transcription: {
                  whisper: { apiKey: "server-only", allowed_urls: ["https://CUSTOM.example:443"] },
                  hot_words: ["server word"],
                },
              },
            }),
          ]),
      }),
    ),
    Model.node.replace(Layer.mock(Model.Service, { get: () => Effect.succeed(catalog) })),
    Bus.node.replace(Layer.mock(Bus.Service, {})),
    ModelResolver.node.replace(
      Layer.mock(ModelResolver.Service, {
        resolveModel: (selected, variant) =>
          ModelResolver.resolveModel(selected, variant).pipe(
            Effect.map((model) => ({
              model,
              ref: Model.Ref.make({ id: selected.id, providerID: selected.providerID, variant }),
              capabilities: selected.capabilities,
              cost: [],
              limit: { context: 200_000, output: 32_000 },
            })),
          ),
      }),
    ),
    llmClient.replace(client),
    httpClient.replace(
      Layer.succeed(
        HttpClient.HttpClient,
        HttpClient.make((request) => {
          uploads.push(request)
          return Effect.succeed(HttpClientResponse.fromWeb(request, Response.json({ text: "hello" })))
        }),
      ),
    ),
    AppProcess.node.replace(
      Layer.mock(AppProcess.Service, {
        run: () =>
          Effect.succeed({
            command: "ffmpeg",
            exitCode: 0,
            stdout: Buffer.from("mp3-bytes"),
            stderr: Buffer.alloc(0),
            stdoutTruncated: false,
            stderrTruncated: false,
          }),
      }),
    ),
  ]),
)

const settings = Transcription.resolve([], { lalm: { model: "test-provider/voice-model" } })

const transcribe = (session?: {
  readonly id: Session.ID
  readonly parentID?: Session.ID
  readonly projectID: Project.ID
}) =>
  Effect.gen(function* () {
    const voice = yield* Transcription.Service
    yield* voice.transcribe(
      {
        audio: new Uint8Array([1, 2, 3]),
        mime: "audio/wav",
        ...(session ? { session } : {}),
      },
      settings,
    )
    return requests[0]?.headers
  })

const offSafetySettings = [
  { category: "HARM_CATEGORY_HARASSMENT", threshold: "OFF" },
  { category: "HARM_CATEGORY_HATE_SPEECH", threshold: "OFF" },
  { category: "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold: "OFF" },
  { category: "HARM_CATEGORY_DANGEROUS_CONTENT", threshold: "OFF" },
]

beforeEach(() => {
  requests.length = 0
  uploads.length = 0
})

it.effect("uploads normalized audio and context to the selected Whisper endpoint instead of calling the LLM", () =>
  Effect.gen(function* () {
    const voice = yield* Transcription.Service
    const url = "https://example.com/v1/audio/transcriptions"
    const config = Transcription.resolve([{ whisper: { url, apiKey: "test-key" }, hot_words: ["OpenCode"] }], {
      type: "whisper",
      whisper: { language: "zh" },
    })
    const result = yield* voice.transcribe({ audio: new Uint8Array([1]), mime: "audio/wav", prompt: "context" }, config)
    expect(result).toEqual({ text: "hello" })
    expect(requests).toHaveLength(0)
    expect(uploads).toHaveLength(1)
    const [request] = uploads
    expect(request.url).toBe(url)
    expect(request.method).toBe("POST")
    expect(request.headers.authorization).toBe("Bearer test-key")
    if (request.body._tag !== "FormData") throw new Error("expected multipart request")
    const form = request.body.formData
    expect(form.get("model")).toBe("whisper-1")
    expect(form.get("response_format")).toBe("json")
    expect(form.get("language")).toBe("zh")
    expect(form.get("prompt")).toBe("context\nOpenCode")
    const file = form.get("file")
    if (!(file instanceof File)) throw new Error("expected audio file")
    expect(file.name).toBe("audio.mp3")
    expect(file.type).toBe("audio/mpeg")
    expect(yield* Effect.promise(() => file.text())).toBe("mp3-bytes")
  }),
)

it.effect("explicit Whisper destinations are exact, cannot borrow the server key, and honor empty hot words", () =>
  Effect.gen(function* () {
    const voice = yield* Transcription.Service
    const url = "https://custom.example"
    const base = { type: "whisper" } as const
    const rejected = yield* Effect.flip(voice.resolve(base, { whisper: { url: url + "/other" } }))
    expect(rejected._tag).toBe("Transcription.ConfigurationError")
    const settings = yield* voice.resolve(base, { whisper: { url }, hot_words: [], context_pairs: 1 })
    expect(settings.context_pairs).toBe(1)
    expect(settings.hot_words).toEqual([])
    yield* voice.transcribe({ audio: new Uint8Array([1]), mime: "audio/wav" }, settings)
    expect(uploads[0]?.url).toBe(url + "/")
    expect(uploads[0]?.headers.authorization).toBeUndefined()
    const withKey = yield* voice.resolve(base, { whisper: { url, apiKey: "client-only" } })
    yield* voice.transcribe({ audio: new Uint8Array([1]), mime: "audio/wav" }, withKey)
    expect(uploads[1]?.headers.authorization).toBe("Bearer client-only")
  }),
)

describe("Transcription.transcribe LALM session identity", () => {
  it.effect("carries session headers when a context session is provided", () =>
    Effect.gen(function* () {
      const headers = yield* transcribe({ id: sessionID, projectID: Project.ID.global })

      expect(headers?.["x-opencode-session"]).toBe(sessionID)
      expect(headers?.["x-session-affinity"]).toBe(sessionID)
      expect(headers?.["X-Session-Id"]).toBe(sessionID)
      expect(headers?.["x-opencode-project"]).toBe(Project.ID.global)
      expect(headers?.["x-opencode-client"]).toBe("opencode")
    }),
  )

  it.effect("sends the selected variant's thinking settings alongside transcription safety settings", () =>
    Effect.gen(function* () {
      const voice = yield* Transcription.Service
      yield* voice.transcribe(
        { audio: new Uint8Array([1]), mime: "audio/wav" },
        { ...settings, lalm: { ...settings.lalm, variant: "none" } },
      )
      const request = yield* compileRequest(requests[0])
      expect(request.body).toMatchObject({
        generationConfig: { thinkingConfig: { includeThoughts: false, thinkingBudget: 0 } },
        safetySettings: offSafetySettings,
      })
    }),
  )

  ;(it.effect("routes a child session on its parent affinity", () =>
    Effect.gen(function* () {
      const parentID = Session.ID.make("ses_voice_parent")
      const headers = yield* transcribe({ id: sessionID, parentID, projectID: Project.ID.global })

      expect(headers?.["x-session-affinity"]).toBe(parentID)
      expect(headers?.["X-Session-Id"]).toBe(parentID)
      expect(headers?.["x-opencode-session"]).toBe(parentID)
      expect(headers?.["x-parent-session-id"]).toBe(parentID)
    }),
  ),
    it.effect("synthesizes a routing identity without a context session", () =>
      Effect.gen(function* () {
        const headers = yield* transcribe()

        expect(headers?.["x-opencode-session"]).toMatch(/^ses_[0-9A-Za-z]+$/)
        expect(headers?.["x-session-affinity"]).toBe(headers?.["x-opencode-session"])
        expect(headers?.["x-opencode-project"]).toBe(Project.ID.global)
        expect(headers?.["x-parent-session-id"]).toBeUndefined()
        expect(headers?.["x-opencode-client"]).toBe("opencode")
      }),
    ))

  it.effect("sends flat Gemini safety settings that survive protocol decoding", () =>
    Effect.gen(function* () {
      yield* transcribe()

      // Nested provider keys are stripped by protocol option decoding, which
      // previously left Gemini transcription requests with no safety settings.
      expect(requests[0].providerOptions).toEqual({ safetySettings: offSafetySettings })
    }),
  )
})
