import fs from "fs/promises"
import path from "path"
import { describe, expect } from "bun:test"
import { parse } from "jsonc-parser"
import { ConfigWriter } from "@opencode/core/config/writer"
import { Info } from "@opencode/schema/config"
import { Global } from "@opencode/util/global"
import { LayerNode } from "@opencode/util/effect/layer-node"
import { Effect, Schema } from "effect"
import { tempGlobalLayer } from "../fixture/global"
import { testEffect } from "../lib/effect"

const decode = Schema.decodeUnknownSync(Info)

// Global joins the group so tests can read the temp config directory back;
// the replacement keeps it the same instance the writer depends on.
const it = testEffect(
  LayerNode.compile(LayerNode.group([ConfigWriter.node, Global.node]), {
    replacements: [Global.node.replace(tempGlobalLayer)],
  }),
)

const seed = (config: string, name: string, content: string) =>
  Effect.promise(() =>
    fs.mkdir(config, { recursive: true }).then(() => fs.writeFile(path.join(config, name), content)),
  )

const read = (config: string, name: string) => Effect.promise(() => fs.readFile(path.join(config, name), "utf8"))

// Written files may legitimately contain comments, so assertions parse JSONC, not JSON.
const parseObject = (text: string) => parse(text, undefined, { allowTrailingComma: true })

describe("ConfigWriter", () => {
  it.effect("creates the global config file with the patch when absent", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      yield* writer.merge(
        decode({
          providers: {
            myprovider: {
              package: "aisdk:@ai-sdk/openai-compatible",
              settings: { baseURL: "https://api.myprovider.com/v1" },
            },
          },
        }),
      )
      const written = yield* read(global.config, "opencode.json")
      expect(parseObject(written)).toEqual({
        providers: {
          myprovider: {
            package: "aisdk:@ai-sdk/openai-compatible",
            settings: { baseURL: "https://api.myprovider.com/v1" },
          },
        },
      })
      expect(written.endsWith("\n")).toBe(true)
    }),
  )

  it.effect("merges in place, preserving unrelated keys and their formatting", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      const original = [
        "{",
        '    "username": "existing",',
        '    "instructions": [',
        '        "./AGENTS.md"',
        "    ],",
        '    "providers": {',
        '        "other": { "package": "aisdk:other" }',
        "    }",
        "}",
      ].join("\n")
      yield* seed(global.config, "opencode.json", original)
      yield* writer.merge(
        decode({
          username: "replacement",
          providers: {
            myprovider: { package: "aisdk:@ai-sdk/openai-compatible" },
          },
        }),
      )
      const written = yield* read(global.config, "opencode.json")
      expect(parseObject(written)).toEqual({
        username: "replacement",
        instructions: ["./AGENTS.md"],
        providers: {
          other: { package: "aisdk:other" },
          myprovider: { package: "aisdk:@ai-sdk/openai-compatible" },
        },
      })
      // The four-space document is edited surgically, not reformatted: the untouched
      // array keeps its eight-space item indentation a two-space rewrite would lose.
      expect(written).toContain('        "./AGENTS.md"')
      expect(written).toContain('    "username": "replacement",')
    }),
  )

  it.effect("keeps comments when editing an existing jsonc document", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      yield* seed(
        global.config,
        "opencode.jsonc",
        [
          "{",
          "  // provisioning note",
          '  "username": "existing", // trailing note',
          '  "shell": "/bin/sh"',
          "}",
        ].join("\n"),
      )
      yield* writer.merge(
        decode({
          shell: "/bin/zsh",
          providers: {
            myprovider: {
              package: "aisdk:@ai-sdk/openai-compatible",
              settings: { baseURL: "https://api.myprovider.com/v1" },
            },
          },
        }),
      )
      const written = yield* read(global.config, "opencode.jsonc")
      expect(written).toContain("// provisioning note")
      expect(written).toContain('"username": "existing", // trailing note')
      expect(parseObject(written)).toEqual({
        username: "existing",
        shell: "/bin/zsh",
        providers: {
          myprovider: {
            package: "aisdk:@ai-sdk/openai-compatible",
            settings: { baseURL: "https://api.myprovider.com/v1" },
          },
        },
      })
      // Still one document: no second config file is created.
      expect(yield* Effect.promise(() => fs.readdir(global.config))).toEqual(["opencode.jsonc"])
    }),
  )

  it.effect("treats blank and comment-only documents as a fresh start", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      yield* seed(global.config, "opencode.json", "")
      yield* writer.merge(decode({ shell: "/bin/bash" }))
      expect(parseObject(yield* read(global.config, "opencode.json"))).toEqual({ shell: "/bin/bash" })
      yield* seed(global.config, "opencode.json", "// migrated notes\n")
      yield* writer.merge(decode({ shell: "/bin/zsh" }))
      expect(parseObject(yield* read(global.config, "opencode.json"))).toEqual({ shell: "/bin/zsh" })
    }),
  )

  it.effect("leaves the file untouched when the patch carries no instructions", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      const original = '{\n  "username": "existing"\n}\n'
      yield* seed(global.config, "opencode.json", original)
      yield* writer.merge(decode({ providers: {} }))
      expect(yield* read(global.config, "opencode.json")).toBe(original)
    }),
  )

  it.effect("writes into the shadowing file when both config files exist", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      yield* seed(global.config, "opencode.json", '{\n  "shell": "/bin/bash"\n}\n')
      yield* seed(global.config, "opencode.jsonc", '{\n  "username": "existing"\n}\n')
      yield* writer.merge(decode({ shell: "/bin/zsh" }))
      // Config.latest resolves shared keys with findLast, so jsonc shadows json;
      // the merge must land in jsonc or it would never take effect.
      expect(parseObject(yield* read(global.config, "opencode.jsonc"))).toEqual({
        username: "existing",
        shell: "/bin/zsh",
      })
      expect(parseObject(yield* read(global.config, "opencode.json"))).toEqual({ shell: "/bin/bash" })
    }),
  )

  it.effect("rejects a malformed global config document", () =>
    Effect.gen(function* () {
      const global = yield* Global.Service
      const writer = yield* ConfigWriter.Service
      yield* seed(global.config, "opencode.json", "{ not json")
      const error = yield* Effect.flip(writer.merge(decode({ shell: "/bin/zsh" })))
      expect(error).toBeInstanceOf(ConfigWriter.WriteError)
      expect(error.message).toContain("not a valid JSON document")
    }),
  )
})
