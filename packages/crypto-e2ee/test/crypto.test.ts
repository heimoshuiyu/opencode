import { describe, expect, test } from "bun:test"
import { encrypt, decrypt, deriveKey, parsePsk } from "../src/index"

const TEST_PSK = new Uint8Array(32).fill(0xab)

describe("key derivation", () => {
  test("deriveKey returns a valid CryptoKey", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    expect(key.type).toBe("secret")
    expect(key.algorithm.name).toBe("AES-GCM")
  })

  test("same PSK + context produces same key", async () => {
    const key1 = await deriveKey(TEST_PSK, "http")
    const key2 = await deriveKey(TEST_PSK, "http")
    const plaintext = { hello: "world" }
    const envelope = await encrypt(key1, plaintext)
    const result = await decrypt(key2, envelope)
    expect(result).toEqual(plaintext)
  })

  test("different contexts produce different keys", async () => {
    const httpKey = await deriveKey(TEST_PSK, "http")
    const sseKey = await deriveKey(TEST_PSK, "sse")
    const plaintext = { secret: "data" }
    const envelope = await encrypt(httpKey, plaintext)
    await expect(decrypt(sseKey, envelope)).rejects.toThrow()
  })

  test("different PSKs produce different keys", async () => {
    const psk2 = new Uint8Array(32).fill(0xcd)
    const key1 = await deriveKey(TEST_PSK, "http")
    const key2 = await deriveKey(psk2, "http")
    const plaintext = { secret: "data" }
    const envelope = await encrypt(key1, plaintext)
    await expect(decrypt(key2, envelope)).rejects.toThrow()
  })

  test("parsePsk parses valid hex", () => {
    const hex = "a3f8c2d1e5b7943f6d0a1c8b2e4d5f7a9b0c3d6e8f1a2b4c5d7e9f0a1b2c3d4e"
    const psk = parsePsk(hex)
    expect(psk.length).toBe(32)
    expect(psk[0]).toBe(0xa3)
    expect(psk[31]).toBe(0x4e)
  })

  test("parsePsk rejects invalid length", () => {
    expect(() => parsePsk("abcdef")).toThrow("64 hex characters")
  })
})

describe("encrypt/decrypt", () => {
  test("roundtrip with object", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const data = { method: "POST", path: "/session", body: { message: "hello" } }
    const envelope = await encrypt(key, data)
    expect(envelope.nonce).toBeDefined()
    expect(envelope.ciphertext).toBeDefined()
    expect(typeof envelope.nonce).toBe("string")
    expect(typeof envelope.ciphertext).toBe("string")
    const result = await decrypt(key, envelope)
    expect(result).toEqual(data)
  })

  test("roundtrip with array", async () => {
    const key = await deriveKey(TEST_PSK, "sse")
    const data = [{ event: "message.updated" }, { event: "session.created" }]
    const envelope = await encrypt(key, data)
    const result = await decrypt<typeof data>(key, envelope)
    expect(result).toEqual(data)
  })

  test("roundtrip with string", async () => {
    const key = await deriveKey(TEST_PSK, "ws")
    const data = "terminal output here"
    const envelope = await encrypt(key, data)
    const result = await decrypt(key, envelope)
    expect(result).toBe(data)
  })

  test("roundtrip with null", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const envelope = await encrypt(key, null)
    const result = await decrypt(key, envelope)
    expect(result).toBeNull()
  })

  test("roundtrip with large payload", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const data = { content: "x".repeat(100_000) }
    const envelope = await encrypt(key, data)
    const result = await decrypt(key, envelope)
    expect(result).toEqual(data)
  })

  test("each encryption uses different nonce", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const data = { same: "data" }
    const e1 = await encrypt(key, data)
    const e2 = await encrypt(key, data)
    expect(e1.nonce).not.toBe(e2.nonce)
    expect(e1.ciphertext).not.toBe(e2.ciphertext)
  })

  test("tampered ciphertext fails to decrypt", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const envelope = await encrypt(key, { secret: "data" })
    const raw = Buffer.from(envelope.ciphertext, "base64")
    raw[0] ^= 0xff
    envelope.ciphertext = Buffer.from(raw).toString("base64")
    await expect(decrypt(key, envelope)).rejects.toThrow()
  })

  test("tampered nonce fails to decrypt", async () => {
    const key = await deriveKey(TEST_PSK, "http")
    const envelope = await encrypt(key, { secret: "data" })
    const raw = Buffer.from(envelope.nonce, "base64")
    raw[0] ^= 0xff
    envelope.nonce = Buffer.from(raw).toString("base64")
    await expect(decrypt(key, envelope)).rejects.toThrow()
  })
})
