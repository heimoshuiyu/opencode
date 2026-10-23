import type { Accessor } from "solid-js"
import { useServer } from "@/runtime/server/current"
import { useServerSDK } from "@/runtime/server/client"
import { authTokenFromCredentials } from "@/runtime/server/api"
import { uuid } from "@/runtime/persistence/uuid"
import { useWorkspaceLocation } from "@/workspaces/location"
import { useLanguage } from "@/runtime/i18n/language"
import { bytesToBase64 } from "@opencode/util/base64"
import type { ComposerControls } from "../adapter"

// Where a prompt is headed: the model that reads it and the server that runs its tools.
export type AttachmentDestination = {
  /** Input modalities the selected model reads natively. */
  input: { image: boolean; audio: boolean; pdf: boolean }
  /** The server shares the client's filesystem, so an attachment's source path resolves as-is. */
  local: boolean
  /** Streams a file into the server's temporary directory and returns its absolute path there. */
  upload: (file: File, report: (loaded: number) => void, signal: AbortSignal) => Promise<string>
  transcribe: (file: File, mime: string) => Promise<string>
}

export function useAttachmentDestination(controls: Accessor<ComposerControls>) {
  const server = useServer()
  const sdk = useServerSDK()
  const location = useWorkspaceLocation()
  const language = useLanguage()

  return (): AttachmentDestination => ({
    input: controls().model.selection.current()?.capabilities.input ?? { image: false, audio: false, pdf: false },
    local: server.isLocal,
    upload: async (file, report, signal) => {
      const info = await sdk.api.server.info({ signal })
      // One directory per upload keeps the original filename without collisions; the server
      // normalizes the separators and returns the resolved path.
      const url = new URL("/api/experimental/fs/write", server.conn.http.url)
      url.searchParams.set("location[directory]", location().directory)
      url.searchParams.set("path", `${info.paths.tmp}/uploads/${uuid()}/${file.name}`)

      return write(url, file, server.conn.http.password, report, signal)
    },
    transcribe: async (file, mime) => {
      const audio = bytesToBase64(new Uint8Array(await file.arrayBuffer()))

      const result = await sdk.api.transcription.transcribe({
        location: { directory: location().directory },
        audio,
        mime,
      })

      const text = result.data.text?.trim()

      if (!text) throw new Error(language.t("prompt.toast.voiceEmpty.description"))

      return text
    },
  })
}

// fetch cannot report upload progress and Chromium only streams request bodies over HTTP/2, so
// the one request that needs both goes through XMLHttpRequest. The browser streams the File
// from disk; nothing is buffered in the renderer.
function write(url: URL, file: File, password: string | undefined, report: (loaded: number) => void, signal: AbortSignal) {
  return new Promise<string>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open("POST", url)
    xhr.responseType = "json"
    xhr.setRequestHeader("content-type", "application/octet-stream")

    if (password) xhr.setRequestHeader("authorization", `Basic ${authTokenFromCredentials({ password })}`)
    xhr.upload.addEventListener("progress", (event) => report(event.loaded))
    xhr.addEventListener("load", () => {
      if (xhr.status !== 200) return reject(new Error(`Upload failed with status ${xhr.status}`))
      // SAFETY: a 200 from `fs.write` is its declared success body, `Location.response(FileSystem.Write)`.
      resolve((xhr.response as { data: { path: string } }).data.path)
    })
    xhr.addEventListener("error", () => reject(new Error("Upload failed")))
    xhr.addEventListener("abort", () => reject(new DOMException("Upload aborted", "AbortError")))
    signal.addEventListener("abort", () => xhr.abort(), { once: true })
    xhr.send(file)
  })
}
