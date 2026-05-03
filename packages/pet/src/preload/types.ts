// v4: Activity states (persistent, loop animations) + Reaction states (transient, revert to activity)
export type PetState =
  // Activity — persistent, loop animations
  | "idle"
  | "thinking"
  | "generating"
  | "acting"
  | "waiting-user"
  | "retrying"
  | "sleeping"
  | "disconnected"
  // Reaction — transient, auto-revert to current activity
  | "greeting"
  | "chatting"
  | "file-written"
  | "completed"
  | "upgraded"
  | "error"
  | "pty-exited"
  | "compacted"
  | "env-changed"

export type OpenCodeConnectionStatus = "connected" | "disconnected" | "connecting"

export type ServerConfig = {
  url: string
  username: string
  password: string
}

export type PetApi = {
  // Pet state
  getPetState: () => Promise<PetState>
  onPetStateChange: (callback: (state: PetState) => void) => () => void

  // Pet AI comment bubble
  onPetBubble: (callback: (text: string) => void) => () => void

  // Direct chat
  sendChatMessage: (text: string) => void

  // OpenCode connection
  getConnectionStatus: () => Promise<OpenCodeConnectionStatus>
  getServerConfig: () => Promise<ServerConfig>
  setServerConfig: (config: ServerConfig) => void
  connect: () => Promise<void>
  disconnect: () => Promise<void>

  // Window
  setAlwaysOnTop: (flag: boolean) => Promise<void>
  setClickThrough: (flag: boolean) => Promise<void>

  // Skins
  listSkins: () => Promise<string[]>
  getSkinModel: (name: string) => Promise<string | null>
  setSkin: (name: string) => void

  // Window control
  showWindow: () => Promise<void>
  hideWindow: () => Promise<void>
  resizeWindow: (width: number, height: number) => Promise<void>
  moveWindowBy: (dx: number, dy: number) => Promise<void>
  quit: () => void
}
