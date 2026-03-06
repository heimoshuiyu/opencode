import { Schema, Effect } from "effect"
import * as Tool from "./tool"
import { BackgroundJobManager } from "./background-job-manager"

const Parameters = Schema.Struct({
  job_id: Schema.String.annotate({
    description: "The ID of the background job to check output for (e.g., 'A1B2', 'C3D4')",
  }),
  max_wait_time: Schema.Number.annotate({
    description: "Maximum time in seconds to wait for job completion (max 300 seconds).",
  }),
  offset: Schema.optional(Schema.Number).annotate({
    description:
      "Character offset to start reading output from. Use the next_offset from a previous response to only get new output.",
  }),
})

export const JobOutputTool = Tool.define(
  "job_output",
  Effect.gen(function* () {
    const jobs = yield* BackgroundJobManager.Service

    const run = Effect.fn("JobOutputTool.execute")(function* (
      params: Schema.Schema.Type<typeof Parameters>,
      _ctx: Tool.Context,
    ) {
      yield* Effect.logInfo("Getting job output", { jobId: params.job_id, maxWaitTime: params.max_wait_time, offset: params.offset })

      const offset = params.offset ?? 0
      const job = yield* jobs.get(params.job_id)
      if (!job) return yield* new BackgroundJobManager.JobNotFoundError({ jobId: params.job_id })

      const waited =
        job.status !== "running"
          ? { info: job, timedOut: false }
          : params.max_wait_time <= 0
            ? { info: job, timedOut: true }
            : yield* jobs.wait({ jobId: params.job_id, timeout: Math.min(params.max_wait_time * 1000, 300 * 1000) })

      const fullOutput = waited.info.output
      const newOutput = fullOutput.slice(offset)
      const nextOffset = fullOutput.length

      const parts = [newOutput || "No new output"]
      if (waited.timedOut) {
        parts.push(
          `Task is still running after waiting ${params.max_wait_time} seconds. Next offset: ${nextOffset}. Use offset=${nextOffset} to get only new output.`,
        )
      }

      return {
        title: `Output for job ${params.job_id}`,
        output: formatJobOutput(waited.info, parts.filter((part) => part.length > 0).join("\n"), nextOffset),
        metadata: {},
      }
    })

    return {
      description: `Retrieves the output and status of a background job started by the bash tool.

Use this tool to:
- Check the progress of running background jobs (immediate return)
- Wait for job completion with optional timeout
- View the complete output of completed jobs
- Monitor job status (running, completed, failed, killed)

The tool returns the current output and whether the job has finished.`,

      parameters: Parameters,

      execute: (params: Schema.Schema.Type<typeof Parameters>, ctx: Tool.Context) =>
        run(params, ctx).pipe(Effect.orDie),
    }
  }),
)

export function formatJobOutput(job: BackgroundJobManager.Info, output: string, nextOffset: number): string {
  return [
    `Status: ${job.status}`,
    "",
    output,
    job.exitCode !== undefined && job.exitCode !== 0 ? `Exit code ${job.exitCode}` : undefined,
    job.error ? `Error: ${job.error}` : undefined,
    job.status === "running" ? `Next offset: ${nextOffset}` : undefined,
  ]
    .filter((line): line is string => line !== undefined)
    .join("\n")
}
