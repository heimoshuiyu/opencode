import { encrypt, decrypt } from "@opencode-ai/crypto-e2ee"

const log = {
  error(message?: unknown, extra?: Record<string, unknown>) { console.debug("[relay-ws-bridge]", message, extra ?? "") },
}

export class WSTunnelBridge {
  constructor(
    private ws: WebSocket,
    private tunnelId: string,
    private psk: CryptoKey,
    private onMessage: (data: unknown) => void,
  ) {}

  async handleEncryptedFrame(raw: string) {
    try {
      const envelope = JSON.parse(raw) as { nonce: string; ciphertext: string }
      const decrypted = await decrypt(this.psk, envelope)
      this.onMessage(decrypted)
    } catch (err) {
      log.error("WS bridge decrypt error", { error: err, tunnelId: this.tunnelId })
    }
  }

  async sendEncrypted(data: unknown) {
    try {
      const envelope = await encrypt(this.psk, data)
      this.ws.send(JSON.stringify({ type: "ws_frame", tunnel_id: this.tunnelId, envelope }))
    } catch (err) {
      log.error("WS bridge encrypt error", { error: err, tunnelId: this.tunnelId })
    }
  }

  close() {
    this.ws.send(JSON.stringify({ type: "ws_close", tunnel_id: this.tunnelId }))
  }
}

export * as WSTunnelBridgeModule from "./ws-bridge"
