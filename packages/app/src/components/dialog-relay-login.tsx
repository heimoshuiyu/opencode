import { Button } from "@opencode-ai/ui/button"
import { Dialog } from "@opencode-ai/ui/dialog"
import { IconButton } from "@opencode-ai/ui/icon-button"
import { TextField } from "@opencode-ai/ui/text-field"
import { showToast } from "@opencode-ai/ui/toast"
import { useDialog } from "@opencode-ai/ui/context/dialog"
import { createStore } from "solid-js/store"
import { useRelay, normalizeRelayUrl } from "@/context/relay"
import { For, Show } from "solid-js"

export function DialogRelayLogin() {
  const dialog = useDialog()
  const relay = useRelay()

  const [form, setForm] = createStore({
    url: "",
    psk: "",
    error: "",
    busy: false,
  })

  const submit = async () => {
    const url = normalizeRelayUrl(form.url)
    if (!url) {
      setForm("error", "Invalid URL")
      return
    }
    if (!form.psk || form.psk.length !== 64) {
      setForm("error", "PSK must be 64 hex characters")
      return
    }

    setForm("busy", true)
    setForm("error", "")

    try {
      const res = await fetch(`${url}/relay/instances`, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) throw new Error(`Relay returned ${res.status}`)
      relay.addRelay({ url, psk: form.psk })
      setForm("url", "")
      setForm("psk", "")
    } catch (err) {
      setForm("error", err instanceof Error ? err.message : String(err))
      showToast({
        variant: "error",
        title: "Failed to connect to relay",
        description: err instanceof Error ? err.message : String(err),
      })
    } finally {
      setForm("busy", false)
    }
  }

  const keyDown = (event: KeyboardEvent) => {
    event.stopPropagation()
    if (event.key === "Escape") {
      event.preventDefault()
      dialog.close()
      return
    }
    if (event.key !== "Enter" || event.isComposing) return
    event.preventDefault()
    void submit()
  }

  return (
    <Dialog title="Relay Login">
      <div class="flex flex-col gap-3 px-5 pb-5">
        <div class="text-14-regular text-text-weak">
          Connect to a relay server to access remote OpenCode instances
        </div>
        <div class="bg-surface-base rounded-md p-5 flex flex-col gap-3">
          <TextField
            type="text"
            label="Relay URL"
            placeholder="relay.example.com"
            value={form.url}
            autofocus
            validationState={form.error ? "invalid" : "valid"}
            error={form.error}
            disabled={form.busy}
            onChange={(v) => setForm("url", v)}
            onKeyDown={keyDown}
          />
          <TextField
            type="password"
            label="PSK (Pre-Shared Key)"
            placeholder="64 hex characters"
            value={form.psk}
            disabled={form.busy}
            onChange={(v) => setForm("psk", v)}
            onKeyDown={keyDown}
          />
        </div>

        <div class="flex justify-end gap-2">
          <Button variant="secondary" onClick={dialog.close}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={() => void submit()}
            disabled={form.busy || !form.url || !form.psk}
          >
            {form.busy ? "Connecting..." : "Connect"}
          </Button>
        </div>

        <Show when={relay.credentials().length > 0}>
          <div class="border-t border-border-weak-base pt-3">
            <div class="text-14-medium text-text-base mb-2">Connected Relays</div>
            <div class="flex flex-col gap-1">
              <For each={relay.connections()}>
                {(conn) => (
                  <div class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-surface-raised-base-hover">
                    <div
                      classList={{
                        "size-1.5 rounded-full shrink-0": true,
                        "bg-icon-success-base": conn.connected,
                        "bg-icon-critical-base": !conn.connected && !!conn.error,
                        "bg-border-weak-base": !conn.connected && !conn.error,
                      }}
                    />
                    <span class="text-14-regular text-text-base truncate flex-1">
                      {conn.credentials.url.replace(/^https?:\/\//, "")}
                    </span>
                    <span class="text-12-regular text-text-weak">
                      {conn.connected ? `${conn.instances.length} instance${conn.instances.length !== 1 ? "s" : ""}` : "offline"}
                    </span>
                    <IconButton
                      variant="ghost"
                      size="small"
                      icon="close"
                      label="Remove"
                      onClick={() => relay.removeRelay(conn.credentials.url)}
                    />
                  </div>
                )}
              </For>
            </div>
          </div>
        </Show>
      </div>
    </Dialog>
  )
}
