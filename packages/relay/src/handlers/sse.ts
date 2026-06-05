import * as Registry from "../connection-registry"
import type { RelayHttpRequest } from "../protocol"

export async function handleSSE(req: Request): Promise<Response> {
  if (req.method !== "GET") {
    return Response.json({ error: "method not allowed" }, { status: 405 })
  }

  const url = new URL(req.url)
  const instanceId = url.searchParams.get("instance")
  if (!instanceId) {
    return Response.json({ error: "missing instance parameter" }, { status: 400 })
  }

  if (!Registry.hasInstance(instanceId)) {
    return Response.json({ error: "instance not found" }, { status: 404 })
  }

  let body: { envelope: RelayHttpRequest["envelope"] }
  try {
    body = (await req.json()) as { envelope: RelayHttpRequest["envelope"] }
  } catch {
    return Response.json({ error: "invalid json body" }, { status: 400 })
  }

  if (!body.envelope) {
    return Response.json({ error: "missing envelope" }, { status: 400 })
  }

  const streamId = `sse_${crypto.randomUUID().slice(0, 8)}`
  const stream = Registry.subscribeSSE(instanceId, streamId, body.envelope)

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    },
  })
}
