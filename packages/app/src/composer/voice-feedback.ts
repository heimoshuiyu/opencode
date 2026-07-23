// The mic feeds the analyser only. Only the two short cues are connected to the speakers.
export function voiceFeedback(sounds: () => boolean) {
  if (typeof AudioContext === "undefined") return
  // Capture can still work when the browser has no usable output device.
  let context: AudioContext

  try {
    context = new AudioContext()
  } catch {
    return
  }

  void context.resume().catch(() => {})
  const meter = context.createAnalyser()
  meter.fftSize = 512

  function cue(start: boolean) {
    if (!sounds() || context.state !== "running") return
    const t = context.currentTime
    const note = context.createOscillator()
    const envelope = context.createGain()
    note.frequency.setValueAtTime(start ? 480 : 650, t)
    note.frequency.exponentialRampToValueAtTime(start ? 720 : 420, t + 0.065)
    envelope.gain.setValueAtTime(0.0001, t)
    envelope.gain.exponentialRampToValueAtTime(0.065, t + 0.006)
    envelope.gain.exponentialRampToValueAtTime(0.0001, t + 0.085)
    note.connect(envelope).connect(context.destination)
    note.start(t)
    note.stop(t + 0.09)
  }

  return {
    meter,
    start(stream: MediaStream) {
      try {
        context.createMediaStreamSource(stream).connect(meter)
        cue(true)
      } catch {
        // Feedback is optional; do not discard an otherwise valid take.
      }
    },
    close(stopped: boolean) {
      if (stopped) cue(false)
      setTimeout(() => void context.close().catch(() => {}), stopped ? 110 : 0)
    },
  }
}
