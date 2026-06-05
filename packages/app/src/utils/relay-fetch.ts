import { relayEncrypt, relayDecrypt } from "@/context/relay"

export type RelayFetchConfig = {
  relayUrl: string
  psk: string
  instanceId: string
}

export function createRelayFetch(config: RelayFetchConfig) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = input instanceof Request ? input : new Request(input, init)

    const url = new URL(req.url)
    const payload: Record<string, unknown> = {
      method: req.method,
      path: url.pathname + url.search,
    }

    const headers: Record<string, string> = {}
    req.headers.forEach((v, k) => {
      headers[k] = v
    })
    if (Object.keys(headers).length > 0) payload.headers = headers

    if (req.body) {
      const contentType = req.headers.get("content-type") ?? ""
      if (contentType.includes("application/json")) {
        payload.body = await req.json()
      } else {
        payload.body = await req.text()
      }
    }

    const envelope = await relayEncrypt(config.psk, payload)

    const relayRes = await fetch(`${config.relayUrl}/relay/http`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ instance: config.instanceId, envelope }),
      signal: init?.signal ?? req.signal,
    })

    if (!relayRes.ok) {
      const errBody = await relayRes.json().catch(() => ({ error: relayRes.statusText }))
      throw new Error(`relay error: ${(errBody as Record<string, unknown>).error ?? relayRes.statusText}`)
    }

    const relayBody = (await relayRes.json()) as { envelope: { nonce: string; ciphertext: string } }
    const decrypted = await relayDecrypt(config.psk, relayBody.envelope)
    const responsePayload = decrypted as { status?: number; headers?: Record<string, string>; body?: unknown }

    const status = responsePayload.status ?? 200
    const body = responsePayload.body !== undefined ? JSON.stringify(responsePayload.body) : ""
    const responseHeaders = new Headers(responsePayload.headers)
    if (!responseHeaders.has("content-type")) responseHeaders.set("content-type", "application/json")

    return new Response(body, { status, headers: responseHeaders })
  }
}
