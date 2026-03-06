import { Clock, Effect, Schema } from "effect"
import * as Tool from "./tool"
import { BackgroundJobManager } from "./background-job-manager"

const Parameters = Schema.Struct({
  job_id: Schema.String.annotate({ description: "The ID of the background job to terminate (e.g., 'A1B2', 'C3D4')" }),
  force: Schema.optional(Schema.Boolean).annotate({
    description: "Whether to force immediate termination (SIGKILL) instead of graceful termination (SIGTERM)",
  }),
})

export const JobKillTool = Tool.define(
  "job_kill",
  Effect.gen(function* () {
    const jobs = yield* BackgroundJobManager.Service

    const run = Effect.fn("JobKillTool.execute")(function* (
      params: Schema.Schema.Type<typeof Parameters>,
      _ctx: Tool.Context,
    ) {
      const force = params.force ?? false

      yield* Effect.logInfo("Killing job", { jobId: params.job_id, force })

      const job = yield* jobs.get(params.job_id)
      if (!job) return yield* new BackgroundJobManager.JobNotFoundError({ jobId: params.job_id })

      if (job.status !== "running") {
        return {
          title: `Job ${params.job_id} already completed`,
          output: formatJobStatus(job, yield* Clock.currentTimeMillis),
          metadata: {},
        }
      }

      const runtime = Math.floor(((yield* Clock.currentTimeMillis) - job.startedAt) / 1000)
      const killed = yield* jobs.kill({ jobId: params.job_id, force })

      return {
        title: `Terminated job ${params.job_id}`,
        output: formatTermination({ job: killed, runtime, force }),
        metadata: {},
      }
    })

    return {
      description: `Terminates a running background job started by the bash tool.

Use this tool to:
- Stop long-running background jobs that are no longer needed
- Cancel stuck or misbehaving processes
- Clean up completed jobs to free resources
- Force terminate unresponsive commands

The tool will attempt to gracefully terminate the process first, then force kill if necessary.`,

      parameters: Parameters,

      execute: (params: Schema.Schema.Type<typeof Parameters>, ctx: Tool.Context) =>
        run(params, ctx).pipe(Effect.orDie),
    }
  }),
)

function formatTermination(input: { job: BackgroundJobManager.Info; runtime: number; force: boolean }): string {
  return [
    `Job ${input.job.id} termination initiated.`,
    "",
    "Job Details:",
    `- ID: ${input.job.id}`,
    `- PID: ${input.job.pid}`,
    `- Command: ${input.job.command}`,
    `- Runtime: ${input.runtime}s`,
    `- Termination: ${input.force ? "Force (SIGKILL)" : "Graceful (SIGTERM)"}`,
    input.job.description ? `- Description: ${input.job.description}` : undefined,
  ]
    .filter((line): line is string => line !== undefined)
    .join("\n")
}

function formatJobStatus(job: BackgroundJobManager.Info, now: number): string {
  return [
    `Job ${job.id} Status:`,
    "",
    `- Status: ${job.status}`,
    `- Command: ${job.command}`,
    `- PID: ${job.pid}`,
    `- Runtime: ${Math.floor((now - job.startedAt) / 1000)}s`,
    job.exitCode !== undefined ? `- Exit Code: ${job.exitCode}` : undefined,
    job.description ? `- Description: ${job.description}` : undefined,
  ]
    .filter((line): line is string => line !== undefined)
    .join("\n")
}
