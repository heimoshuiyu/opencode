import { randomBytes } from "node:crypto"
import * as Log from "@opencode-ai/core/util/log"
import { InstanceState } from "@/effect/instance-state"
import {
  Cause,
  Clock,
  Context,
  Deferred,
  Effect,
  Exit,
  Fiber,
  Layer,
  Schema,
  Scope,
  Stream,
  SynchronizedRef,
} from "effect"
import { ChildProcess } from "effect/unstable/process"
import type { ChildProcessHandle } from "effect/unstable/process/ChildProcessSpawner"
import { ChildProcessSpawner } from "effect/unstable/process/ChildProcessSpawner"

export type Status = "running" | "completed" | "killed" | "failed"

export type Info = {
  readonly id: string
  readonly pid: number
  readonly command: string
  readonly cwd: string
  readonly startedAt: number
  readonly completedAt?: number
  readonly status: Status
  readonly exitCode?: number
  readonly output: string
  readonly description?: string
  readonly error?: string
}

export type StartInput = {
  readonly command: string
  readonly cwd: string
  readonly description?: string
  readonly process: ChildProcess.Command
  readonly output?: string
}

export type AdoptInput = {
  readonly handle: ChildProcessHandle
  readonly scope: Scope.Scope
  readonly command: string
  readonly cwd: string
  readonly description?: string
  readonly output: string
}

export type AdoptResult = {
  readonly info: Info
}

export type KillInput = {
  readonly jobId: string
  readonly force: boolean
}

export type WaitInput = {
  readonly jobId: string
  readonly timeout: number
}

export type WaitResult = {
  readonly info: Info
  readonly timedOut: boolean
}

export type Output = {
  readonly output: string
  readonly status: Status
  readonly completed: boolean
}

export class MaxJobsReachedError extends Schema.TaggedErrorClass<MaxJobsReachedError>()(
  "BackgroundJobManagerMaxJobsReachedError",
  { max: Schema.Number },
) {}

export class JobNotFoundError extends Schema.TaggedErrorClass<JobNotFoundError>()(
  "BackgroundJobManagerJobNotFoundError",
  {
    jobId: Schema.String,
  },
) {}

export class JobStartFailedError extends Schema.TaggedErrorClass<JobStartFailedError>()(
  "BackgroundJobManagerJobStartFailedError",
  {
    command: Schema.String,
    cause: Schema.Defect,
  },
) {}

export class JobKillFailedError extends Schema.TaggedErrorClass<JobKillFailedError>()(
  "BackgroundJobManagerJobKillFailedError",
  {
    jobId: Schema.String,
    cause: Schema.Defect,
  },
) {}

export type Error = MaxJobsReachedError | JobNotFoundError | JobStartFailedError | JobKillFailedError

export interface Interface {
  readonly start: (input: StartInput) => Effect.Effect<Info, MaxJobsReachedError | JobStartFailedError>
  readonly adopt: (input: AdoptInput) => Effect.Effect<AdoptResult, MaxJobsReachedError>
  readonly get: (jobId: string) => Effect.Effect<Info | undefined>
  readonly list: () => Effect.Effect<Info[]>
  readonly running: () => Effect.Effect<Info[]>
  readonly kill: (input: KillInput) => Effect.Effect<Info, JobNotFoundError | JobKillFailedError>
  readonly output: (jobId: string) => Effect.Effect<Output, JobNotFoundError>
  readonly wait: (input: WaitInput) => Effect.Effect<WaitResult, JobNotFoundError>
  readonly cleanupCompleted: () => Effect.Effect<void>
}

export class Service extends Context.Service<Service, Interface>()("@opencode/BackgroundJobManager") {}

type Active = {
  readonly info: Info
  readonly done: Deferred.Deferred<Info>
  readonly handle: ChildProcessHandle
  readonly scope: Scope.Scope
  readonly killRequested: boolean
  readonly fiber?: Fiber.Fiber<void, never>
}

type State = {
  readonly jobs: SynchronizedRef.SynchronizedRef<Map<string, Active>>
  readonly scope: Scope.Scope
}

type FinishResult = {
  readonly info?: Info
  readonly done?: Deferred.Deferred<Info>
  readonly scope?: Scope.Scope
}

const MAX_CONCURRENT_JOBS = 50
const JOB_RETENTION_TIME = 8 * 60 * 60 * 1000
const KILL_GRACE_PERIOD = "3 seconds"
const log = Log.create({ service: "background-job-manager" })

function snapshot(job: Active): Info {
  return { ...job.info }
}

function generateJobId(jobs: Map<string, Active>): string {
  const id = randomBytes(2).toString("hex").toUpperCase()
  if (!jobs.has(id)) return id
  return generateJobId(jobs)
}

function errorText(error: unknown) {
  if (error instanceof globalThis.Error) return error.message
  return String(error)
}

export const layer: Layer.Layer<Service, never, ChildProcessSpawner> = Layer.effect(
  Service,
  Effect.gen(function* () {
    const spawner = yield* ChildProcessSpawner
    const state = yield* InstanceState.make<State>(
      Effect.fn("BackgroundJobManager.state")(function* (_ctx) {
        const jobs = yield* SynchronizedRef.make(new Map<string, Active>())
        const scope = yield* Scope.Scope
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* Effect.forEach(
              Array.from((yield* SynchronizedRef.get(jobs)).values()),
              (job) =>
                Effect.gen(function* () {
                  if (job.fiber) yield* Fiber.interrupt(job.fiber).pipe(Effect.ignore)
                  if (job.info.status === "running") {
                    yield* job.handle.kill({ forceKillAfter: KILL_GRACE_PERIOD }).pipe(Effect.ignore)
                  }
                  yield* Scope.close(job.scope, Exit.void).pipe(Effect.ignore)
                }),
              { concurrency: "unbounded" },
            )
          }),
        )
        return { jobs, scope }
      }),
    )

    const getActive = Effect.fnUntraced(function* (jobId: string) {
      return (yield* SynchronizedRef.get((yield* InstanceState.get(state)).jobs)).get(jobId)
    })

    const appendOutput = Effect.fn("BackgroundJobManager.appendOutput")(function* (jobId: string, chunk: string) {
      yield* SynchronizedRef.update((yield* InstanceState.get(state)).jobs, (jobs) => {
        const job = jobs.get(jobId)
        if (!job || job.info.status !== "running") return jobs
        return new Map(jobs).set(jobId, {
          ...job,
          info: {
            ...job.info,
            output: job.info.output + chunk,
          },
        })
      })
    })

    const cleanupJob = Effect.fn("BackgroundJobManager.cleanupJob")(function* (jobId: string) {
      const job = yield* SynchronizedRef.modify(
        (yield* InstanceState.get(state)).jobs,
        (jobs): readonly [Active | undefined, Map<string, Active>] => {
          const job = jobs.get(jobId)
          if (!job || job.info.status === "running") return [undefined, jobs]
          const next = new Map(jobs)
          next.delete(jobId)
          return [job, next]
        },
      )
      if (!job) return
      log.info("Cleaning up job", {
        jobId,
        status: job.info.status,
        age: (yield* Clock.currentTimeMillis) - job.info.startedAt,
      })
      yield* Scope.close(job.scope, Exit.void).pipe(Effect.ignore)
    })

    const scheduleCleanup = Effect.fn("BackgroundJobManager.scheduleCleanup")(function* (jobId: string) {
      const s = yield* InstanceState.get(state)
      yield* Effect.sleep(JOB_RETENTION_TIME).pipe(
        Effect.andThen(cleanupJob(jobId)),
        Effect.ignore,
        Effect.forkIn(s.scope, { startImmediately: true }),
      )
    })

    const finish = Effect.fn("BackgroundJobManager.finish")(function* (
      jobId: string,
      status: Exclude<Status, "running">,
      data?: { readonly exitCode?: number; readonly error?: string },
    ) {
      const completedAt = yield* Clock.currentTimeMillis
      const result = yield* SynchronizedRef.modify(
        (yield* InstanceState.get(state)).jobs,
        (jobs): readonly [FinishResult, Map<string, Active>] => {
          const job = jobs.get(jobId)
          if (!job) return [{}, jobs]
          if (job.info.status !== "running") return [{ info: snapshot(job) }, jobs]
          const next = {
            ...job,
            info: {
              ...job.info,
              status,
              completedAt,
              ...(data?.exitCode !== undefined ? { exitCode: data.exitCode } : {}),
              ...(data?.error !== undefined ? { error: data.error } : {}),
            },
          }
          return [
            {
              info: snapshot(next),
              done: job.done,
              scope: job.scope,
            },
            new Map(jobs).set(jobId, next),
          ]
        },
      )

      if (!result.info) return
      log.info("Job completed", { jobId, status: result.info.status, exitCode: result.info.exitCode })
      if (result.done) yield* Deferred.succeed(result.done, result.info).pipe(Effect.ignore)
      if (result.scope) yield* Scope.close(result.scope, Exit.void).pipe(Effect.ignore)
      yield* scheduleCleanup(jobId)
      return result.info
    })

    const killRequested = Effect.fnUntraced(function* (jobId: string) {
      return (yield* getActive(jobId))?.killRequested === true
    })

    const runJob = Effect.fn("BackgroundJobManager.runJob")(function* (job: Active) {
      const result = yield* Effect.all(
        {
          output: Effect.exit(
            Stream.runForEach(Stream.decodeText(job.handle.all), (chunk) => appendOutput(job.info.id, chunk)),
          ),
          exit: Effect.exit(job.handle.exitCode),
        },
        { concurrency: "unbounded" },
      )

      if (yield* killRequested(job.info.id)) {
        yield* finish(job.info.id, "killed")
        return
      }

      if (Exit.isFailure(result.output)) {
        yield* finish(job.info.id, "failed", { error: errorText(Cause.squash(result.output.cause)) })
        return
      }

      if (Exit.isSuccess(result.exit)) {
        const exitCode = Number(result.exit.value)
        yield* finish(job.info.id, exitCode === 0 ? "completed" : "failed", { exitCode })
        return
      }

      yield* finish(job.info.id, "failed", { error: errorText(Cause.squash(result.exit.cause)) })
    })

    const register = Effect.fn("BackgroundJobManager.register")(function* (input: {
      readonly handle: ChildProcessHandle
      readonly scope: Scope.Scope
      readonly command: string
      readonly cwd: string
      readonly description?: string
      readonly output: string
      readonly label: string
    }) {
      const s = yield* InstanceState.get(state)
      const startedAt = yield* Clock.currentTimeMillis
      const done = yield* Deferred.make<Info>()
      const job = yield* SynchronizedRef.modifyEffect(
        s.jobs,
        Effect.fnUntraced(function* (jobs) {
          if (jobs.size >= MAX_CONCURRENT_JOBS) yield* new MaxJobsReachedError({ max: MAX_CONCURRENT_JOBS })
          const id = generateJobId(jobs)
          const job: Active = {
            done,
            handle: input.handle,
            scope: input.scope,
            killRequested: false,
            info: {
              id,
              pid: Number(input.handle.pid),
              command: input.command,
              cwd: input.cwd,
              startedAt,
              status: "running",
              output: input.output,
              description: input.description,
            },
          }
          return [job, new Map(jobs).set(id, job)] as const
        }),
      )
      const fiber = yield* runJob(job).pipe(Effect.orDie, Effect.forkIn(s.scope, { startImmediately: true }))
      yield* SynchronizedRef.update(s.jobs, (jobs) => {
        const current = jobs.get(job.info.id)
        if (!current) return jobs
        return new Map(jobs).set(job.info.id, { ...current, fiber })
      })
      log.info(`${input.label} job`, {
        jobId: job.info.id,
        pid: job.info.pid,
        command: job.info.command,
        cwd: job.info.cwd,
      })
      return snapshot(job)
    })

    const start: Interface["start"] = Effect.fn("BackgroundJobManager.start")(function* (input) {
      const scope = yield* Scope.make()
      const handle = yield* Scope.provide(scope)(spawner.spawn(input.process)).pipe(
        Effect.mapError((cause) => new JobStartFailedError({ command: input.command, cause })),
        Effect.tapError(() => Scope.close(scope, Exit.void).pipe(Effect.ignore)),
      )
      return yield* register({
        handle,
        scope,
        command: input.command,
        cwd: input.cwd,
        description: input.description,
        output: input.output ?? "",
        label: "Started",
      }).pipe(Effect.tapError(() => Scope.close(scope, Exit.void).pipe(Effect.ignore)))
    })

    const adopt: Interface["adopt"] = Effect.fn("BackgroundJobManager.adopt")(function* (input) {
      const info = yield* register({
        handle: input.handle,
        scope: input.scope,
        command: input.command,
        cwd: input.cwd,
        description: input.description,
        output: input.output,
        label: "Adopted",
      }).pipe(Effect.tapError(() => Scope.close(input.scope, Exit.void).pipe(Effect.ignore)))
      return { info }
    })

    const get: Interface["get"] = Effect.fn("BackgroundJobManager.get")(function* (jobId) {
      const job = yield* getActive(jobId)
      if (!job) return
      return snapshot(job)
    })

    const list: Interface["list"] = Effect.fn("BackgroundJobManager.list")(function* () {
      return Array.from((yield* SynchronizedRef.get((yield* InstanceState.get(state)).jobs)).values())
        .map(snapshot)
        .toSorted((a, b) => b.startedAt - a.startedAt)
    })

    const running: Interface["running"] = Effect.fn("BackgroundJobManager.running")(function* () {
      return (yield* list()).filter((job) => job.status === "running")
    })

    const kill: Interface["kill"] = Effect.fn("BackgroundJobManager.kill")(function* (input) {
      const job = yield* SynchronizedRef.modify(
        (yield* InstanceState.get(state)).jobs,
        (jobs): readonly [Active | Info | undefined, Map<string, Active>] => {
          const job = jobs.get(input.jobId)
          if (!job) return [undefined, jobs]
          if (job.info.status !== "running") return [snapshot(job), jobs]
          return [job, new Map(jobs).set(input.jobId, { ...job, killRequested: true })]
        },
      )

      if (!job) return yield* new JobNotFoundError({ jobId: input.jobId })
      if (!("handle" in job)) return job

      yield* job.handle
        .kill({
          killSignal: input.force ? "SIGKILL" : "SIGTERM",
          forceKillAfter: input.force ? undefined : KILL_GRACE_PERIOD,
        })
        .pipe(Effect.mapError((cause) => new JobKillFailedError({ jobId: input.jobId, cause })))

      const waited = yield* Deferred.await(job.done).pipe(Effect.timeoutOption("1 second"))
      if (waited._tag === "Some") return waited.value
      return (yield* finish(input.jobId, "killed")) ?? (yield* get(input.jobId)) ?? snapshot(job)
    })

    const output: Interface["output"] = Effect.fn("BackgroundJobManager.output")(function* (jobId) {
      const job = yield* get(jobId)
      if (!job) return yield* new JobNotFoundError({ jobId })
      return {
        output: job.output,
        status: job.status,
        completed: job.status !== "running",
      }
    })

    const wait: Interface["wait"] = Effect.fn("BackgroundJobManager.wait")(function* (input) {
      const job = yield* getActive(input.jobId)
      if (!job) return yield* new JobNotFoundError({ jobId: input.jobId })
      if (job.info.status !== "running") return { info: snapshot(job), timedOut: false }
      if (input.timeout <= 0) return { info: snapshot(job), timedOut: true }
      const info = yield* Deferred.await(job.done).pipe(Effect.timeoutOption(input.timeout))
      if (info._tag === "Some") return { info: info.value, timedOut: false }
      return { info: (yield* get(input.jobId)) ?? snapshot(job), timedOut: true }
    })

    const cleanupCompleted: Interface["cleanupCompleted"] = Effect.fn("BackgroundJobManager.cleanupCompleted")(
      function* () {
        yield* Effect.forEach(
          (yield* list()).filter((job) => job.status !== "running"),
          (job) => cleanupJob(job.id),
          { concurrency: "unbounded" },
        )
      },
    )

    return Service.of({ start, adopt, get, list, running, kill, output, wait, cleanupCompleted })
  }),
)

export const defaultLayer = layer

export * as BackgroundJobManager from "./background-job-manager"
