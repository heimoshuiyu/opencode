import { createRequire } from "node:module"
import { isSea } from "node:sea"
import type { spawn } from "@lydell/node-pty"
import type { Opts, Proc } from "./pty.js"

export type { Disp, Exit, Opts, Proc } from "./pty.js"

function spawnPty(file: string, args: string[], opts: Opts): Proc {
  // Headless servers can run ordinary shell tools without the optional interactive PTY addon.
  const pty = createRequire(import.meta.url)(process.env.OPENCODE_NODE_PTY_PATH ?? "@lydell/node-pty") as {
    spawn: typeof spawn
  }
  const proc = pty.spawn(file, args, process.platform === "win32" && isSea() ? { ...opts, useConptyDll: true } : opts)
  return {
    pid: proc.pid,
    onData(listener) {
      return proc.onData(listener)
    },
    onExit(listener) {
      return proc.onExit(listener)
    },
    write(data) {
      proc.write(data)
    },
    resize(cols, rows) {
      proc.resize(cols, rows)
    },
    kill(signal) {
      proc.kill(signal)
    },
  }
}

export { spawnPty as spawn }
