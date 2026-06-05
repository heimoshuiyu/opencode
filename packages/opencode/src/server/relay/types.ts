export type DecryptedRequest = {
  method: string
  path: string
  query?: Record<string, string>
  headers?: Record<string, string>
  body?: unknown
}

export type DecryptedResponse = {
  status: number
  headers?: Record<string, string>
  body?: unknown
}

export type { RelayClientConfig as RelayConfig } from "./config"

export * as RelayTypes from "./types"
