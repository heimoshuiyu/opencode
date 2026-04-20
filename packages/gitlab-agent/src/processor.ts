import { $ } from "bun"
import path from "node:path"
import { createOpencodeClient, createOpencodeServer } from "@opencode-ai/sdk/v2"
import { GitLabClient } from "./gitlab"
import { buildPromptForMR, fetchMRContext } from "./prompt"
import type { MRTask } from "./webhook"
import type { Config } from "./config"

type SSEEvent = {
  type: string
  properties: Record<string, unknown>
}

type OpencodeHandle = {
  client: ReturnType<typeof createOpencodeClient>
  serverUrl: string
  close: () => void
}

export type ProcessorDeps = {
  config: Config
  createOpencode: (workspace: string, auth: Config["auth"]) => Promise<OpencodeHandle>
  gitClone: (url: string, branch: string, dest: string) => Promise<void>
  gitPush: (workspace: string, branch: string, remote: string) => Promise<void>
  gitCommit: (workspace: string, message: string) => Promise<void>
  gitHasChanges: (workspace: string) => Promise<boolean>
  cleanup: (workspace: string) => Promise<void>
}

const defaults: (config: Config) => ProcessorDeps = (config) => ({
  config,
  async createOpencode(workspace, _auth) {
    const server = await createOpencodeServer({
      hostname: "127.0.0.1",
      port: 4096,
      timeout: 30000,
    })
    const client = createOpencodeClient({
      baseUrl: server.url,
      directory: workspace,
    })
    return {
      client,
      serverUrl: server.url,
      close: () => server.close(),
    }
  },
  async gitClone(url, branch, dest) {
    await $`git clone --depth=50 --branch=${branch} ${url} ${dest}`.quiet()
  },
  async gitPush(workspace, _branch, _remote) {
    await $`git push`.cwd(workspace).quiet()
  },
  async gitCommit(workspace, message) {
    await $`git add .`.cwd(workspace).quiet()
    await $`git commit -m ${message}`.cwd(workspace).quiet()
  },
  async gitHasChanges(workspace) {
    const result = await $`git status --porcelain`.cwd(workspace).quiet()
    return result.stdout.toString().trim().length > 0
  },
  async cleanup(workspace) {
    await $`rm -rf ${workspace}`.quiet()
  },
})

export async function processMR(task: MRTask, deps?: Partial<ProcessorDeps>): Promise<void> {
  const config = (await import("./config")).loadConfig()
  const d = { ...defaults(config), ...deps }

  const workspace = path.join(config.workspaceBase, `${task.projectId}-${task.mrIid}-${Date.now()}`)
  const gitlab = new GitLabClient(task.projectConfig)

  console.log(`[processor] starting MR !${task.mrIid} for ${task.projectSlug}`)
  console.log(`[processor] workspace: ${workspace}`)

  try {
    const hasAccess = await gitlab.hasWriteAccess(task.projectId, task.userId)
    if (!hasAccess) {
      await gitlab.createMRNote(
        task.projectId,
        task.mrIid,
        `@${task.username} you don't have write permissions for this project.`,
      )
      console.log(`[processor] user ${task.username} lacks write access, skipping`)
      return
    }

    await gitlab.createMRNote(task.projectId, task.mrIid, "👀 opencode is reviewing...")

    const cloneUrl = gitlab.getCloneUrl(task.projectPath)
    await d.gitClone(cloneUrl, task.sourceBranch, workspace)

    await $`git config user.name "opencode"`.cwd(workspace).quiet()
    await $`git config user.email "opencode@gitlab-agent"`.cwd(workspace).quiet()

    console.log(`[processor] fetching MR context`)
    const { mr, notes } = await fetchMRContext(gitlab, task.projectId, task.mrIid, task.noteId)

    const prompt = buildPromptForMR(mr, notes, task.userPrompt)

    console.log(`[processor] starting opencode serve`)
    const opencode = await d.createOpencode(workspace, config.auth)

    try {
      const sessionRes = await opencode.client.session.create<true>({
        title: `MR !${task.mrIid}: ${mr.title}`,
        permission: [{ permission: "question", action: "deny", pattern: "*" }],
      })
      const sessionID = (sessionRes.data as { id: string }).id
      console.log(`[processor] session created: ${sessionID}`)

      const [providerID, ...rest] = config.opencodeModel.split("/")
      const modelID = rest.join("/")

      await opencode.client.session.promptAsync<true>({
        sessionID,
        model: { providerID, modelID },
        parts: [{ type: "text", text: prompt }],
      })

      console.log(`[processor] waiting for AI to finish`)
      const reviewText = await waitForCompletion(opencode.serverUrl, sessionID)

      if (await d.gitHasChanges(workspace)) {
        console.log(`[processor] code changes detected, committing and pushing`)
        await d.gitCommit(workspace, `opencode: review fixes for MR !${task.mrIid}`)
        await d.gitPush(workspace, task.sourceBranch, "origin")
      }

      console.log(`[processor] posting review comment`)
      await gitlab.createMRNote(task.projectId, task.mrIid, reviewText)
      console.log(`[processor] MR !${task.mrIid} completed successfully`)
    } finally {
      opencode.close()
    }
  } catch (error) {
    console.error(`[processor] failed MR !${task.mrIid}:`, error)
    const msg = error instanceof Error ? error.message : String(error)
    try {
      await gitlab.createMRNote(task.projectId, task.mrIid, `**opencode error:** ${msg}`)
    } catch (noteError) {
      console.error(`[processor] failed to post error note:`, noteError)
    }
    throw error
  } finally {
    await d.cleanup(workspace)
  }
}

async function waitForCompletion(serverUrl: string, sessionID: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error("Timeout waiting for opencode session to complete (10 minutes)"))
    }, 10 * 60 * 1000)

    let lastText = ""
    let resolved = false

    const finish = () => {
      if (resolved) return
      resolved = true
      clearTimeout(timeout)
      if (lastText.trim()) {
        resolve(lastText)
      } else {
        reject(new Error("No text response from opencode"))
      }
    }

    fetch(`${serverUrl}/event`)
      .then(async (response) => {
        if (!response.body) throw new Error("No response body from SSE stream")

        const reader = response.body.getReader()
        const decoder = new TextDecoder()

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          const chunk = decoder.decode(value, { stream: true })
          const lines = chunk.split("\n")

          for (const line of lines) {
            if (!line.startsWith("data: ")) continue
            const jsonStr = line.slice(6).trim()
            if (!jsonStr) continue

            try {
              const evt = JSON.parse(jsonStr) as SSEEvent

              if (evt.type === "message.part.updated") {
                const part = (evt.properties as { part: Record<string, unknown> }).part
                if ((part as { sessionID: string }).sessionID !== sessionID) continue
                if (part.type === "text" && (part as { time?: { end?: number } }).time?.end) {
                  lastText = (part as { text: string }).text
                }
              }

              if (evt.type === "session.status") {
                const props = evt.properties as { sessionID: string; status: { type: string } }
                if (props.sessionID !== sessionID) continue
                if (props.status.type === "idle") {
                  finish()
                  return
                }
              }

              if (evt.type === "session.error") {
                const props = evt.properties as { sessionID: string; error?: { message: string } }
                if (props.sessionID !== sessionID) continue
                clearTimeout(timeout)
                reject(new Error(`Session error: ${props.error?.message ?? "unknown"}`))
                return
              }
            } catch {
              // Ignore SSE parse errors
            }
          }
        }

        finish()
      })
      .catch((e) => {
        clearTimeout(timeout)
        reject(e)
      })
  })
}
