export type RelayMessage =
  | { type: "request"; id: string; envelope: RelayEnvelope }
  | { type: "response"; id: string; envelope: RelayEnvelope }
  | { type: "sse_subscribe"; stream_id: string; envelope: RelayEnvelope }
  | { type: "sse_event"; stream_id: string; envelope: RelayEnvelope }
  | { type: "sse_close"; stream_id: string }
  | { type: "ws_tunnel"; tunnel_id: string; envelope: RelayEnvelope }
  | { type: "ws_tunnel_ready"; tunnel_id: string }
  | { type: "ws_frame"; tunnel_id: string; envelope: RelayEnvelope }
  | { type: "ws_close"; tunnel_id: string }
  | { type: "ping" }
  | { type: "pong" }
  | { type: "registered"; status: string }

export type RelayEnvelope = {
  nonce: string
  ciphertext: string
}

export type InstanceInfo = {
  id: string
  version: string
}

export type RelayHttpRequest = {
  instance: string
  envelope: RelayEnvelope
  timeout?: number
}

export type RelayHttpSuccess = {
  envelope: RelayEnvelope
}

export type RelayHttpError = {
  error: string
  status: number
}

export type RelayHttpResponse = RelayHttpSuccess | RelayHttpError

export function parseMessage(data: string | Buffer): RelayMessage {
  return JSON.parse(typeof data === "string" ? data : data.toString()) as RelayMessage
}

export * as Protocol from "./protocol"
