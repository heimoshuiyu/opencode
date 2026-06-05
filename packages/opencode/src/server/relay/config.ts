import { deriveKey, parsePsk } from "@opencode-ai/crypto-e2ee"

export type RelayClientConfig = {
  url: string
  instanceId: string
  psk: CryptoKey
  reconnectBaseMs?: number
  reconnectMaxMs?: number
}

export async function loadConfig(): Promise<RelayClientConfig | undefined> {
  const url = process.env.OPENCODE_RELAY_URL
  const instanceId = process.env.OPENCODE_RELAY_INSTANCE_ID
  const pskHex = process.env.OPENCODE_RELAY_PSK

  if (!url || !instanceId || !pskHex) return undefined

  const pskBytes = parsePsk(pskHex)
  const psk = await deriveKey(pskBytes, "http")

  return { url, instanceId, psk }
}

export * as Config from "./config"
