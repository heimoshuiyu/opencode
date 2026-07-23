import { beforeEach, describe, expect } from "bun:test"
import { LanguageModel, type LLMRequest } from "@opencode/ai"
import { OpenAIChat } from "@opencode/ai/protocols"
import { TestLLM } from "@opencode/ai/testing"
import { AppProcess } from "@opencode/util/process"
import { Bus } from "@opencode/core/bus"
import { Config } from "@opencode/core/config"
import { AppNodeBuilder } from "@opencode/core/effect/app-node-builder"
import { llmClient } from "@opencode/core/effect/app-node-platform"
import { httpClient } from "@opencode/util/effect/app-node-platform"
import { ID, Info, Model } from "@opencode/core/model"
import { ModelResolver } from "@opencode/core/model-resolver"
import { Project } from "@opencode/core/project"
import { Provider } from "@opencode/core/provider"
import { Session } from "@opencode/core/session"
import { Voice } from "@opencode/core/voice/index"
import { Effect, Layer } from "effect"
import { FetchHttpClient } from "effect/unstable/http"
import { testEffect } from "../lib/effect"

const sessionID = Session.ID.make("ses_voice_headers")

const catalog = Info.make({
  ...Info.default(Provider.ID.make("test-provider"), ID.make("voice-model")),
  package: "@opencode/ai/providers/openai-compatible",
  capabilities: { tools: false, input: ["audio"], output: ["text"] },
})
const runtime = LanguageModel.make({ id: "voice-model", provider: "test-provider", route: OpenAIChat.route })

const requests: LLMRequest[] = []
const client = TestLLM.testLayer({
  fallback: TestLLM.text("transcribed", "t0"),
  transformRequest: (request) => {
    requests.push(request)
    return request
  },
})
const it = testEffect(
  AppNodeBuilder.build(Voice.node, [
    Config.node.replace(Layer.mock(Config.Service, { entries: () => Effect.succeed([]) })),
    Model.node.replace(Layer.mock(Model.Service, { get: () => Effect.succeed(catalog) })),
    Bus.node.replace(Layer.mock(Bus.Service, {})),
    ModelResolver.node.replace(
      Layer.mock(ModelResolver.Service, {
        resolveModel: () =>
          Effect.succeed({
            model: runtime,
            ref: Model.Ref.make({ id: catalog.id, providerID: catalog.providerID }),
            capabilities: catalog.capabilities,
            cost: [],
            limit: { context: 200_000, output: 32_000 },
          }),
      }),
    ),
    llmClient.replace(client),
    httpClient.replace(FetchHttpClient.layer),
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

const settings = Voice.resolve([], { type: "lalm", lalm: { model: "test-provider/voice-model" } })

const transcribe = (session?: { readonly id: Session.ID; readonly parentID?: Session.ID; readonly projectID: Project.ID }) =>
  Effect.gen(function* () {
    const voice = yield* Voice.Service
    yield* voice.transcribe(
      {
        audio: new Uint8Array([1, 2, 3]),
        mime: "audio/wav",
        ...(session ? { session } : {}),
      },
      settings,
    )
    return requests[0]?.http?.headers
  })

const offSafetySettings = [
  { category: "HARM_CATEGORY_HARASSMENT", threshold: "OFF" },
  { category: "HARM_CATEGORY_HATE_SPEECH", threshold: "OFF" },
  { category: "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold: "OFF" },
  { category: "HARM_CATEGORY_DANGEROUS_CONTENT", threshold: "OFF" },
]

beforeEach(() => {
  requests.length = 0
})

describe("Voice.transcribe LALM session identity", () => {
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

  it.effect("routes a child session on its parent affinity", () =>
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
  )

  it.effect("sends flat Gemini safety settings that survive protocol decoding", () =>
    Effect.gen(function* () {
      yield* transcribe()

      // Nested provider keys are stripped by protocol option decoding, which
      // previously left Gemini transcription requests with no safety settings.
      expect(requests[0].providerOptions).toEqual({ safetySettings: offSafetySettings })
    }),
  )
})
