import { Effect, Stream } from "effect"
import { ChildProcess, ChildProcessSpawner } from "effect/unstable/process"
import { VoiceError } from "@/voice/error"

export interface Deps {
  spawner: ChildProcessSpawner.ChildProcessSpawner["Service"]
}

export const make = (deps: Deps) => {
  const toWavOrMp3 = Effect.fn("Voice.toWavOrMp3")(
    function* (input: { buffer: ArrayBuffer; mime: string }) {
      const isWav = input.mime.includes("wav")
      const isMp3 = input.mime.includes("mpeg") || input.mime.includes("mp3")
      if (isWav || isMp3) {
        const name = isWav ? "audio.wav" : "audio.mp3"
        const mime = isWav ? "audio/wav" : "audio/mpeg"
        return { buffer: input.buffer, name, mime }
      }

      const handle = yield* deps.spawner
        .spawn(
          ChildProcess.make("ffmpeg", [
            "-y", "-f", "webm", "-i", "pipe:0",
            "-ac", "1", "-ar", "16000", "-f", "mp3", "pipe:1",
          ], {
            stdin: Stream.make(new Uint8Array(input.buffer)),
            stdout: "pipe",
            stderr: "pipe",
          }),
        )
        .pipe(
          Effect.mapError((cause) =>
            new VoiceError({
              message: "Failed to start ffmpeg for voice audio conversion",
              cause,
            }),
          ),
        )

      const [stdout, stderr, code] = yield* Effect.all(
        [
          Stream.runFold(
            handle.stdout,
            () => ({ chunks: Array<Uint8Array>(), bytes: 0 }),
            (acc, chunk) => {
              acc.chunks.push(chunk)
              acc.bytes += chunk.length
              return acc
            },
          ).pipe(Effect.map((result) => Buffer.concat(result.chunks, result.bytes))),
          Stream.mkString(Stream.decodeText(handle.stderr)),
          handle.exitCode,
        ],
        { concurrency: 3 },
      ).pipe(
        Effect.mapError((cause) =>
          new VoiceError({
            message: "Failed to convert voice audio with ffmpeg",
            cause,
          }),
        ),
      )

      if (code !== 0) {
        return yield* new VoiceError({
          message: `ffmpeg conversion failed (exit code ${code}): ${stderr.trim() || "unknown error"}`,
        })
      }
      if (!stdout.byteLength) {
        return yield* new VoiceError({ message: "ffmpeg conversion produced no audio output" })
      }

      return {
        buffer: stdout.buffer.slice(stdout.byteOffset, stdout.byteOffset + stdout.byteLength),
        name: "audio.mp3",
        mime: "audio/mpeg",
      } as const
    },
    Effect.scoped,
  )

  const prepareAudio = Effect.fn("Voice.prepareAudio")(function* (file: File, mime: string) {
    const content = yield* Effect.tryPromise({
      try: () => file.arrayBuffer(),
      catch: (cause) => new VoiceError({ message: "Failed to read voice audio file", cause }),
    })
    return yield* toWavOrMp3({ buffer: content, mime })
  })

  return { toWavOrMp3, prepareAudio }
}

export const buildPrompt = (input: { prompt?: string; assistant?: string }) => {
  const head = input.assistant?.trim() ?? ""
  const tail = input.prompt?.trim() ?? ""
  if (!head) return tail
  if (!tail) return head
  return `${head} ${tail}`
}

export * as VoiceCommon from "./common"
