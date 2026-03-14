import { Flag } from "../flag/flag"
import { gunzipSync } from "node:zlib"

// At build time, this is replaced with a Record<string, { d?: string, g?: string }>
// where d = raw base64 content (for binary assets), g = gzip base64 (for text assets).
// At dev time it's undefined.
declare const OPENCODE_WEB_ASSETS: Record<string, { d?: string; g?: string }> | undefined

const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".mjs": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".wasm": "application/wasm",
  ".txt": "text/plain; charset=utf-8",
  ".map": "application/json; charset=utf-8",
}

const assets = typeof OPENCODE_WEB_ASSETS !== "undefined" ? OPENCODE_WEB_ASSETS : undefined

export function hasEmbedded(): boolean {
  return assets !== undefined && Object.keys(assets).length > 0
}

function ext(p: string) {
  const dot = p.lastIndexOf(".")
  return dot >= 0 ? p.slice(dot).toLowerCase() : ""
}

function mime(p: string) {
  return MIME[ext(p)] ?? "application/octet-stream"
}

function b64decode(s: string) {
  const bin = atob(s)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

// Cache decompressed bytes by path
const cache = new Map<string, Uint8Array>()

export function serveEmbedded(p: string): Response | undefined {
  if (!assets) return undefined

  // Strip leading slash, normalize
  let key = p.startsWith("/") ? p.slice(1) : p
  if (!key) key = "index.html"

  const entry = assets[key]
  if (!entry) return undefined

  // Check byte cache
  let bytes = cache.get(key)
  if (!bytes) {
    if (entry.d) {
      bytes = b64decode(entry.d)
    } else if (entry.g) {
      bytes = gunzipSync(b64decode(entry.g))
    } else {
      return undefined
    }
    cache.set(key, bytes)
  }

  const cc = key === "index.html"
    ? "no-cache"
    : key.startsWith("assets/")
      ? "public, max-age=31536000, immutable"
      : "public, max-age=86400"

  return new Response(new Uint8Array(bytes), {
    headers: {
      "content-type": mime(key),
      "cache-control": "public, max-age=86400",
    },
  })
}

// When OPENCODE_WEB_URL is set, always proxy to remote (no embedded assets)
export function shouldProxy(): boolean {
  return Flag.OPENCODE_WEB_URL !== undefined && Flag.OPENCODE_WEB_URL !== ""
}

