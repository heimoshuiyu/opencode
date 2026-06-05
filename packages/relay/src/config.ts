export type RelayConfig = {
  port: number
  hostname: string
  heartbeatInterval: number
  heartbeatTimeout: number
}

const DEFAULT_CONFIG: RelayConfig = {
  port: 8080,
  hostname: "0.0.0.0",
  heartbeatInterval: 30_000,
  heartbeatTimeout: 60_000,
}

export function loadConfig(overrides?: Partial<RelayConfig>): RelayConfig {
  return {
    ...DEFAULT_CONFIG,
    port: overrides?.port ?? (process.env.RELAY_PORT ? Number(process.env.RELAY_PORT) : DEFAULT_CONFIG.port),
    hostname: overrides?.hostname ?? process.env.RELAY_HOSTNAME ?? DEFAULT_CONFIG.hostname,
    heartbeatInterval: overrides?.heartbeatInterval ?? DEFAULT_CONFIG.heartbeatInterval,
    heartbeatTimeout: overrides?.heartbeatTimeout ?? DEFAULT_CONFIG.heartbeatTimeout,
  }
}

export * as Config from "./config"
