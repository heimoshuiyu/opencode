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
