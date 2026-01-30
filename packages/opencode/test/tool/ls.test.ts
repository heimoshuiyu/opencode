import { describe, expect, test } from "bun:test"
import path from "path"
import { ListTool } from "../../src/tool/ls"
import { Instance } from "../../src/project/instance"
import { tmpdir } from "../fixture/fixture"
import { SessionID, MessageID } from "../../src/session/schema"

const ctx = {
  sessionID: SessionID.make("test"),
  messageID: MessageID.make("test"),
  callID: "",
  agent: "build",
  abort: AbortSignal.any([]),
  messages: [],
  metadata: () => {},
  ask: async () => {},
}

describe("tool.ls", () => {
  test("lists files in directory", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "file1.txt"), "content1")
        await Bun.write(path.join(dir, "file2.txt"), "content2")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("file1.txt")
        expect(result.output).toContain("file2.txt")
      },
    })
  })

  test("handles empty directory", async () => {
    await using tmp = await tmpdir()
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain(tmp.path)
        expect(result.metadata.count).toBe(0)
      },
    })
  })

  test("depth 0 lists only root files", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "root.txt"), "root content")
        await Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content")
        await Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 0 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).not.toContain("subdir/")
        expect(result.output).not.toContain("nested.txt")
        expect(result.output).not.toContain("deep.txt")
      },
    })
  })

  test("depth 1 lists root + 1 level subdirectories", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "root.txt"), "root content")
        await Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content")
        await Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).not.toContain("nested.txt")
        expect(result.output).not.toContain("deep/")
      },
    })
  })

  test("depth 2 lists deeper nested files", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "root.txt"), "root content")
        await Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content")
        await Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 2 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).toContain("nested.txt")
        expect(result.output).toContain("deep/")
        expect(result.output).not.toContain("deep.txt")
      },
    })
  })

  test("depth 3 lists all nested files", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "root.txt"), "root content")
        await Bun.write(path.join(dir, "subdir", "nested.txt"), "nested content")
        await Bun.write(path.join(dir, "subdir", "deep", "deep.txt"), "deep content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 3 }, ctx)
        expect(result.output).toContain("root.txt")
        expect(result.output).toContain("subdir/")
        expect(result.output).toContain("nested.txt")
        expect(result.output).toContain("deep/")
        expect(result.output).toContain("deep.txt")
      },
    })
  })

  test("respects ignore patterns", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "keep.txt"), "keep")
        await Bun.write(path.join(dir, "ignore.txt"), "ignore")
        await Bun.write(path.join(dir, "node_modules", "module.js"), "module")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ ignore: ["ignore.txt"], depth: 1 }, ctx)
        expect(result.output).toContain("keep.txt")
        expect(result.output).not.toContain("ignore.txt")
        expect(result.output).not.toContain("node_modules/")
      },
    })
  })

  test("ignores default ignore patterns", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "keep.txt"), "keep")
        await Bun.write(path.join(dir, "node_modules", "module.js"), "module")
        await Bun.write(path.join(dir, "dist", "output.js"), "output")
        await Bun.write(path.join(dir, ".git", "config"), "config")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.output).toContain("keep.txt")
        expect(result.output).not.toContain("node_modules/")
        expect(result.output).not.toContain("dist/")
        expect(result.output).not.toContain(".git/")
      },
    })
  })

  test("limits file count to 100", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        for (let i = 0; i < 150; i++) {
          await Bun.write(path.join(dir, `file${i}.txt`), `content${i}`)
        }
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.metadata.count).toBe(100)
        expect(result.metadata.truncated).toBe(true)
      },
    })
  })

  test("does not truncate when under limit", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        for (let i = 0; i < 10; i++) {
          await Bun.write(path.join(dir, `file${i}.txt`), `content${i}`)
        }
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        expect(result.metadata.count).toBe(10)
        expect(result.metadata.truncated).toBe(false)
      },
    })
  })

  test("lists files in sorted order", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "zebra.txt"), "z")
        await Bun.write(path.join(dir, "apple.txt"), "a")
        await Bun.write(path.join(dir, "banana.txt"), "b")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        const lines = result.output.split("\n")
        const appleIndex = lines.findIndex((l) => l.includes("apple.txt"))
        const bananaIndex = lines.findIndex((l) => l.includes("banana.txt"))
        const zebraIndex = lines.findIndex((l) => l.includes("zebra.txt"))
        expect(appleIndex).toBeLessThan(bananaIndex)
        expect(bananaIndex).toBeLessThan(zebraIndex)
      },
    })
  })

  test("renders directory structure with proper indentation", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "root.txt"), "root")
        await Bun.write(path.join(dir, "subdir", "nested.txt"), "nested")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 2 }, ctx)
        const lines = result.output.split("\n")
        const rootLine = lines.find((l) => l.includes("root.txt"))
        const subdirLine = lines.find((l) => l.includes("subdir/"))
        const nestedLine = lines.find((l) => l.includes("nested.txt"))
        expect(rootLine).toMatch(/^  root\.txt$/)
        expect(subdirLine).toMatch(/^  subdir\/$/)
        expect(nestedLine).toMatch(/^    nested\.txt$/)
      },
    })
  })

  test("lists subdirectories before files when depth allows", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "afile.txt"), "a")
        await Bun.write(path.join(dir, "adir", "nested.txt"), "nested")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        const lines = result.output.split("\n")
        const adirIndex = lines.findIndex((l) => l.includes("adir/"))
        const afileIndex = lines.findIndex((l) => l.includes("afile.txt"))
        expect(adirIndex).toBeLessThan(afileIndex)
      },
    })
  })

  test("uses absolute path when provided", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "test.txt"), "content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ path: tmp.path, depth: 1 }, ctx)
        expect(result.output).toContain(tmp.path)
        expect(result.output).toContain("test.txt")
      },
    })
  })

  test("uses relative path to project directory", async () => {
    await using tmp = await tmpdir({
      git: true,
      init: async (dir) => {
        await Bun.write(path.join(dir, "test.txt"), "content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 1 }, ctx)
        // When path equals worktree, relative returns "." or "" depending on path implementation
        expect(["", "."]).toContain(result.title)
      },
    })
  })

  test("lists multiple levels of nested directories", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "level1", "level2", "level3", "file.txt"), "content")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const list = await ListTool.init()
        const result = await list.execute({ depth: 3 }, ctx)
        expect(result.output).toContain("level1/")
        expect(result.output).toContain("level2/")
        expect(result.output).toContain("level3/")
        expect(result.output).not.toContain("file.txt")
      },
    })
  })
})
