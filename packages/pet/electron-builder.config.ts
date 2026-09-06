import type { Configuration } from "electron-builder"

const config: Configuration = {
  appId: "ai.opencode.pet",
  productName: "OpenCode Pet",
  artifactName: "opencode-pet-${os}-${arch}.${ext}",
  directories: {
    output: "dist",
    buildResources: "resources",
  },
  files: ["out/**/*"],
  mac: {
    category: "public.app-category.entertainment",
    icon: "resources/icons/icon.icns",
    target: ["dmg"],
  },
  win: {
    icon: "resources/icons/icon.ico",
    target: ["nsis"],
  },
  nsis: {
    oneClick: false,
    allowToChangeInstallationDirectory: true,
  },
  linux: {
    icon: "resources/icons",
    category: "Utility",
    target: ["AppImage", "deb"],
  },
}

export default config
