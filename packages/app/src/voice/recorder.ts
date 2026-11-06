/** Each delegation gets a self-contained recording (including its own container header). */
export class VoiceRecorder {
  private recorder?: MediaRecorder

  attach(stream: MediaStream) {
    if (typeof MediaRecorder === "undefined") return
    const mimeType = ["audio/webm", "audio/mp4"].find((mime) => MediaRecorder.isTypeSupported(mime))
    if (!mimeType) return
    // Browsers can support the MIME but not this stream. RTP and the transcript still work.
    try {
      this.recorder = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 32_000 })
      this.recorder.start()
    } catch {
      this.recorder = undefined
    }
  }

  take() {
    const recorder = this.recorder
    if (!recorder || recorder.state === "inactive") return
    const mime = recorder.mimeType.split(";")[0]!
    const file = new Promise<{ uri: string; name: string } | undefined>((resolve) => {
      recorder.ondataavailable = ({ data }) => {
        if (!data.size) return resolve(undefined)
        const reader = new FileReader()
        reader.onerror = () => resolve(undefined)
        reader.onload = () => resolve({ uri: String(reader.result), name: mime === "audio/mp4" ? "voice.m4a" : "voice.webm" })
        reader.readAsDataURL(data.slice(0, data.size, mime))
      }
      recorder.onerror = () => resolve(undefined)
    })
    recorder.stop()
    this.attach(recorder.stream)
    return file
  }

  stop() {
    if (this.recorder && this.recorder.state !== "inactive") this.recorder.stop()
    this.recorder = undefined
  }
}
