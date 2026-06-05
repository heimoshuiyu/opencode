import { createSimpleContext } from "@opencode-ai/ui/context"
import { createEffect, onCleanup } from "solid-js"
import { createStore } from "solid-js/store"
import { Persist, persisted } from "@/utils/persist"
import { encrypt, decrypt, deriveKey, parsePsk } from "@opencode-ai/crypto-e2ee"
import { createRelayFetch } from "@/utils/relay-fetch"
import type { ServerConnection } from "./server"

export type RelayResolvedConnection = {
  conn: ServerConnection.Any
  fetch: typeof globalThis.fetch | undefined
}

export type RelayInstance = {
  id: string
  version: string
  online: boolean
}

export type RelayCredentials = {
  url: string
  psk: string
}

export type RelayConnection = {
  credentials: RelayCredentials
  instances: RelayInstance[]
  connected: boolean
  error?: string
}

const POLL_INTERVAL_MS = 10_000

export function normalizeRelayUrl(input: string) {
  const trimmed = input.trim()
  if (!trimmed) return
  const withProtocol = /^https?:\/\//.test(trimmed) ? trimmed : `http://${trimmed}`
  return withProtocol.replace(/\/+$/, "")
}

async function fetchInstances(relayUrl: string): Promise<RelayInstance[]> {
  const url = `${relayUrl}/relay/instances`
  const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
  if (!res.ok) throw new Error(`relay returned ${res.status}`)
  const body = (await res.json()) as { instances: RelayInstance[] }
  return body.instances ?? []
}

export async function relayEncrypt(pskHex: string, payload: unknown) {
  const pskBytes = parsePsk(pskHex)
  const key = await deriveKey(pskBytes, "http")
  return encrypt(key, payload)
}

export async function relayDecrypt(pskHex: string, envelope: { nonce: string; ciphertext: string }) {
  const pskBytes = parsePsk(pskHex)
  const key = await deriveKey(pskBytes, "http")
  return decrypt(key, envelope)
}

export const { use: useRelay, provider: RelayProvider } = createSimpleContext({
  name: "Relay",
  gate: true,
  init: () => {
    const [store, setStore, _, ready] = persisted(
      Persist.global("relay"),
      createStore({
        credentials: [] as RelayCredentials[],
      }),
    )

    const [state, setState] = createStore<RelayConnection[]>([])

    const connections = () => state

    createEffect(() => {
      if (!ready()) return

      const creds = store.credentials
      if (!creds.length) {
        setState([])
        return
      }

      const pollers = creds.map((cred) => {
        const entry: RelayConnection = {
          credentials: cred,
          instances: [],
          connected: false,
        }
        const idx = state.findIndex((c) => c.credentials.url === cred.url)
        if (idx === -1) {
          setState(state.length, entry)
        }

        let dead = false

        const poll = async () => {
          try {
            const instances = await fetchInstances(cred.url)
            if (dead) return
            const targetIdx = state.findIndex((c) => c.credentials.url === cred.url)
            if (targetIdx === -1) return
            setState(targetIdx, { instances, connected: true, error: undefined })
          } catch (err) {
            if (dead) return
            const targetIdx = state.findIndex((c) => c.credentials.url === cred.url)
            if (targetIdx === -1) return
            setState(targetIdx, {
              connected: false,
              error: err instanceof Error ? err.message : String(err),
            })
          }
        }

        void poll()
        const interval = setInterval(() => void poll(), POLL_INTERVAL_MS)

        onCleanup(() => {
          dead = true
          clearInterval(interval)
        })

        return { dead: () => dead, stop: () => { dead = true; clearInterval(interval) } }
      })

      onCleanup(() => {
        for (const p of pollers) p?.stop()
      })
    })

    function addRelay(credentials: RelayCredentials) {
      const url = normalizeRelayUrl(credentials.url)
      if (!url) return

      const normalized: RelayCredentials = { url, psk: credentials.psk }
      const existing = store.credentials.findIndex((c) => c.url === url)
      if (existing !== -1) {
        setStore("credentials", existing, normalized)
      } else {
        setStore("credentials", store.credentials.length, normalized)
      }
    }

    function removeRelay(url: string) {
      const normalized = normalizeRelayUrl(url)
      if (!normalized) return
      setStore(
        "credentials",
        store.credentials.filter((c) => c.url !== normalized),
      )
      const idx = state.findIndex((c) => c.credentials.url === normalized)
      if (idx !== -1) setState([...state.slice(0, idx), ...state.slice(idx + 1)])
    }

    function resolveConnection(activeKey: string, fallback: ServerConnection.Any) {
      const info = parseRelayKey(activeKey)
      if (!info) return { conn: fallback, fetch: undefined }
      const cred = store.credentials.find((c) => c.url === info.relayUrl)
      if (!cred) return { conn: fallback, fetch: undefined }
      return {
        conn: {
          type: "http" as const,
          http: { url: `${cred.url}/relay/http` },
          displayName: info.instanceId,
        },
        fetch: createRelayFetch({ relayUrl: cred.url, psk: cred.psk, instanceId: info.instanceId }) as typeof globalThis.fetch,
      }
    }

    return {
      ready,
      connections,
      credentials: () => store.credentials,
      addRelay,
      removeRelay,
      resolveConnection,
    }
  },
})

export function parseRelayKey(key: string) {
  if (!key.startsWith("relay:")) return
  const rest = key.slice(6)
  const sepIdx = rest.lastIndexOf(":")
  if (sepIdx === -1) return
  const relayUrl = rest.slice(0, sepIdx)
  const instanceId = rest.slice(sepIdx + 1)
  if (!relayUrl || !instanceId) return
  return { relayUrl, instanceId }
}
