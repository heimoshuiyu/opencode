import * as Registry from "../connection-registry"
import type { RelayHttpRequest } from "../protocol"

export async function handleHttp(req: Request): Promise<Response> {
  if (req.method !== "POST") {
    return Response.json({ error: "method not allowed" }, { status: 405 })
  }

  let body: RelayHttpRequest
  try {
    body = (await req.json()) as RelayHttpRequest
  } catch {
    return Response.json({ error: "invalid json" }, { status: 400 })
  }

  if (!body.instance || !body.envelope) {
    return Response.json({ error: "missing instance or envelope" }, { status: 400 })
  }

  const timeoutMs = body.timeout ?? 30_000

  if (!Registry.hasInstance(body.instance)) {
    return Response.json({ error: "instance not found" }, { status: 404 })
  }

  const requestId = `req_${crypto.randomUUID().slice(0, 8)}`

  try {
    const responseEnvelope = await Registry.sendRequest(body.instance, requestId, body.envelope, timeoutMs)
    return Response.json({ envelope: responseEnvelope })
  } catch (err) {
    const message = err instanceof Error ? err.message : "unknown error"
    const status = message === "instance not found" ? 404 : message === "gateway timeout" ? 504 : 502
    return Response.json({ error: message }, { status })
  }
}
