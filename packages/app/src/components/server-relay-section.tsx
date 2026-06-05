import { Button } from "@opencode-ai/ui/button"
import { useDialog } from "@opencode-ai/ui/context/dialog"
import { Icon } from "@opencode-ai/ui/icon"
import { Show, createMemo, For, onCleanup } from "solid-js"
import { useRelay, type RelayInstance } from "@/context/relay"
import { ServerConnection, useServer } from "@/context/server"
import { useNavigate } from "@solidjs/router"

type RelayInstanceItem = { relay: { url: string; psk: string }; instance: RelayInstance }

export function RelaySection() {
  const relay = useRelay()
  const server = useServer()
  const navigate = useNavigate()
  const dialog = useDialog()

  const relayInstances = createMemo(() => {
    const result: RelayInstanceItem[] = []
    for (const conn of relay.connections()) {
      if (!conn.connected) continue
      for (const inst of conn.instances) {
        result.push({ relay: conn.credentials, instance: inst })
      }
    }
    return result
  })

  let dialogRun = 0
  let dialogDead = false
  onCleanup(() => {
    dialogDead = true
    dialogRun += 1
  })

  return (
    <>
      <Show when={relayInstances().length > 0}>
        <div class="flex items-center gap-2 mt-2 mb-1 px-3">
          <div class="flex-1 h-px bg-border-weak-base" />
          <span class="text-11-regular text-text-weak">Relay</span>
          <div class="flex-1 h-px bg-border-weak-base" />
        </div>
        <For each={relayInstances()}>
          {(item) => {
            const relayKey = `relay:${item.relay.url}:${item.instance.id}`
            const isActive = createMemo(() => server.key === relayKey)
            return (
              <button
                type="button"
                class="flex items-center gap-2 w-full h-8 pl-3 pr-1.5 py-1.5 rounded-md transition-colors text-left hover:bg-surface-raised-base-hover"
                disabled={!item.instance.online}
                classList={{ "cursor-not-allowed": !item.instance.online }}
                onClick={() => {
                  if (!item.instance.online) return
                  const key = ServerConnection.Key.make(relayKey)
                  navigate("/")
                  queueMicrotask(() => server.setActive(key))
                }}
              >
                <div
                  classList={{
                    "size-1.5 rounded-full shrink-0": true,
                    "bg-icon-success-base": item.instance.online,
                    "bg-icon-critical-base": !item.instance.online,
                  }}
                />
                <div class="flex flex-col min-w-0 flex-1">
                  <span class="text-14-regular text-text-base truncate">{item.instance.id}</span>
                  <span class="text-12-regular text-text-weak truncate">
                    {item.relay.url.replace(/^https?:\/\//, "")} · v{item.instance.version}
                  </span>
                </div>
                <Show when={item.instance.online}>
                  <span class="text-11-regular text-icon-success-base">online</span>
                </Show>
                <Show when={isActive()}>
                  <Icon name="check" size="small" class="text-icon-weak shrink-0" />
                </Show>
              </button>
            )
          }}
        </For>
      </Show>

      <div class="flex gap-2 mt-3">
        <Button
          variant="secondary"
          class="self-start h-8 px-3 py-1.5"
          onClick={() => {
            const run = ++dialogRun
            void import("./dialog-relay-login").then((x) => {
              if (dialogDead || dialogRun !== run) return
              dialog.show(() => <x.DialogRelayLogin />)
            })
          }}
        >
          Relay
        </Button>
      </div>
    </>
  )
}
