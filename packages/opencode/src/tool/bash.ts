import z from "zod"
import { spawn } from "child_process"
import { Tool } from "./tool"
import path from "path"
import DESCRIPTION from "./bash.txt"
import { Log } from "../util/log"
import { Instance } from "../project/instance"
import { lazy } from "@/util/lazy"
import { Language } from "web-tree-sitter"
import fs from "fs/promises"

import { Filesystem } from "@/util/filesystem"
import { fileURLToPath } from "url"
import { Flag } from "@/flag/flag.ts"
import { Shell } from "@/shell/shell"
import { BackgroundJobManager } from "./background-job-manager"

import { BashArity } from "@/permission/arity"
import { Truncate } from "./truncate"
import { Plugin } from "@/plugin"

const MAX_METADATA_LENGTH = 30_000
const AUTO_BACKGROUND_TIMEOUT = 60 * 1000 // 1 minute for auto-background conversion

export const log = Log.create({ service: "bash-tool" })

const resolveWasm = (asset: string) => {
  if (asset.startsWith("file://")) return fileURLToPath(asset)
  if (asset.startsWith("/") || /^[a-z]:/i.test(asset)) return asset
  const url = new URL(asset, import.meta.url)
  return fileURLToPath(url)
}

const parser = lazy(async () => {
  const { Parser } = await import("web-tree-sitter")
  const { default: treeWasm } = await import("web-tree-sitter/tree-sitter.wasm" as string, {
    with: { type: "wasm" },
  })
  const treePath = resolveWasm(treeWasm)
  await Parser.init({
    locateFile() {
      return treePath
    },
  })
  const { default: bashWasm } = await import("tree-sitter-bash/tree-sitter-bash.wasm" as string, {
    with: { type: "wasm" },
  })
  const bashPath = resolveWasm(bashWasm)
  const bashLanguage = await Language.load(bashPath)
  const p = new Parser()
  p.setLanguage(bashLanguage)
  return p
})

// TODO: we may wanna rename this tool so it works better on other shells
export const BashTool = Tool.define("bash", async () => {
  const shell = Shell.acceptable()
  log.info("bash tool using shell", { shell })

  return {
    description: DESCRIPTION.replaceAll("${directory}", Instance.directory)
      .replaceAll("${maxLines}", String(Truncate.MAX_LINES))
      .replaceAll("${maxBytes}", String(Truncate.MAX_BYTES)),
    parameters: z.object({
      command: z.string().describe("The command to execute"),
      background: z
        .boolean()
        .optional()
        .describe("Whether to run the command in the background. If false and command runs over 60 seconds, it will automatically be converted to background."),
      workdir: z
        .string()
        .describe(
          `The working directory to run the command in. Defaults to ${Instance.directory}. Use this instead of 'cd' commands.`,
        )
        .optional(),
      description: z
        .string()
        .describe(
          "Clear, concise description of what this command does in 5-10 words. Examples:\nInput: ls\nOutput: Lists files in current directory\n\nInput: git status\nOutput: Shows working tree status\n\nInput: npm install\nOutput: Installs package dependencies\n\nInput: mkdir foo\nOutput: Creates directory 'foo'",
        ),
    }),
    async execute(params, ctx) {
      const cwd = params.workdir || Instance.directory
      const background = params.background ?? false

      const tree = await parser().then((p) => p.parse(params.command))
      if (!tree) {
        throw new Error("Failed to parse command")
      }
      const directories = new Set<string>()
      if (!Instance.containsPath(cwd)) directories.add(cwd)
      const patterns = new Set<string>()
      const always = new Set<string>()

      for (const node of tree.rootNode.descendantsOfType("command")) {
        if (!node) continue

        // Get full command text including redirects if present
        let commandText = node.parent?.type === "redirected_statement" ? node.parent.text : node.text

        const command = []
        for (let i = 0; i < node.childCount; i++) {
          const child = node.child(i)
          if (!child) continue
          if (
            child.type !== "command_name" &&
            child.type !== "word" &&
            child.type !== "string" &&
            child.type !== "raw_string" &&
            child.type !== "concatenation"
          ) {
            continue
          }
          command.push(child.text)
        }

        // not an exhaustive list, but covers most common cases
        if (["cd", "rm", "cp", "mv", "mkdir", "touch", "chmod", "chown", "cat"].includes(command[0])) {
          for (const arg of command.slice(1)) {
            if (arg.startsWith("-") || (command[0] === "chmod" && arg.startsWith("+"))) continue
            const resolved = await fs.realpath(path.resolve(cwd, arg)).catch(() => "")
            log.info("resolved path", { arg, resolved })
            if (resolved) {
              const normalized =
                process.platform === "win32" ? Filesystem.windowsPath(resolved).replace(/\//g, "\\") : resolved
              if (!Instance.containsPath(normalized)) {
                const dir = (await Filesystem.isDir(normalized)) ? normalized : path.dirname(normalized)
                directories.add(dir)
              }
            }
          }
        }

        // cd covered by above check
        if (command.length && command[0] !== "cd") {
          patterns.add(commandText)
          always.add(BashArity.prefix(command).join(" ") + " *")
        }
      }

      if (directories.size > 0) {
        const globs = Array.from(directories).map((dir) => {
          // Preserve POSIX-looking paths with /s, even on Windows
          if (dir.startsWith("/")) return `${dir.replace(/[\\/]+$/, "")}/*`
          return path.join(dir, "*")
        })
        await ctx.ask({
          permission: "external_directory",
          patterns: globs,
          always: globs,
          metadata: {},
        })
      }

      if (patterns.size > 0) {
        await ctx.ask({
          permission: "bash",
          patterns: Array.from(patterns),
          always: Array.from(always),
          metadata: {},
        })
      }

      const shellEnv = await Plugin.trigger(
        "shell.env",
        { cwd, sessionID: ctx.sessionID, callID: ctx.callID },
        { env: {} },
      )
      // Determine if command should run in background
      let jobId: string | undefined
      const shouldRunInBackground = background

      if (shouldRunInBackground) {
        log.info("Starting command in background job", { command: params.command, cwd })

        // Start as background job
        const result = await BackgroundJobManager.startJob({
          command: params.command,
          cwd,
          description: params.description,
        })

        jobId = result.jobId

        return {
          title: `Background job started`,
          output: `Background job started with ID: ${jobId}

Command: ${params.command}
Working Directory: ${cwd}
Description: ${params.description}

Use job_output tool to view output or job_kill to terminate.`,
          metadata: {
            job_id: jobId,
            is_background: true,
            command: params.command,
            cwd,
            description: params.description,
          } as any,
        }
      }

      // Synchronous execution with auto-background conversion
      const proc = spawn(params.command, {
        shell,
        cwd,
        env: {
          ...process.env,
          ...shellEnv.env,
        },
        stdio: ["ignore", "pipe", "pipe"],
        detached: process.platform !== "win32",
        windowsHide: process.platform === "win32",
      })

      let output = ""

      // Initialize metadata with empty output
      ctx.metadata({
        metadata: {
          output: "",
          description: params.description,
        },
      })

      const append = (chunk: Buffer) => {
        output += chunk.toString()
        ctx.metadata({
          metadata: {
            // truncate the metadata to avoid GIANT blobs of data (has nothing to do w/ what agent can access)
            output: output.length > MAX_METADATA_LENGTH ? output.slice(0, MAX_METADATA_LENGTH) + "\n\n..." : output,
            description: params.description,
          },
        })
      }

      proc.stdout?.on("data", append)
      proc.stderr?.on("data", append)

      let aborted = false
      let exited = false
      let autoConverted = false

      const kill = () => Shell.killTree(proc, { exited: () => exited })

      if (ctx.abort.aborted) {
        aborted = true
        await kill()
      }

      const abortHandler = () => {
        aborted = true
        void kill()
      }

      ctx.abort.addEventListener("abort", abortHandler, { once: true })

      const finish = { current: undefined as undefined | ((error?: Error) => void) }

      // Auto-background conversion timer
      const autoBackgroundTimer = setTimeout(async () => {
        if (!exited && !autoConverted) {
          autoConverted = true
          log.info("Auto-converting to background job", { command: params.command, runtime: AUTO_BACKGROUND_TIMEOUT })

          // Convert to background job
          const result = await BackgroundJobManager.startJob({
            command: params.command,
            cwd,
            description: params.description,
            process: proc,
            output,
          })

          jobId = result.jobId

          proc.stdout?.removeListener("data", append)
          proc.stderr?.removeListener("data", append)

          finish.current?.()
        }
      }, AUTO_BACKGROUND_TIMEOUT)

      await new Promise<void>((resolve, reject) => {
        const finished = { value: false }
        const cleanup = () => {
          clearTimeout(autoBackgroundTimer)
          ctx.abort.removeEventListener("abort", abortHandler)
        }

        finish.current = (error?: Error) => {
          if (finished.value) return
          finished.value = true
          cleanup()
          if (error) {
            reject(error)
            return
          }
          resolve()
        }

        proc.once("exit", () => {
          exited = true
          finish.current?.()
        })
        proc.once("error", (error) => {
          exited = true
          finish.current?.(error)
        })
      })

      const resultMetadata: string[] = []

      if (aborted) {
        resultMetadata.push("User aborted the command")
      }

      if (autoConverted) {
        resultMetadata.push(`Command automatically converted to background job after ${AUTO_BACKGROUND_TIMEOUT/1000} seconds`)
      }

      if (resultMetadata.length > 0) {
        resultMetadata.unshift("<bash_metadata>")
        resultMetadata.push("</bash_metadata>")
        output += "\n\n" + resultMetadata.join("\n")
      }

      // Return different response based on whether auto-conversion happened
      if (autoConverted && jobId) {
        return {
          title: `Auto-converted to background job`,
          output: `Command automatically converted to background job with ID: ${jobId}

Command: ${params.command}
Working Directory: ${cwd}
Description: ${params.description}

Reason: Command exceeded ${AUTO_BACKGROUND_TIMEOUT/1000} second limit

Use job_output tool to view output or job_kill to terminate.`,
          metadata: {
            job_id: jobId,
            is_background: true,
            auto_converted: true,
            command: params.command,
            cwd,
            description: params.description,
          } as any,
        }
      }

      return {
        title: params.description,
        metadata: {
          output: output.length > MAX_METADATA_LENGTH ? output.slice(0, MAX_METADATA_LENGTH) + "\n\n..." : output,
          exit: proc.exitCode,
          description: params.description,
          is_background: false,
        },
        output,
      }
    },
  }
})
