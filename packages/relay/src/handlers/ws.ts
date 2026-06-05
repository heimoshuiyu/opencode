import * as Registry from "../connection-registry"
import type { RelayEnvelope } from "../protocol"

export function handleWSUpgrade(req: Request, server: any): Response {
  const url = new URL(req.url)
  const instanceId = url.searchParams.get("instance")
  if (!instanceId) {
    return Response.json({ error: "missing instance parameter" }, { status: 400 })
  }

  if (!Registry.hasInstance(instanceId)) {
    return Response.json({ error: "instance not found" }, { status: 404 })
  }

  const data: Registry.ClientWSData = { tunnelId: "", instanceId, type: "client" }
  const upgraded = server.upgrade(req, { data })
  if (upgraded) return new Response("upgrading", { status: 101 })

  return Response.json({ error: "websocket upgrade failed" }, { status: 500 })
}

export function handleClientWSOpen(
  ws: any,
  tunnelId: string,
  envelope: RelayEnvelope,
) {
  const { instanceId } = ws.data as Registry.ClientWSData
  ws.data.tunnelId = tunnelId
  Registry.registerTunnel(instanceId, tunnelId, ws, envelope)
}
