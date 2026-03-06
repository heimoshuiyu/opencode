import { Schema, Effect } from "effect"
import * as Tool from "./tool"
import { BackgroundJobManager } from "./background-job-manager"
import { Log } from "@/util"

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

export const JobListTool = Tool.define("job_list", Effect.gen(function* () {
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
      Effect.gen(function* () {
        const { status = "all", limit = 20 } = params

        log.info("Listing jobs", { status, limit })

        let jobs = BackgroundJobManager.getAllJobs()

        if (status !== "all") {
          jobs = jobs.filter(job => job.status === status)
        }

        const displayJobs = limit > 0 ? jobs.slice(0, limit) : jobs

        const output = formatOutput(displayJobs)

        return {
          title: `Background Jobs (${status === "all" ? "All" : status})`,
          output,
          metadata: {},
        }
      }),
  }
}))

function formatOutput(jobs: any[]): string {
  if (jobs.length === 0) {
    return "No background jobs found."
  }

  let output = `Background Jobs (${jobs.length}):\n\n`

  for (const job of jobs) {
    const runtime = Math.floor((Date.now() - job.startTime.getTime()) / 1000)
    output += `Job ID: ${job.id}\n`
    output += `  Status: ${job.status}\n`
    output += `  PID: ${job.pid}\n`
    output += `  Command: ${job.command}\n`
    output += `  Working Directory: ${job.cwd}\n`
    output += `  Runtime: ${formatRuntime(runtime)}\n`
    output += `  Started: ${job.startTime.toLocaleString()}\n`
    if (job.description) output += `  Description: ${job.description}\n`
    if (job.exitCode !== undefined) output += `  Exit Code: ${job.exitCode}\n`
    output += `  Output Size: ${job.output.length} characters\n`
    if (job.status === "running") {
      output += `  Output Preview: ${job.output.substring(0, 100)}${job.output.length > 100 ? "..." : ""}\n`
    }
    output += "\n" + "─".repeat(50) + "\n\n"
  }

  output += "Use job_output <id> for full output, job_kill <id> to terminate."
  return output
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
