import z from "zod"
import * as Tool from "./tool"
import { BackgroundJobManager } from "./background-job-manager"
import { Log } from "@/util"
import { Effect } from "effect"

const log = Log.create({ service: "job-list-tool" })

const Params = z.object({
  status: z
    .enum(["running", "completed", "failed", "killed", "all"])
    .describe("Filter jobs by status. If not specified, shows all jobs")
    .optional(),
  limit: z
    .number()
    .describe("Maximum number of jobs to display (default: 20). Use 0 for no limit.")
    .optional(),
  format: z
    .enum(["table", "json", "detailed"])
    .describe("Output format: 'table' for readable table, 'json' for machine-readable, 'detailed' for full details")
    .optional(),
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
    
    parameters: Params,

    execute: (params: z.infer<typeof Params>, ctx: Tool.Context) =>
      Effect.gen(function* () {
        const { status = "all", limit = 20, format = "table" } = params

        log.info("Listing jobs", { status, limit, format })

        let jobs = BackgroundJobManager.getAllJobs()

        if (status !== "all") {
          jobs = jobs.filter(job => job.status === status)
        }

        const displayJobs = limit > 0 ? jobs.slice(0, limit) : jobs

        let output: string

        switch (format) {
          case "json":
            output = formatJsonOutput(displayJobs, jobs.length)
            break
          case "detailed":
            output = formatDetailedOutput(displayJobs, jobs.length)
            break
          case "table":
          default:
            output = formatTableOutput(displayJobs, jobs.length)
            break
        }

        return {
          title: `Background Jobs (${status === "all" ? "All" : status})`,
          output,
          metadata: {},
        }
      }),
  }
}))

function formatTableOutput(jobs: any[], total: number): string {
  if (jobs.length === 0) {
    return "No background jobs found."
  }

  let output = `Background Jobs (${jobs.length} of ${total}):\n\n`
  output += "ID    | Status   | Runtime  | PID    | Command\n"
  output += "------|----------|----------|--------|------------------\n"

  for (const job of jobs) {
    const runtime = Math.floor((Date.now() - job.startTime.getTime()) / 1000)
    const runtimeStr = formatRuntime(runtime)
    const statusStr = job.status.padEnd(8)
    const commandStr = job.command.length > 30 ? job.command.substring(0, 27) + "..." : job.command
    output += `${job.id.padEnd(5)} | ${statusStr} | ${runtimeStr.padEnd(8)} | ${String(job.pid).padEnd(6)} | ${commandStr}\n`
  }

  output += "\nUse job_output <id> to view output, job_kill <id> to terminate."
  return output
}

function formatJsonOutput(jobs: any[], total: number): string {
  return JSON.stringify({
    total_jobs: total,
    jobs: jobs.map(job => ({
      id: job.id,
      pid: job.pid,
      command: job.command,
      cwd: job.cwd,
      status: job.status,
      exit_code: job.exitCode,
      start_time: job.startTime.toISOString(),
      runtime_seconds: Math.floor((Date.now() - job.startTime.getTime()) / 1000),
      description: job.description,
      output_length: job.output.length,
    })),
  }, null, 2)
}

function formatDetailedOutput(jobs: any[], total: number): string {
  if (jobs.length === 0) {
    return "No background jobs found."
  }

  let output = `Background Jobs (${jobs.length} of ${total}):\n\n`

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
