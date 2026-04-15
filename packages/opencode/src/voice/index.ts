import { Effect, Layer, Context } from "effect"
import { type Info } from "@/config/config"
import { Lalm } from "@/voice/lalm"
import { Whisper } from "@/voice/whisper"
import { type VoiceError } from "@/voice/error"
import { VoiceConfig } from "@/voice/config"
import { Config } from "@/config/config"
import { Provider } from "@/provider/provider"
import { ChildProcessSpawner } from "effect/unstable/process"
import { HttpClient, FetchHttpClient } from "effect/unstable/http"
import { CrossSpawnSpawner } from "@opencode-ai/core/cross-spawn-spawner"

export type TranscribeInput = {
  file: File
  mime: string
  prompt?: string
  signal?: AbortSignal
  voice?: Info["voice"]
}

export interface Interface {
  readonly transcribe: (input: TranscribeInput) => Effect.Effect<{ text: string }, VoiceError>
}

export class Service extends Context.Service<Service, Interface>()("@opencode/Voice") {}

export const layer = Layer.effect(
  Service,
  Effect.gen(function* () {
    const config = yield* Config.Service
    const spawner = yield* ChildProcessSpawner.ChildProcessSpawner
    const provider = yield* Provider.Service
    const http = yield* HttpClient.HttpClient

    const whisper = Whisper.make({ config, spawner, http })
    const lalm = Lalm.make({ config, spawner, provider })

    const transcribe = Effect.fn("Voice.transcribe")(function* (input: TranscribeInput) {
      const cfg = yield* config.get()
      const type = VoiceConfig.providerType(input.voice, cfg.voice)
      return yield* type === "lalm"
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
    Layer.provide(Config.defaultLayer),
    Layer.provide(FetchHttpClient.layer),
  ),
)

export * as Voice from "."
