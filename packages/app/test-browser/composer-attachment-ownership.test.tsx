import { describe, expect, test } from "bun:test"
import { createRoot } from "solid-js"
import { createStore } from "solid-js/store"
import { createComposerAttachments } from "@/composer/attachments/attachments"
import { createComposerEditor } from "@/composer/editor/interaction"
import type { ComposerPersistedState, ComposerPrompt } from "@/composer/types"

function target() {
  const [store, setStore] = createStore<{ prompt: ComposerPrompt; cursor: number }>({
    prompt: [{ type: "text", content: "", start: 0, end: 0 }],
    cursor: 0,
  })

  return {
    prompt: store,
    capture: {
      current: () => store.prompt,
      cursor: () => store.cursor,
      set: (prompt: ComposerPrompt, cursor = store.cursor) => setStore({ prompt, cursor }),
    },
  }
}

describe("Composer attachment ownership", () => {
  test("keeps an async attachment with the Composer where it started", async () => {
    await new Promise<void>((resolveTest, rejectTest) => {
      createRoot((dispose) => {
        const first = target()
        const second = target()
        const stored = Promise.withResolvers<{ id: string; url: string }>()
        let active = first.capture

        const attachments = createComposerAttachments({
          capture: () => active,
          editor: () => document.createElement("div"),
          focusEditor() {},
          addPart: () => false,
          setDraggingType() {},
          directory: () => "C:/repo",
          destination: () => ({
            input: { image: true, audio: true, pdf: true },
            local: false,
            upload: () => Promise.reject(new Error("native attachments must not upload")),
            transcribe: () => Promise.reject(new Error("native attachments must not transcribe")),
          }),
          isDialogActive: () => false,
          duplicate() {},
          onError: rejectTest,
          onUploadError: rejectTest,
          store: () => stored.promise,
        })

        const pending = attachments.addAttachments([new File(["image"], "image.png", { type: "image/png" })])
        active = second.capture
        expect(attachments.busy()).toBe(true)
        stored.resolve({ id: "blob-1", url: "blob:test" })
        void pending.then(() => {
          expect(first.prompt.prompt.some((part) => part.type === "image" && part.blob.id === "blob-1")).toBe(true)
          expect(second.prompt.prompt.some((part) => part.type === "image")).toBe(false)
          expect(attachments.busy()).toBe(false)
          dispose()
          resolveTest()
        }, rejectTest)
      })
    })
  })

  test("routes audio by the selected model's audio capability", async () => {
    const inserted: string[] = []

    const make = (audio: boolean, current: ReturnType<typeof target>) =>
      createComposerAttachments({
        capture: () => current.capture,
        editor: () => document.createElement("div"),
        focusEditor() {},
        addPart: (part) => {
          if ("content" in part) inserted.push(part.content)

          return true
        },
        setDraggingType() {},
        directory: () => "/repo",
        destination: () => ({
          input: { image: true, audio, pdf: true },
          local: false,
          upload: () => Promise.reject(new Error("audio must not upload")),
          transcribe: () => Promise.resolve("transcribed audio"),
        }),
        isDialogActive: () => false,
        duplicate() {},
        onError(error) {
          throw error
        },
        onUploadError(error) {
          throw error
        },
        store: (file) => Promise.resolve({ id: `blob-${file.name}`, url: "blob:audio" }),
      })

    const native = target()
    await make(true, native).addAttachments([new File(["audio"], "recording.webm", { type: "audio/webm" })])
    expect(native.prompt.prompt.some((part) => part.type === "image" && part.mime === "audio/webm")).toBe(true)
    expect(inserted).toEqual([])

    const fallback = target()
    await make(false, fallback).addAttachments([new File(["audio"], "recording.webm", { type: "audio/webm" })])
    expect(fallback.prompt.prompt.some((part) => part.type === "image")).toBe(false)
    expect(inserted).toEqual(["transcribed audio"])
  })

  test("blocks actual submission while audio transcription is pending", async () => {
    await createRoot(async (dispose) => {
      try {
        const transcription = Promise.withResolvers<string>()
        const [state, setState] = createStore({ submissions: 0 })
        const store = createStore<ComposerPersistedState>({
          prompt: [{ type: "text", content: "keep my draft", start: 0, end: 13 }],
          context: { items: [] },
        })
        const editor = createComposerEditor({
          store,
          commands: () => [],
          context: () => [],
          searchContextFiles: () => [],
          view: {
            submit: {
              stopping: () => false,
              onStop() {},
              onSubmit: () => setState("submissions", (count) => count + 1),
            },
          },
          attachments: {
            directory: () => "/repo",
            destination: () => ({
              input: { image: true, audio: false, pdf: true },
              local: false,
              upload: () => Promise.reject(new Error("unexpected upload")),
              transcribe: () => transcription.promise,
            }),
            isDialogActive: () => false,
            duplicate() {},
            onError(error) {
              throw error
            },
            onUploadError(error) {
              throw error
            },
          },
        })

        editor.setEditor(document.createElement("div"))

        const adding = editor.addAttachments([new File(["audio"], "recording.webm", { type: "audio/webm" })])

        expect(editor.canSubmit()).toBe(false)
        editor.submit()
        expect(state.submissions).toBe(0)

        transcription.resolve("transcribed audio")
        await adding

        expect(editor.canSubmit()).toBe(true)
        editor.submit()
        expect(state.submissions).toBe(1)
      } finally {
        dispose()
      }
    })
  })

  test("blocks actual submission while voice is processing", () => {
    createRoot((dispose) => {
      const [state, setState] = createStore({ processing: true, submissions: 0 })
      const store = createStore<ComposerPersistedState>({
        prompt: [{ type: "text", content: "keep my draft", start: 0, end: 13 }],
        context: { items: [] },
      })
      const editor = createComposerEditor({
        store,
        busy: () => state.processing,
        commands: () => [],
        context: () => [],
        searchContextFiles: () => [],
        view: {
          submit: {
            stopping: () => false,
            onStop() {},
            onSubmit: () => setState("submissions", (count) => count + 1),
          },
        },
      })

      expect(editor.canSubmit()).toBe(false)
      editor.submit()
      expect(state.submissions).toBe(0)

      setState("processing", false)
      expect(editor.canSubmit()).toBe(true)
      editor.submit()
      expect(state.submissions).toBe(1)
      dispose()
    })
  })
})
