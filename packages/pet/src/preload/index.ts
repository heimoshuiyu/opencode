import { contextBridge, ipcRenderer } from "electron"
import type { PetApi, PetState, ServerConfig } from "./types"

const api: PetApi = {
  getPetState: () => ipcRenderer.invoke("get-pet-state"),
  onPetStateChange: (callback) => {
    const handler = (_: unknown, state: PetState) => callback(state)
    ipcRenderer.on("pet-state", handler)
    return () => ipcRenderer.removeListener("pet-state", handler)
  },

  onPetBubble: (callback) => {
    const handler = (_: unknown, text: string) => callback(text)
    ipcRenderer.on("pet-bubble", handler)
    return () => ipcRenderer.removeListener("pet-bubble", handler)
  },

  sendChatMessage: (text) => ipcRenderer.send("pet-chat-send", text),

  getConnectionStatus: () => ipcRenderer.invoke("get-connection-status"),
  getServerConfig: () => ipcRenderer.invoke("get-server-config"),
  setServerConfig: (config: ServerConfig) => ipcRenderer.send("set-server-config", config),
  connect: () => ipcRenderer.invoke("connect"),
  disconnect: () => ipcRenderer.invoke("disconnect"),

  setAlwaysOnTop: (flag) => ipcRenderer.invoke("set-always-on-top", flag),
  setClickThrough: (flag) => ipcRenderer.invoke("set-click-through", flag),

  listSkins: () => ipcRenderer.invoke("list-skins"),
  getSkinModel: (name) => ipcRenderer.invoke("get-skin-model", name),
  setSkin: (name) => ipcRenderer.send("set-skin", name),

  showWindow: () => ipcRenderer.invoke("show-window"),
  hideWindow: () => ipcRenderer.invoke("hide-window"),
  resizeWindow: (w, h) => ipcRenderer.invoke("resize-window", w, h),
  moveWindowBy: (dx, dy) => ipcRenderer.invoke("move-window-by", dx, dy),
  quit: () => ipcRenderer.send("quit"),
}

contextBridge.exposeInMainWorld("pet", api)
