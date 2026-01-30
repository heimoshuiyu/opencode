import { describe, expect } from "bun:test"
import path from "path"
import { Effect, Layer } from "effect"
import { ListTool } from "../../src/tool/ls"
import { SessionID, MessageID } from "../../src/session/schema"
import * as CrossSpawnSpawner from "../../src/effect/cross-spawn-spawner"
import { Ripgrep } from "../../src/file/ripgrep"
import { AppFileSystem } from "../../src/filesystem"
import { Truncate } from "../../src/tool/truncate"
import { Agent } from "../../src/agent/agent"
import { provideTmpdirInstance } from "../fixture/fixture"
import { testEffect } from "../lib/effect"

const it = testEffect(
  Layer.mergeAll(
    CrossSpawnSpawner.defaultLayer,
    AppFileSystem.defaultLayer,
    Ripgrep.defaultLayer,
    Truncate.defaultLayer,
    Agent.defaultLayer,
  ),
)

const ctx = {
  sessionID: SessionID.make("test"),
  messageID: MessageID.make("test"),
  callID: "",
  agent: "build",
  abort: AbortSignal.any([]),
  messages: [],
  metadata: () => Effect.void,
  ask: () => Effect.void,
}

describe("tool.ls", () => {
  it.live("lists files in directory", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "file1.txt"), "content1"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "file2.txt"), "content2"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("file1.txt")
        expect(result.output).toContain("file2.txt")
      }),
    ),
  )

  it.live("handles empty directory", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain(dir)
        expect(result.metadata.count).toBe(0)
      }),
    ),
  )

  it.live("depth 0 lists only root files", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "root.txt"), "root content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 0 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).not.toContain("subdir/")
        expect(result.output).not.toContain("nested.txt")
        expect(result.output).not.toContain("deep.txt")
      }),
    ),
  )

  it.live("depth 1 lists root + 1 level subdirectories", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "root.txt"), "root content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).not.toContain("nested.txt")
        expect(result.output).not.toContain("deep/")
      }),
    ),
  )

  it.live("depth 2 lists deeper nested files", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "root.txt"), "root content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 2 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).toContain("nested.txt")
        expect(result.output).toContain("deep/")
        expect(result.output).not.toContain("deep.txt")
      }),
    ),
  )

  it.live("depth 3 lists all nested files", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "root.txt"), "root content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 3 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).toContain("nested.txt")
        expect(result.output).toContain("deep/")
        expect(result.output).toContain("deep.txt")
      }),
    ),
  )

  it.live("respects ignore patterns", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "keep.txt"), "keep"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "ignore.txt"), "ignore"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "node_modules", "module.js"), "module"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ ignore: ["ignore.txt"], depth: 1 }, ctx)
        expect(result.output).toContain("keep.txt")
        expect(result.output).not.toContain("ignore.txt")
        expect(result.output).not.toContain("node_modules/")
      }),
    ),
  )

  it.live("ignores default ignore patterns", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "keep.txt"), "keep"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "node_modules", "module.js"), "module"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "dist", "output.js"), "output"))
        yield* Effect.promise(() => Bun.write(path.join(dir, ".git", "config"), "config"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("keep.txt")
        expect(result.output).not.toContain("node_modules/")
        expect(result.output).not.toContain("dist/")
        expect(result.output).not.toContain(".git/")
      }),
    ),
  )

  it.live("limits file count to 100", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        for (let i = 0; i < 150; i++) {
          yield* Effect.promise(() => Bun.write(path.join(dir, `file${i}.txt`), `content${i}`))
        }
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.metadata.count).toBe(100)
        expect(result.metadata.truncated).toBe(true)
      }),
    ),
  )

  it.live("does not truncate when under limit", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        for (let i = 0; i < 10; i++) {
          yield* Effect.promise(() => Bun.write(path.join(dir, `file${i}.txt`), `content${i}`))
        }
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(result.metadata.count).toBe(10)
        expect(result.metadata.truncated).toBe(false)
      }),
    ),
  )

  it.live("lists files in sorted order", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "zebra.txt"), "z"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "apple.txt"), "a"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "banana.txt"), "b"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        const lines = result.output.split("\n")
        const appleIndex = lines.findIndex((l) => l.includes("apple.txt"))
        const bananaIndex = lines.findIndex((l) => l.includes("banana.txt"))
        const zebraIndex = lines.findIndex((l) => l.includes("zebra.txt"))
        expect(appleIndex).toBeLessThan(bananaIndex)
        expect(bananaIndex).toBeLessThan(zebraIndex)
      }),
    ),
  )

  it.live("renders directory structure with proper indentation", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "root.txt"), "root"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "subdir", "nested.txt"), "nested"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 2 }, ctx)
        const lines = result.output.split("\n")
        const rootLine = lines.find((l) => l.includes("root.txt"))
        const subdirLine = lines.find((l) => l.includes("subdir/"))
        const nestedLine = lines.find((l) => l.includes("nested.txt"))
        expect(rootLine).toMatch(/^  root\.txt$/)
        expect(subdirLine).toMatch(/^  subdir\/$/)
        expect(nestedLine).toMatch(/^    nested\.txt$/)
      }),
    ),
  )

  it.live("lists subdirectories before files when depth allows", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "afile.txt"), "a"))
        yield* Effect.promise(() => Bun.write(path.join(dir, "adir", "nested.txt"), "nested"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        const lines = result.output.split("\n")
        const adirIndex = lines.findIndex((l) => l.includes("adir/"))
        const afileIndex = lines.findIndex((l) => l.includes("afile.txt"))
        expect(adirIndex).toBeLessThan(afileIndex)
      }),
    ),
  )

  it.live("uses absolute path when provided", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "test.txt"), "content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ path: dir, depth: 1 }, ctx)
        expect(result.output).toContain(dir)
        expect(result.output).toContain("test.txt")
      }),
    ),
  )

  it.live("uses relative path to project directory", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "test.txt"), "content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 1 }, ctx)
        expect(["", "."]).toContain(result.title)
      }),
    ),
  )

  it.live("lists multiple levels of nested directories", () =>
    provideTmpdirInstance((dir) =>
      Effect.gen(function* () {
        yield* Effect.promise(() => Bun.write(path.join(dir, "level1", "level2", "level3", "file.txt"), "content"))
        const info = yield* ListTool
        const list = yield* info.init()
        const result = yield* list.execute({ depth: 3 }, ctx)
        expect(result.output).toContain("level1/")
        expect(result.output).toContain("level2/")
        expect(result.output).toContain("level3/")
        expect(result.output).not.toContain("file.txt")
      }),
    ),
  )
})
