import { loadConfig, type RelayConfig } from "./config"
import * as Registry from "./connection-registry"
import { parseMessage } from "./protocol"
import { handleHttp } from "./handlers/http"
import { handleSSE } from "./handlers/sse"
import { handleWSUpgrade, handleClientWSOpen } from "./handlers/ws"
import type { RelayEnvelope } from "./protocol"

let server: any

export async function start(configOverrides?: Partial<RelayConfig>) {
  const config = loadConfig(configOverrides)

  server = Bun.serve<any>({
    port: config.port,
    hostname: config.hostname,

    fetch(req, server) {
      const url = new URL(req.url)

      if (url.pathname === "/_register" && req.headers.get("upgrade") === "websocket") {
        const instanceId = url.searchParams.get("instance") ?? `instance-${Date.now()}`
        const version = url.searchParams.get("version") ?? "unknown"
        ;(server as any).upgrade(req, {
          data: { instanceId, type: "instance", version },
        })
        return new Response("upgrading", { status: 101 })
      }

      if (url.pathname === "/relay/http") {
        return handleHttp(req)
      }

      if (url.pathname === "/relay/instances" && req.method === "GET") {
        const instances = [...Registry.connections.entries()].map(([id, conn]) => ({
          id,
          version: conn.info.version,
          online: true,
          connectedAt: conn.lastHeartbeat,
        }))
        return Response.json({ instances })
      }

      if (url.pathname === "/relay/sse") {
        return handleSSE(req)
      }

      if (url.pathname === "/relay/ws" && req.headers.get("upgrade") === "websocket") {
        return handleWSUpgrade(req, server)
      }

      if (url.pathname === "/health") {
        return Response.json({ status: "ok", instances: Registry.connections.size })
      }

      return Response.json({ error: "not found" }, { status: 404 })
    },

    websocket: {
      open(ws: any) {
        if (ws.data.type === "instance") {
          Registry.register(ws, {
            id: ws.data.instanceId,
            version: ws.data.version,
          })
          ws.send(JSON.stringify({ type: "registered", status: "ok" }))
        }
      },

      message(ws: any, raw: string | Buffer) {
        const data = ws.data

        if (data.type === "client") {
          handleClientMessage(ws, raw)
          return
        }

        const msg = parseMessage(raw)
        const instanceId = data.instanceId

        switch (msg.type) {
          case "pong":
            Registry.handleHeartbeat(instanceId)
            break
          case "response":
            Registry.handleResponse(instanceId, msg.id, msg.envelope)
            break
          case "sse_event":
            Registry.handleSSEEvent(instanceId, msg.stream_id, msg.envelope)
            break
          case "sse_close":
            Registry.handleSSEClose(instanceId, msg.stream_id)
            break
          case "ws_tunnel_ready":
            break
          case "ws_frame":
            Registry.handleWSFrameFromInstance(instanceId, msg.tunnel_id, msg.envelope)
            break
          case "ws_close":
            Registry.handleWSCloseFromInstance(instanceId, msg.tunnel_id)
            break
        }
      },

      close(ws: any) {
        const data = ws.data

        if (data.type === "client") {
          if (data.tunnelId) {
            Registry.closeTunnel(data.instanceId, data.tunnelId)
          }
          return
        }

        Registry.unregister(data.instanceId)
      },
    },
  })

  console.log(`relay listening on http://${config.hostname}:${config.port}`)

  startHeartbeat(config.heartbeatInterval, config.heartbeatTimeout)
}


function handleClientMessage(ws: any, raw: string | Buffer) {
  const { instanceId } = ws.data

  if (!ws.data.tunnelId) {
    const msg = JSON.parse(typeof raw === "string" ? raw : raw.toString())
    const tunnelId = `tun_${crypto.randomUUID().slice(0, 8)}`
    handleClientWSOpen(ws, tunnelId, msg.envelope)
    return
  }

  const { tunnelId } = ws.data
  let envelope: RelayEnvelope
  try {
    envelope = JSON.parse(typeof raw === "string" ? raw : raw.toString())
  } catch {
    return
  }

  Registry.forwardClientWSFrame(instanceId, tunnelId, envelope)
}

export function stop() {
  server?.stop()
}

function startHeartbeat(intervalMs: number, timeoutMs: number) {
  setInterval(() => {
    const now = Date.now()
    for (const [id, conn] of Registry.connections) {
      if (now - conn.lastHeartbeat > timeoutMs) {
        console.log(`instance ${id} heartbeat timeout, disconnecting`)
        Registry.unregister(id)
        conn.ws.close(1000, "heartbeat timeout")
        continue
      }
      conn.ws.send(JSON.stringify({ type: "ping" }))
    }
  }, intervalMs)
}

export { Registry }

export * as Server from "./server"
