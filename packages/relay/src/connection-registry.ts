import type { ServerWebSocket } from "bun"
import type { InstanceInfo, RelayEnvelope } from "./protocol"

type PendingRequest = {
  resolve: (response: RelayEnvelope) => void
  reject: (error: Error) => void
  timer: Timer
}

type PendingSSE = {
  controller: ReadableStreamDefaultController
}

export type PendingWS = {
  clientWs: ServerWebSocket<ClientWSData>
}

type InstanceConnection = {
  ws: ServerWebSocket<InstanceWSData>
  info: InstanceInfo
  pendingRequests: Map<string, PendingRequest>
  pendingSSE: Map<string, PendingSSE>
  pendingWS: Map<string, PendingWS>
  lastHeartbeat: number
}

export type InstanceWSData = { instanceId: string; type: "instance"; version: string }
export type ClientWSData = { tunnelId: string; instanceId: string; type: "client" }

const connections = new Map<string, InstanceConnection>()

export function register(ws: ServerWebSocket<InstanceWSData>, info: InstanceInfo) {
  const existing = connections.get(info.id)
  if (existing) {
    for (const [, pending] of existing.pendingRequests) {
      clearTimeout(pending.timer)
      pending.reject(new Error("instance reconnected"))
    }
    existing.ws.close(1000, "replaced by new connection")
  }

  connections.set(info.id, {
    ws,
    info,
    pendingRequests: new Map(),
    pendingSSE: new Map(),
    pendingWS: new Map(),
    lastHeartbeat: Date.now(),
  })
}

export function unregister(instanceId: string) {
  const conn = connections.get(instanceId)
  if (!conn) return

  for (const [, pending] of conn.pendingRequests) {
    clearTimeout(pending.timer)
    pending.reject(new Error("instance disconnected"))
  }

  for (const [, sse] of conn.pendingSSE) {
    sse.controller.close()
  }

  for (const [, ws] of conn.pendingWS) {
    ws.clientWs.close(1001, "instance disconnected")
  }

  connections.delete(instanceId)
}

export function hasInstance(instanceId: string): boolean {
  return connections.has(instanceId)
}

export function sendRequest(
  instanceId: string,
  requestId: string,
  envelope: RelayEnvelope,
  timeoutMs: number = 30_000,
): Promise<RelayEnvelope> {
  const conn = connections.get(instanceId)
  if (!conn) return Promise.reject(new Error("instance not found"))

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      conn.pendingRequests.delete(requestId)
      reject(new Error("gateway timeout"))
    }, timeoutMs)

    conn.pendingRequests.set(requestId, { resolve, reject, timer })
    conn.ws.send(JSON.stringify({ type: "request", id: requestId, envelope }))
  })
}

export function handleResponse(instanceId: string, id: string, envelope: RelayEnvelope) {
  const conn = connections.get(instanceId)
  if (!conn) return

  const pending = conn.pendingRequests.get(id)
  if (!pending) return

  clearTimeout(pending.timer)
  conn.pendingRequests.delete(id)
  pending.resolve(envelope)
}

export function subscribeSSE(instanceId: string, streamId: string, envelope: RelayEnvelope): ReadableStream {
  const conn = connections.get(instanceId)
  if (!conn) return new ReadableStream({ start(c) { c.error(new Error("instance not found")) } })

  conn.ws.send(JSON.stringify({ type: "sse_subscribe", stream_id: streamId, envelope }))

  return new ReadableStream({
    start(controller) {
      conn.pendingSSE.set(streamId, { controller })
    },
    cancel() {
      conn.pendingSSE.delete(streamId)
      conn.ws.send(JSON.stringify({ type: "sse_close", stream_id: streamId }))
    },
  })
}

export function handleSSEEvent(instanceId: string, streamId: string, envelope: RelayEnvelope) {
  const conn = connections.get(instanceId)
  if (!conn) return

  const sse = conn.pendingSSE.get(streamId)
  if (!sse) return

  const data = JSON.stringify(envelope)
  sse.controller.enqueue(`data: ${data}\n\n`)
}

export function handleSSEClose(instanceId: string, streamId: string) {
  const conn = connections.get(instanceId)
  if (!conn) return

  const sse = conn.pendingSSE.get(streamId)
  if (!sse) return

  sse.controller.close()
  conn.pendingSSE.delete(streamId)
}

export function registerTunnel(
  instanceId: string,
  tunnelId: string,
  clientWs: ServerWebSocket<ClientWSData>,
  envelope: RelayEnvelope,
) {
  const conn = connections.get(instanceId)
  if (!conn) return false

  conn.pendingWS.set(tunnelId, { clientWs })
  conn.ws.send(JSON.stringify({ type: "ws_tunnel", tunnel_id: tunnelId, envelope }))
  return true
}

export function forwardClientWSFrame(instanceId: string, tunnelId: string, envelope: RelayEnvelope) {
  const conn = connections.get(instanceId)
  if (!conn) return

  conn.ws.send(JSON.stringify({ type: "ws_frame", tunnel_id: tunnelId, envelope }))
}

export function handleWSFrameFromInstance(instanceId: string, tunnelId: string, envelope: RelayEnvelope) {
  const conn = connections.get(instanceId)
  if (!conn) return

  const tunnel = conn.pendingWS.get(tunnelId)
  if (!tunnel) return

  tunnel.clientWs.send(JSON.stringify(envelope))
}

export function handleWSCloseFromInstance(instanceId: string, tunnelId: string) {
  const conn = connections.get(instanceId)
  if (!conn) return

  const tunnel = conn.pendingWS.get(tunnelId)
  if (!tunnel) return

  tunnel.clientWs.close(1000, "remote closed")
  conn.pendingWS.delete(tunnelId)
}

export function closeTunnel(instanceId: string, tunnelId: string) {
  const conn = connections.get(instanceId)
  if (!conn) return

  conn.ws.send(JSON.stringify({ type: "ws_close", tunnel_id: tunnelId }))
  conn.pendingWS.delete(tunnelId)
}

export function handleHeartbeat(instanceId: string) {
  const conn = connections.get(instanceId)
  if (!conn) return
  conn.lastHeartbeat = Date.now()
}

export { connections }

export * as Registry from "./connection-registry"
