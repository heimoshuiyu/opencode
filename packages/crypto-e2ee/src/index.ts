import { Envelope, encodeEnvelope, decodeEnvelope } from "./envelope"

export { Envelope } from "./envelope"
export { deriveKey, parsePsk } from "./key"
export type { KeyContext } from "./key"

export async function encrypt(key: CryptoKey, plaintext: unknown): Promise<Envelope> {
  const nonce = crypto.getRandomValues(new Uint8Array(12))
  const data = new TextEncoder().encode(JSON.stringify(plaintext))
  const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce.buffer as ArrayBuffer, tagLength: 128 }, key, data)
  return encodeEnvelope(nonce, new Uint8Array(ciphertext))
}

export async function decrypt<T = unknown>(key: CryptoKey, envelope: Envelope): Promise<T> {
  const { nonce, ciphertext } = decodeEnvelope(envelope)
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce.buffer as ArrayBuffer, tagLength: 128 }, key, ciphertext.buffer as ArrayBuffer)
  return JSON.parse(new TextDecoder().decode(plaintext)) as T
}

export * as CryptoE2EE from "./index"
