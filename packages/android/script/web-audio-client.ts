declare global {
  interface Window { openCodeAudioPort: Promise<MessagePort> }
}

async function connect() {
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([window.openCodeAudioPort, new Promise<never>((_, reject) => {
      timeout = setTimeout(() => reject(new Error("Android audio is unavailable")), 4000)
    })])
  } finally { clearTimeout(timeout) }
}

export async function playSoundById(sound: string | undefined) {
  if (!sound || sound === "none") return
  const port = await connect().catch(() => undefined)
  if (!port) return
  const id = crypto.randomUUID()
  port.postMessage(JSON.stringify({ op: "play", sound, id }))
  return () => port.postMessage(JSON.stringify({ op: "quiet", id }))
}

type Hooks = {
  ready(stop: () => void, meter: Pick<AnalyserNode, "fftSize" | "getFloatTimeDomainData">): void
  done(blob: Blob): void
  error(message: string): void
}

export async function recordComposer(signal: AbortSignal, sounds: () => boolean, hooks: Hooks) {
  const port = await connect().catch((e: Error) => { if (!signal.aborted) hooks.error(e.message) })
  if (!port || signal.aborted) return
  const id = crypto.randomUUID()
  const send = (op: string) => port.postMessage(JSON.stringify({ op, id, cues: sounds() }))
  const chunks: Uint8Array<ArrayBuffer>[] = []
  let amplitude = 0
  const meter = { fftSize: 1, getFloatTimeDomainData: (data: Float32Array) => { data[0] = amplitude } }
  const cleanup = () => {
    clearTimeout(timeout)
    signal.removeEventListener("abort", abort)
    port.removeEventListener("message", receive)
    chunks.length = 0
  }
  const abort = () => { send("cancel"); cleanup() }
  const timeout = setTimeout(() => { abort(); hooks.error("Microphone permission or recording timed out") }, 60000)
  function receive(event: MessageEvent<string>) {
    const msg = JSON.parse(event.data)
    if (msg.id !== id) return
    switch (msg.event) {
      case "ready": clearTimeout(timeout); hooks.ready(() => send("finish"), meter); break
      case "level": amplitude = msg.value / 32768; break
      case "chunk": chunks.push(Uint8Array.from(atob(msg.value), (c) => c.charCodeAt(0))); break
      case "done": {
        const blob = new Blob(chunks, { type: "audio/ogg" }); cleanup(); hooks.done(blob); break
      }
      case "error": cleanup(); hooks.error(msg.value); break
    }
  }
  signal.addEventListener("abort", abort, { once: true })
  port.addEventListener("message", receive)
  port.start()
  send("record")
}
