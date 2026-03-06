import { describe, expect } from "bun:test"
import { Effect, Exit, Layer, Scope, Stream } from "effect"
import { ChildProcess } from "effect/unstable/process"
import { ChildProcessSpawner } from "effect/unstable/process/ChildProcessSpawner"
import { CrossSpawnSpawner } from "@opencode-ai/core/cross-spawn-spawner"
import { BackgroundJobManager } from "@/tool/background-job-manager"
import { TestInstance } from "../fixture/fixture"
import { testEffect } from "../lib/effect"

const it = testEffect(BackgroundJobManager.defaultLayer.pipe(Layer.provideMerge(CrossSpawnSpawner.defaultLayer)))

describe("tool.background-job-manager", () => {
  it.instance("captures output from a completed job", () =>
    Effect.gen(function* () {
      const jobs = yield* BackgroundJobManager.Service
      const test = yield* TestInstance
      const job = yield* jobs.start({
        command: "print background output",
        cwd: test.directory,
        process: ChildProcess.make(process.execPath, ["-e", "console.log('background output')"], {
          cwd: test.directory,
        }),
      })

      const waited = yield* jobs.wait({ jobId: job.id, timeout: 5_000 })

      expect(waited.timedOut).toBe(false)
      expect(waited.info.status).toBe("completed")
      expect(waited.info.output).toContain("background output")
    }),
  )

  it.instance("kills a running job", () =>
    Effect.gen(function* () {
      const jobs = yield* BackgroundJobManager.Service
      const test = yield* TestInstance
      const job = yield* jobs.start({
        command: "run until killed",
        cwd: test.directory,
        process: ChildProcess.make(process.execPath, ["-e", "setInterval(() => {}, 1000)"], {
          cwd: test.directory,
        }),
      })

      const killed = yield* jobs.kill({ jobId: job.id, force: true })

      expect(killed.status).toBe("killed")
    }),
  )

  it.instance("adopts a process and waits for completion", () =>
    Effect.gen(function* () {
      const jobs = yield* BackgroundJobManager.Service
      const spawner = yield* ChildProcessSpawner
      const test = yield* TestInstance

      const proc = ChildProcess.make(process.execPath, ["-e", "console.log('adopted output')"], {
        cwd: test.directory,
      })
      const scope = yield* Scope.make()
      const handle = yield* Scope.provide(scope)(spawner.spawn(proc))

      const adopted = yield* jobs.adopt({
        handle,
        scope,
        command: "adopted test",
        cwd: test.directory,
        output: "initial output\n",
      })

      const waited = yield* jobs.wait({ jobId: adopted.info.id, timeout: 5_000 })

      expect(waited.timedOut).toBe(false)
      expect(waited.info.status).toBe("completed")
      expect(waited.info.output).toContain("initial output")
      expect(waited.info.output).toContain("adopted output")
    }),
  )

  // Regression: foreground stream consumer scope close used to destroy proc.stdout/stderr
  // (via closeOnDone defaulting to true), making the adopted consumer see an empty stream.
  it.instance("adopts a process after foreground consumer is interrupted and continues capturing output", () =>
    Effect.gen(function* () {
      const jobs = yield* BackgroundJobManager.Service
      const spawner = yield* ChildProcessSpawner
      const test = yield* TestInstance

      const proc = ChildProcess.make(
        process.execPath,
        [
          "-e",
          `console.log("first");setTimeout(()=>console.log("second"),500);setTimeout(()=>console.log("third"),1000)`,
        ],
        { cwd: test.directory },
      )

      const processScope = yield* Scope.make()
      const foregroundScope = yield* Scope.make()
      const handle = yield* Scope.provide(processScope)(spawner.spawn(proc))

      // Simulate foreground stream consumer (same pattern as shell.ts auto-background flow)
      const foregroundChunks: string[] = []
      yield* Stream.runForEach(Stream.decodeText(handle.all), (chunk) =>
        Effect.sync(() => foregroundChunks.push(chunk)),
      ).pipe(Effect.forkIn(foregroundScope))

      // Let the process produce "first" output
      yield* Effect.sleep(300)

      // Close foreground scope — this used to destroy proc.stdout/stderr
      yield* Scope.close(foregroundScope, Exit.void).pipe(Effect.ignore)

      const adopted = yield* jobs.adopt({
        handle,
        scope: processScope,
        command: "foreground-to-background regression test",
        cwd: test.directory,
        output: foregroundChunks.join(""),
      })

      const waited = yield* jobs.wait({ jobId: adopted.info.id, timeout: 10_000 })

      expect(waited.timedOut).toBe(false)
      expect(waited.info.status).toBe("completed")
      expect(waited.info.output).toContain("first")
      expect(waited.info.output).toContain("second")
      expect(waited.info.output).toContain("third")
    }),
  )
})
