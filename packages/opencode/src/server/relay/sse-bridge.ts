import { encrypt } from "@opencode-ai/crypto-e2ee"

const log = {
  error(message?: unknown, extra?: Record<string, unknown>) { console.debug("[relay-sse-bridge]", message, extra ?? "") },
}

export async function startSSEBridge(
  ws: WebSocket,
  streamId: string,
  eventStream: AsyncIterable<unknown>,
  psk: CryptoKey,
) {
  try {
    for await (const event of eventStream) {
      const envelope = await encrypt(psk, event)
      const msg = { type: "sse_event" as const, stream_id: streamId, envelope }
      ws.send(JSON.stringify(msg))
    }
  } catch (err) {
    log.error("SSE bridge error", { error: err, streamId })
  } finally {
    ws.send(JSON.stringify({ type: "sse_close", stream_id: streamId }))
  }
}

export * as SSEBridge from "./sse-bridge"
