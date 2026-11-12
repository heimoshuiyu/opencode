// Loaded before the app module, including after a full document navigation. Android targets
// the packaged main-frame origin with postWebMessage; never accept a port from another frame.
window.openCodeAudioPort = new Promise((resolve) => {
  function connect(event) {
    if (event.source !== null || !event.isTrusted || event.data !== "opencode:audio" || event.ports.length !== 1) return
    window.removeEventListener("message", connect)
    resolve(event.ports[0])
  }
  window.addEventListener("message", connect)
})
