import fs from "node:fs"
import path from "node:path"

export type AuthConfig = Record<
  string,
  {
    type: string
    key: string
  }
>

export type ProjectConfig = {
  gitlabUrl: string
  gitlabToken: string
  webhookSecret: string
}

export type Config = {
  port: number
  workspaceBase: string
  opencodeModel: string
  mention: string
  auth: AuthConfig
  projects: Record<string, ProjectConfig>
}

function resolveEnvVars(obj: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(obj)) {
    if (typeof value === "string" && value.startsWith("$")) {
      const envValue = process.env[value.slice(1)]
      if (!envValue) {
        console.warn(`Warning: environment variable ${value.slice(1)} is not set`)
        result[key] = ""
      } else {
        result[key] = envValue
      }
    } else {
      result[key] = value
    }
  }
  return result
}

function resolveAuthEnvVars(auth: AuthConfig): AuthConfig {
  const result: AuthConfig = {}
  for (const [provider, config] of Object.entries(auth)) {
    let key = config.key
    if (key.startsWith("$")) {
      key = process.env[key.slice(1)] ?? ""
    }
    result[provider] = { type: config.type, key }
  }
  return result
}

function resolveProjectsEnvVars(
  projects: Record<string, { gitlabUrl: string; gitlabToken: string; webhookSecret: string }>,
): Record<string, ProjectConfig> {
  const result: Record<string, ProjectConfig> = {}
  for (const [slug, config] of Object.entries(projects)) {
    const resolved = resolveEnvVars({ gitlabToken: config.gitlabToken, webhookSecret: config.webhookSecret })
    result[slug] = {
      gitlabUrl: config.gitlabUrl,
      gitlabToken: resolved.gitlabToken,
      webhookSecret: resolved.webhookSecret,
    }
  }
  return result
}

export function loadConfig(configPath?: string): Config {
  const resolved = configPath ?? path.resolve(import.meta.dir, "..", "config.json")
  if (!fs.existsSync(resolved)) {
    throw new Error(`Config file not found: ${resolved}. Copy config.example.json to config.json and edit it.`)
  }
  const raw = JSON.parse(fs.readFileSync(resolved, "utf-8")) as Config
  return {
    ...raw,
    auth: resolveAuthEnvVars(raw.auth),
    projects: resolveProjectsEnvVars(raw.projects),
  }
}
