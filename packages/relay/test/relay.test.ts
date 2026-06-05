import { describe, expect, test, beforeAll, afterAll } from "bun:test"
import { start, stop, Registry } from "../src/server"
import { encrypt, decrypt, deriveKey, parsePsk } from "@opencode-ai/crypto-e2ee"
import type { RelayEnvelope } from "../src/protocol"

const TEST_PORT = 19876
const TEST_PSK = new Uint8Array(32).fill(0x42)
const TEST_INSTANCE = "test-instance-1"

let httpKey: CryptoKey
let sseKey: CryptoKey

beforeAll(async () => {
  httpKey = await deriveKey(TEST_PSK, "http")
  sseKey = await deriveKey(TEST_PSK, "sse")
  await start({ port: TEST_PORT, heartbeatInterval: 60000, heartbeatTimeout: 120000 })
})

afterAll(() => {
  stop()
})

const BASE = `http://127.0.0.1:${TEST_PORT}`

describe("relay server", () => {
  test("health endpoint returns ok", async () => {
    const res = await fetch(`${BASE}/health`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as { status: string; instances: number }
    expect(body.status).toBe("ok")
    expect(body.instances).toBe(0)
  })

  test("unknown path returns 404", async () => {
    const res = await fetch(`${BASE}/unknown`)
    expect(res.status).toBe(404)
  })

  test("HTTP request to missing instance returns 404", async () => {
    const envelope = await encrypt(httpKey, { method: "GET", path: "/session" })
    const res = await fetch(`${BASE}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: "nonexistent", envelope }),
    })
    expect(res.status).toBe(404)
  })

  test("instances endpoint returns empty list when no instances registered", async () => {
    const res = await fetch(`${BASE}/relay/instances`)
    expect(res.status).toBe(200)
    const body = (await res.json()) as { instances: Array<{ id: string; online: boolean }> }
    expect(body.instances).toEqual([])
  })
})

describe("instance registration", () => {
  test("instance can register via WebSocket", async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/_register?instance=${TEST_INSTANCE}&version=1.0.0`)

    const registered = await new Promise<string>((resolve) => {
      ws.onmessage = (e) => resolve(e.data as string)
    })

    const msg = JSON.parse(registered)
    expect(msg.type).toBe("registered")
    expect(msg.status).toBe("ok")
    expect(Registry.hasInstance(TEST_INSTANCE)).toBe(true)

    const instancesRes = await fetch(`${BASE}/relay/instances`)
    const instancesBody = (await instancesRes.json()) as { instances: Array<{ id: string; online: boolean; version: string }> }
    expect(instancesBody.instances.length).toBeGreaterThanOrEqual(1)
    expect(instancesBody.instances.find((i) => i.id === TEST_INSTANCE)).toBeDefined()
    expect(instancesBody.instances.find((i) => i.id === TEST_INSTANCE)?.online).toBe(true)

    ws.close()
    await new Promise((r) => setTimeout(r, 100))
  })
})

describe("full HTTP request flow", () => {
  let instanceWs: WebSocket

  beforeAll(async () => {
    instanceWs = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/_register?instance=${TEST_INSTANCE}-http&version=1.0.0`)
    await new Promise<void>((resolve) => {
      instanceWs.onmessage = (e) => {
        const msg = JSON.parse(e.data as string)
        if (msg.type === "registered") resolve()
      }
    })
  })

  afterAll(() => {
    instanceWs.close()
  })

  test("client sends encrypted request, instance responds with encrypted response", async () => {
    const requestPayload = { method: "POST", path: "/session", body: { message: "hello" } }
    const requestEnvelope = await encrypt(httpKey, requestPayload)

    const responsePayload = { status: 200, body: { id: "session-123", title: "Test" } }

    instanceWs.onmessage = async (e) => {
      const msg = JSON.parse(e.data as string)
      if (msg.type === "request") {
        const decrypted = await decrypt(httpKey, msg.envelope)
        expect(decrypted).toEqual(requestPayload)

        const responseEnvelope = await encrypt(httpKey, responsePayload)
        instanceWs.send(JSON.stringify({ type: "response", id: msg.id, envelope: responseEnvelope }))
      }
    }

    const res = await fetch(`${BASE}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: `${TEST_INSTANCE}-http`, envelope: requestEnvelope }),
    })

    expect(res.status).toBe(200)
    const body = (await res.json()) as { envelope: any }
    expect(body.envelope).toBeDefined()

    const decryptedResponse = await decrypt(httpKey, body.envelope)
    expect(decryptedResponse).toEqual(responsePayload)
  })

  test("instance not responding causes gateway timeout", async () => {
    const envelope = await encrypt(httpKey, { method: "GET", path: "/session" })

    instanceWs.onmessage = () => {}

    const res = await fetch(`${BASE}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: `${TEST_INSTANCE}-http`, envelope, timeout: 2000 }),
      signal: AbortSignal.timeout(10000),
    })

    expect(res.status).toBe(504)
  })
})
