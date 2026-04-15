import { Effect, Layer, Context } from "effect"
import { type Info } from "@/config/config"
import { SessionID } from "@/session/schema"
import { Lalm } from "@/voice/lalm"
import { Whisper } from "@/voice/whisper"
import { VoiceError } from "@/voice/error"
import { Config } from "@/config/config"
import { Session } from "@/session/session"
import { Provider } from "@/provider/provider"
import { ChildProcessSpawner } from "effect/unstable/process"
import { HttpClient, FetchHttpClient } from "effect/unstable/http"
import { CrossSpawnSpawner } from "@opencode-ai/core/cross-spawn-spawner"

export type TranscribeInput = {
  file: File
  mime: string
  sessionID?: SessionID
  prompt?: string
  signal?: AbortSignal
  voice?: Info["voice"]
}

export interface Interface {
  readonly transcribe: (input: TranscribeInput) => Effect.Effect<{ text: string }, VoiceError.Error>
}

export class Service extends Context.Service<Service, Interface>()("@opencode/Voice") {}

export const layer = Layer.effect(
  Service,
  Effect.gen(function* () {
    const config = yield* Config.Service
    const session = yield* Session.Service
    const spawner = yield* ChildProcessSpawner.ChildProcessSpawner
    const provider = yield* Provider.Service
    const http = yield* HttpClient.HttpClient

    const whisper = Whisper.make({ config, session, spawner, http })
    const lalm = Lalm.make({ config, session, spawner, provider })

    const transcribe = Effect.fn("Voice.transcribe")(function* (input: TranscribeInput) {
      return yield* (input.voice?.type ?? "whisper") === "lalm"
        ? lalm.transcribe(input)
        : whisper.transcribe(input)
    })

    return Service.of({ transcribe })
  }),
)

export const defaultLayer = Layer.suspend(() =>
  layer.pipe(
    Layer.provide(CrossSpawnSpawner.defaultLayer),
    Layer.provide(Provider.defaultLayer),
    Layer.provide(Session.defaultLayer),
    Layer.provide(Config.defaultLayer),
    Layer.provide(FetchHttpClient.layer),
  ),
)

export * as Voice from "."
