import z from "zod"
import { Tool } from "./tool"
import { BackgroundJobManager } from "./background-job-manager"
import { Log } from "../util/log"
import { Effect } from "effect"

const log = Log.create({ service: "job-output-tool" })

const Params = z.object({
  job_id: z
    .string()
    .describe("The ID of the background job to check output for (e.g., 'A1B2', 'C3D4')"),
  max_wait_time: z
    .number()
    .describe("Maximum time in seconds to wait for job completion (max 300 seconds)."),
})

export const JobOutputTool = Tool.define("job_output", Effect.gen(function* () {
  return {
    description: `Retrieves the output and status of a background job started by the bash tool.

Use this tool to:
- Check the progress of running background jobs (immediate return)
- Wait for job completion with optional timeout
- View the complete output of completed jobs
- Monitor job status (running, completed, failed, killed)

The tool returns the current output and whether the job has finished.`,
    
    parameters: Params,

    execute: (params: z.infer<typeof Params>, ctx: Tool.Context) =>
      Effect.gen(function* () {
        const { job_id: jobId, max_wait_time } = params

        log.info("Getting job output", { jobId, maxWaitTime: max_wait_time })

        const job = BackgroundJobManager.getJob(jobId)
        if (!job) {
          throw new Error(`Job not found: ${jobId}. Use job_list to see all available jobs.`)
        }

        const getCurrent = () => {
          const result = BackgroundJobManager.getJobOutput(jobId)
          return {
            output: result.output,
            status: result.status,
            completed: result.completed,
            runtime: Math.floor((Date.now() - job.startTime.getTime()) / 1000),
          }
        }

        let current = getCurrent()

        if (!current.completed && max_wait_time > 0) {
          const maxWaitMs = Math.min(max_wait_time * 1000, 300 * 1000)
          current = yield* Effect.callback<typeof current>((resume, signal) => {
            let done = false
            const finish = () => {
              if (done) return
              done = true
              clearTimeout(timeout)
              BackgroundJobManager.offJobUpdate(onUpdate)
              BackgroundJobManager.offJobComplete(onComplete)
              resume(Effect.succeed(getCurrent()))
            }

            const onUpdate = (data: { jobId: string; output: string }) => {
              if (data.jobId === jobId) {
                // keep listening, current fetched on finish
              }
            }

            const onComplete = (data: { jobId: string; status: string; exitCode?: number; error?: string }) => {
              if (data.jobId === jobId) finish()
            }

            BackgroundJobManager.onJobUpdate(onUpdate)
            BackgroundJobManager.onJobComplete(onComplete)
            signal.addEventListener("abort", finish, { once: true })
            const timeout = setTimeout(() => finish(), maxWaitMs)

            return Effect.sync(() => {
              signal.removeEventListener("abort", finish)
              clearTimeout(timeout)
              BackgroundJobManager.offJobUpdate(onUpdate)
              BackgroundJobManager.offJobComplete(onComplete)
            })
          })
        }

        const parts: string[] = []
        if (current.output) parts.push(current.output)

        if (current.status !== "completed" && max_wait_time > 0) {
          parts.push(`Task is still running after waiting ${max_wait_time} seconds. Try calling again with a longer wait time.`)
        }

        const finalOutput = parts.length > 0 ? parts.join("\n") : "No output"
        const formatted = formatJobOutput(job, current, finalOutput)

        return {
          title: `Output for job ${jobId}`,
          output: formatted,
          metadata: {},
        }
      }),
  }
}))

function formatJobOutput(job: any, current: any, output: string): string {
  let result = `Status: ${current.status}\n\n`
  result += output
  if (current.status === "completed" && job.exitCode !== undefined && job.exitCode !== 0) {
    result += `\nExit code ${job.exitCode}`
  }
  return result
}
