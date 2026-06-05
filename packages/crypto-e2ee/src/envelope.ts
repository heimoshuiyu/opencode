export type Envelope = {
  nonce: string
  ciphertext: string
}

export function encodeEnvelope(nonce: Uint8Array, ciphertext: Uint8Array): Envelope {
  return {
    nonce: Buffer.from(nonce).toString("base64"),
    ciphertext: Buffer.from(ciphertext).toString("base64"),
  }
}

export function decodeEnvelope(envelope: Envelope): { nonce: Uint8Array; ciphertext: Uint8Array } {
  return {
    nonce: Buffer.from(envelope.nonce, "base64"),
    ciphertext: Buffer.from(envelope.ciphertext, "base64"),
  }
}

export * as Envelope from "./envelope"
