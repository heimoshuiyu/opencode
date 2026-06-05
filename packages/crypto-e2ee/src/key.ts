const CONTEXTS = ["http", "sse", "ws"] as const
export type KeyContext = (typeof CONTEXTS)[number]

export async function deriveKey(psk: Uint8Array, context: KeyContext): Promise<CryptoKey> {
  const prk = await crypto.subtle.importKey("raw", psk.buffer as ArrayBuffer, { name: "HKDF", hash: "SHA-256" }, false, ["deriveKey"])

  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new TextEncoder().encode(`opencode-relay-${context}`),
      info: new TextEncoder().encode("aes-256-gcm-key"),
    },
    prk,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  )
}

export function parsePsk(hex: string): Uint8Array {
  if (hex.length !== 64) throw new Error(`PSK must be 64 hex characters (256 bits), got ${hex.length}`)
  const bytes = new Uint8Array(32)
  for (let i = 0; i < 32; i++) bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return bytes
}

export * as Key from "./key"
