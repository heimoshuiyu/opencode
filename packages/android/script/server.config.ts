import { mainConfig } from "../../cli/vite.node.config"
import { nodeTarget, photonWasmAsset } from "../../cli/src/node/target"
import { readFileSync } from "node:fs"
import { createRequire } from "node:module"
import type { Plugin } from "vite"
import pkg from "../../cli/package.json"

// Reuse the Node build's plugin loaders, bundled catalogue and provider transforms. Only the JS
// prelude is shared; no Linux/glibc assets or desktop/TUI executable is shipped on Android.
const config = mainConfig({
  version: pkg.version,
  channel: "next",
  assetHash: "android",
  target: nodeTarget("linux", "arm64"),
  modelsSnapshot: readFileSync(new URL("../../core/src/models-dev/snapshot.txt", import.meta.url), "utf8"),
})

const runtimeAssets: Plugin = {
  name: "opencode-android:runtime-assets",
  generateBundle() {
    const require = createRequire(new URL("../../cli/package.json", import.meta.url))
    this.emitFile({ type: "asset", fileName: `assets/${photonWasmAsset}`, source: readFileSync(require.resolve(photonWasmAsset)) })
  },
}

export default { ...config, plugins: [...(config.plugins ?? []), runtimeAssets] }
