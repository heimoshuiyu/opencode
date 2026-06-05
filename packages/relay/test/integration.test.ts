import { describe, expect, test, beforeAll, afterAll } from "bun:test"
import { start, stop, Registry } from "../src/server"
import { encrypt, decrypt, deriveKey, parsePsk } from "@opencode-ai/crypto-e2ee"
import { RelayClient, loadConfig } from "../../opencode/src/server/relay/index"

const TEST_PORT = 19877
const TEST_PSK_HEX = "a3f8c2d1e5b7943f6d0a1c8b2e4d5f7a9b0c3d6e8f1a2b4c5d7e9f0a1b2c3d4e"
const TEST_INSTANCE = "integration-test"

let httpKey: CryptoKey

beforeAll(async () => {
  const pskBytes = parsePsk(TEST_PSK_HEX)
  httpKey = await deriveKey(pskBytes, "http")
  await start({ port: TEST_PORT, heartbeatInterval: 60000, heartbeatTimeout: 120000 })
})

afterAll(() => {
  stop()
})

const BASE = `http://127.0.0.1:${TEST_PORT}`

describe("end-to-end integration", () => {
  let client: RelayClient

  beforeAll(async () => {
    const originalEnv = { ...process.env }
    process.env.OPENCODE_RELAY_URL = `ws://127.0.0.1:${TEST_PORT}`
    process.env.OPENCODE_RELAY_INSTANCE_ID = TEST_INSTANCE
    process.env.OPENCODE_RELAY_PSK = TEST_PSK_HEX

    const config = await loadConfig()
    expect(config).toBeDefined()

    client = new RelayClient(config!)
    await client.start()

    await new Promise((resolve) => setTimeout(resolve, 500))

    expect(Registry.hasInstance(TEST_INSTANCE)).toBe(true)

    process.env = originalEnv
  })

  afterAll(async () => {
    await client.stop()
  })

  test("client encrypts request → relay forwards → opencode handles → relay returns → client decrypts", async () => {
    const requestPayload = {
      method: "GET",
      path: "/session",
      query: {},
      headers: { "content-type": "application/json" },
      body: {},
    }

    const envelope = await encrypt(httpKey, requestPayload)

    const res = await fetch(`${BASE}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: TEST_INSTANCE, envelope }),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { envelope: any }
    expect(body.envelope).toBeDefined()

    const decrypted = await decrypt<{
      status: number
    }>(httpKey, body.envelope)

    expect(typeof decrypted.status).toBe("number")
  })

  test("multiple sequential encrypted requests", async () => {
    for (let i = 0; i < 5; i++) {
      const payload = {
        method: "GET",
        path: `/test/${i}`,
        body: { index: i },
      }

      const envelope = await encrypt(httpKey, payload)
      const res = await fetch(`${BASE}/relay/http`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ instance: TEST_INSTANCE, envelope }),
      })

      expect(res.status).toBe(200)
      const body = (await res.json()) as { envelope: any }
      const decrypted = await decrypt<{ status: number }>(httpKey, body.envelope)
      expect(typeof decrypted.status).toBe("number")
    }
  })

  test("request to unknown instance returns 404", async () => {
    const envelope = await encrypt(httpKey, { method: "GET", path: "/test" })
    const res = await fetch(`${BASE}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: "nonexistent", envelope }),
    })
    expect(res.status).toBe(404)
  })

  test("concurrent requests are handled independently", async () => {
    const requests = Array.from({ length: 10 }, (_, i) => {
      const payload = { method: "GET", path: `/concurrent/${i}`, body: { i } }
      return encrypt(httpKey, payload).then((envelope) =>
        fetch(`${BASE}/relay/http`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ instance: TEST_INSTANCE, envelope }),
        }),
      )
    })

    const responses = await Promise.all(requests)
    for (const res of responses) {
      expect(res.status).toBe(200)
    }
  })
})
