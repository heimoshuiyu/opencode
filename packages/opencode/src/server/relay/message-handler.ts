import * as Server from "../server"
import type { DecryptedRequest, DecryptedResponse } from "./types"

export async function handleRelayMessage(request: DecryptedRequest): Promise<DecryptedResponse> {
  try {
    const url = new URL(request.path, "http://localhost")
    if (request.query) {
      for (const [k, v] of Object.entries(request.query)) {
        url.searchParams.set(k, v)
      }
    }

    const headers = new Headers(request.headers)
    if (!headers.has("content-type")) {
      headers.set("content-type", "application/json")
    }

    const init: RequestInit = {
      method: request.method,
      headers,
    }

    if (request.body !== undefined && request.method !== "GET" && request.method !== "HEAD") {
      init.body = JSON.stringify(request.body)
    }

    const req = new Request(url.toString(), init)
    const res = await Server.Default().app.fetch(req)

    const resHeaders: Record<string, string> = {}
    res.headers.forEach((v, k) => {
      resHeaders[k] = v
    })

    const contentType = res.headers.get("content-type") ?? ""
    let body: unknown
    if (contentType.includes("text/event-stream")) {
      body = await collectSSEBody(res)
    } else if (contentType.includes("application/json")) {
      body = await res.json().catch(() => null)
    } else {
      body = await res.text().catch(undefined)
    }

    return {
      status: res.status,
      headers: resHeaders,
      body,
    }
  } catch (err) {
    return {
      status: 500,
      body: { error: err instanceof Error ? err.message : "internal relay error" },
    }
  }
}

async function collectSSEBody(res: Response): Promise<string[]> {
  const events: string[] = []
  if (!res.body) return events
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ""
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += value
      const parts = buffer.split("\n\n")
      buffer = parts.pop() ?? ""
      for (const part of parts) {
        const dataLine = part.split("\n").find((l) => l.startsWith("data: "))
        if (dataLine) events.push(dataLine.slice(6))
      }
    }
  } finally {
    reader.releaseLock()
  }
  return events
}

export * as MessageHandler from "./message-handler"
