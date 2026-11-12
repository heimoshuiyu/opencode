import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import app, { channel } from "../../app/vite.js"
import { icons } from "../../app/vite.icons"
import { androidAudio } from "./web-audio"

const hash = createHash("sha256")
  .update(readFileSync(new URL("../../app/public/oc-theme-preload.js", import.meta.url)))
  .digest("base64")

export default {
  plugins: [
    androidAudio(),
    app,
    icons(channel),
    {
      name: "opencode-android:content-policy",
      transformIndexHtml() {
        return [{
          tag: "meta",
          attrs: {
            "http-equiv": "Content-Security-Policy",
            content: `default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'sha256-${hash}'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src https: wss: 'self' http://127.0.0.1:* ws://127.0.0.1:* blob: data:; worker-src 'self' blob:; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'`,
          },
          injectTo: "head-prepend",
        }]
      },
    },
  ],
  define: { "import.meta.env.VITE_OPENCODE_SERVER_MODE": JSON.stringify("none") },
  build: { target: "esnext" },
}
