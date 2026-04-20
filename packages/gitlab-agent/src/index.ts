import { Hono } from "hono"
import { loadConfig } from "./config"
import { parseWebhook } from "./webhook"
import { TaskQueue } from "./queue"

const config = loadConfig()
const queue = new TaskQueue()
const app = new Hono()

app.get("/health", (c) =>
  c.json({
    status: "ok",
    queueLength: queue.length,
    isProcessing: queue.isProcessing,
    currentTask: queue.currentTask
      ? `!${queue.currentTask.mrIid} (${queue.currentTask.projectSlug})`
      : null,
  }),
)

app.post("/webhook/:projectSlug", async (c) => {
  const projectSlug = c.req.param("projectSlug")
  const token = c.req.header("X-Gitlab-Token")
  const body = await c.req.json()

  const result = parseWebhook(body, projectSlug, token, config)

  if (!result.ok) {
    return c.json({ error: result.error }, result.status as 401)
  }

  if ("skipped" in result) {
    return c.json({ ok: true, skipped: result.skipped })
  }

  queue.enqueue(result.task).catch((e) => {
    console.error(`[index] task failed:`, e)
  })
  return c.json({ ok: true, queued: true, mr: `!${result.task.mrIid}` })
})

const port = config.port
console.log(`gitlab-opencode-agent starting on port ${port}`)
console.log(`configured projects: ${Object.keys(config.projects).join(", ")}`)
console.log(`mention trigger: ${config.mention}`)
console.log(`opencode model: ${config.opencodeModel}`)

export default {
  port,
  fetch: app.fetch,
}
