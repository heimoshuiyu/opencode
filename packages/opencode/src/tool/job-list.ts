import { Clock, Effect, Schema } from "effect"
import * as Tool from "./tool"
import { BackgroundJobManager } from "./background-job-manager"
import * as Log from "@opencode-ai/core/util/log"

const log = Log.create({ service: "job-list-tool" })

const Parameters = Schema.Struct({
  status: Schema.optional(
    Schema.Union([
      Schema.Literal("running"),
      Schema.Literal("completed"),
      Schema.Literal("failed"),
      Schema.Literal("killed"),
      Schema.Literal("all"),
    ]),
  ).annotate({ description: "Filter jobs by status. If not specified, shows all jobs" }),
  limit: Schema.optional(Schema.Number).annotate({
    description: "Maximum number of jobs to display (default: 20). Use 0 for no limit.",
  }),
})

export const JobListTool = Tool.define(
  "job_list",
  Effect.gen(function* () {
    const jobs = yield* BackgroundJobManager.Service

    const run = Effect.fn("JobListTool.execute")(function* (
      params: Schema.Schema.Type<typeof Parameters>,
      _ctx: Tool.Context,
    ) {
      const status = params.status ?? "all"
      const limit = params.limit ?? 20

      log.info("Listing jobs", { status, limit })

      const all = yield* jobs.list()
      const filtered = status === "all" ? all : all.filter((job) => job.status === status)
      const displayJobs = limit > 0 ? filtered.slice(0, limit) : filtered

      return {
        title: `Background Jobs (${status === "all" ? "All" : status})`,
        output: formatOutput(displayJobs, yield* Clock.currentTimeMillis),
        metadata: {},
      }
    })

    return {
      description: `Lists all background jobs started by the bash tool.

Use this tool to:
- View all running and completed background jobs
- Check job status, runtime, and command details
- Find job IDs for use with other job_* tools
- Monitor system resource usage from background jobs

The tool provides a comprehensive overview of all managed background processes.`,

      parameters: Parameters,

      execute: (params: Schema.Schema.Type<typeof Parameters>, ctx: Tool.Context) =>
        run(params, ctx).pipe(Effect.orDie),
    }
  }),
)

function formatOutput(jobs: BackgroundJobManager.Info[], now: number): string {
  if (jobs.length === 0) {
    return "No background jobs found."
  }

  return [
    `Background Jobs (${jobs.length}):`,
    "",
    ...jobs.map((job) => formatJob(job, now)),
    "Use job_output <id> for full output, job_kill <id> to terminate.",
  ].join("\n")
}

function formatJob(job: BackgroundJobManager.Info, now: number): string {
  const runtime = Math.floor((now - job.startedAt) / 1000)
  return [
    `Job ID: ${job.id}`,
    `  Status: ${job.status}`,
    `  PID: ${job.pid}`,
    `  Command: ${job.command}`,
    `  Working Directory: ${job.cwd}`,
    `  Runtime: ${formatRuntime(runtime)}`,
    `  Started: ${new Date(job.startedAt).toISOString()}`,
    job.description ? `  Description: ${job.description}` : undefined,
    job.exitCode !== undefined ? `  Exit Code: ${job.exitCode}` : undefined,
    job.error ? `  Error: ${job.error}` : undefined,
    `  Output Size: ${job.output.length} characters`,
    job.status === "running"
      ? `  Output Preview: ${job.output.substring(0, 100)}${job.output.length > 100 ? "..." : ""}`
      : undefined,
    "",
    "─".repeat(50),
    "",
  ]
    .filter((line): line is string => line !== undefined)
    .join("\n")
}

function formatRuntime(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60)
    return `${minutes}m ${seconds % 60}s`
  }
  const hours = Math.floor(seconds / 3600)
  return `${hours}h ${Math.floor((seconds % 3600) / 60)}m`
}
