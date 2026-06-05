import type { DecryptedRequest } from "./types"
import type { RelayClientConfig } from "./config"
import { decrypt, encrypt } from "@opencode-ai/crypto-e2ee"
import { handleRelayMessage } from "./message-handler"
import * as Server from "../server"
import { startSSEBridge } from "./sse-bridge"

const log = {
  info(message?: unknown, extra?: Record<string, unknown>) { console.debug("[relay-client]", message, extra ?? "") },
  error(message?: unknown, extra?: Record<string, unknown>) { console.debug("[relay-client]", message, extra ?? "") },
}

export class RelayClient {
  private ws: WebSocket | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private reconnectAttempts = 0
  private running = false
  private sseStreams = new Map<string, ReadableStreamDefaultReader<Uint8Array>>()

  constructor(private config: RelayClientConfig) {}

  async start() {
    this.running = true
    log.info("connecting to relay", { url: this.config.url, instance: this.config.instanceId })
    this.connect()
  }

  async stop() {
    this.running = false
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    for (const [, reader] of this.sseStreams) {
      reader.cancel().catch(() => {})
    }
    this.sseStreams.clear()
    if (this.ws) this.ws.close(1000, "shutdown")
  }

  private connect() {
    if (!this.running) return

    const url = `${this.config.url}/_register?instance=${encodeURIComponent(this.config.instanceId)}&version=1.0.0`

    try {
      this.ws = new WebSocket(url)
    } catch (err) {
      log.error("failed to create websocket", { error: err })
      this.scheduleReconnect()
      return
    }

    this.ws.onopen = () => {
      log.info("connected to relay")
      this.reconnectAttempts = 0
      this.startHeartbeat()
    }

    this.ws.onmessage = async (event) => {
      const raw = event.data as string
      let msg: Record<string, unknown>
      try {
        msg = JSON.parse(raw) as Record<string, unknown>
      } catch {
        return
      }

      const type = msg.type as string

      if (type === "registered") {
        log.info("registered with relay", { status: msg.status })
        return
      }

      if (type === "ping") {
        this.ws?.send(JSON.stringify({ type: "pong" }))
        return
      }

      if (type === "request") {
        await this.handleRequest(msg as { id: string; envelope: unknown })
        return
      }

      if (type === "sse_subscribe") {
        await this.handleSSESubscribe(msg as { stream_id: string; envelope: unknown })
        return
      }

      if (type === "ws_tunnel") {
        await this.handleWSTunnel(msg as { tunnel_id: string; envelope: unknown })
        return
      }
    }

    this.ws.onclose = (event) => {
      log.info("disconnected from relay", { code: event.code, reason: event.reason })
      this.ws = null
      if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
      this.scheduleReconnect()
    }

    this.ws.onerror = (event) => {
      log.error("websocket error", { error: event })
    }
  }

  private startHeartbeat() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: "pong" }))
      }
    }, 15000)
  }

  private scheduleReconnect() {
    if (!this.running) return

    const baseMs = this.config.reconnectBaseMs ?? 1000
    const maxMs = this.config.reconnectMaxMs ?? 30000
    const delay = Math.min(baseMs * Math.pow(2, this.reconnectAttempts), maxMs)
    this.reconnectAttempts++

    log.info("reconnecting", { delay, attempt: this.reconnectAttempts })

    this.reconnectTimer = setTimeout(() => this.connect(), delay)
  }

  private async handleRequest(msg: { id: string; envelope: unknown }) {
    try {
      const decrypted = await decrypt<DecryptedRequest>(this.config.psk, msg.envelope as any)
      const response = await handleRelayMessage(decrypted)
      const envelope = await encrypt(this.config.psk, response)
      this.ws?.send(JSON.stringify({ type: "response", id: msg.id, envelope }))
    } catch (err) {
      log.error("failed to handle relay request", { error: err, requestId: msg.id })
      const envelope = await encrypt(this.config.psk, { status: 500, body: { error: "internal error" } })
      this.ws?.send(JSON.stringify({ type: "response", id: msg.id, envelope }))
    }
  }

  private async handleSSESubscribe(msg: { stream_id: string; envelope: unknown }) {
    const streamId = msg.stream_id
    log.info("SSE subscribe request", { streamId })

    try {
      const req = new Request("http://localhost/global/event", { method: "GET" })
      const res = await Server.Default().app.fetch(req)

      if (!res.ok || !res.body) {
        log.error("failed to subscribe to global events", { status: res.status })
        this.ws?.send(JSON.stringify({ type: "sse_close", stream_id: streamId }))
        return
      }

      const reader = res.body.getReader()
      this.sseStreams.set(streamId, reader)

      const eventStream = (async function* () {
        let buffer = ""
        try {
          while (true) {
            const { done, value } = await reader.read()
            if (done) break
            buffer += new TextDecoder().decode(value, { stream: true })
            const parts = buffer.split("\n\n")
            buffer = parts.pop() ?? ""
            for (const part of parts) {
              const dataLine = part.split("\n").find((l) => l.startsWith("data: "))
              if (dataLine) {
                try {
                  yield JSON.parse(dataLine.slice(6))
                } catch {
                  continue
                }
              }
            }
          }
        } finally {
          reader.releaseLock()
        }
      })()

      await startSSEBridge(this.ws!, streamId, eventStream, this.config.psk)
    } catch (err) {
      log.error("SSE subscribe failed", { error: err, streamId })
      this.ws?.send(JSON.stringify({ type: "sse_close", stream_id: streamId }))
    } finally {
      this.sseStreams.delete(streamId)
    }
  }

  private async handleWSTunnel(msg: { tunnel_id: string; envelope: unknown }) {
    log.info("WS tunnel request", { tunnelId: msg.tunnel_id })
    this.ws?.send(JSON.stringify({ type: "ws_tunnel_ready", tunnel_id: msg.tunnel_id }))
  }
}

export * as Client from "./client"
