export * as ConfigWriter from "./writer.js"

import path from "path"
import { applyEdits, type JSONPath, modify, type ParseError, parse, stripComments } from "jsonc-parser"
import { Context, Effect, Layer, Schema } from "effect"
import { Info } from "@opencode/schema/config"
import { makeGlobalNode } from "@opencode/util/effect/app-node"
import { FSUtil } from "@opencode/util/fs-util"
import { Global } from "@opencode/util/global"
import { ConfigDiscovery } from "./discovery.js"

export class WriteError extends Schema.TaggedError<WriteError>()("Config.WriteError", {
  message: Schema.String,
}) {}

export interface Interface {
  /**
   * Deep-merges a partial configuration document into the user's global config
   * file, creating it when absent. Objects merge recursively while arrays and
   * scalars replace. Patches are applied as in-place jsonc-parser edits, so
   * comments, indentation, and key order outside the patched paths survive.
   */
  readonly merge: (patch: Info) => Effect.Effect<void, WriteError>
}

export class Service extends Context.Service<Service, Interface>()("@opencode/ConfigWriter") {}

const encode = Schema.encodeSync(Info)

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value)

// Flatten the patch into leaf assignments, matching recursive merge semantics: nested
// objects descend into the existing value, everything else replaces, and an empty
// object carries no instructions at all.
const leaves = (patch: Record<string, unknown>, prefix: JSONPath = []): Array<[JSONPath, unknown]> =>
  Object.entries(patch).flatMap(([key, value]) => {
    if (value === undefined) return []
    const jsonPath = [...prefix, key]
    if (!isRecord(value)) return [[jsonPath, value] as [JSONPath, unknown]]
    const nested = leaves(value, jsonPath)
    return nested.length > 0 ? nested : []
  })

// Inserted and rewritten nodes copy the document's own indentation; two spaces otherwise.
const formattingOf = (text: string) => {
  const indent = /\r?\n([ \t]+)/.exec(text)?.[1] ?? ""
  return indent.includes("\t")
    ? { insertSpaces: false, tabSize: 2 }
    : { insertSpaces: true, tabSize: indent.length > 0 ? indent.length : 2 }
}

// Returns the patched text, or undefined when the document is malformed. Blank and
// comment-only files carry no data to lose and start fresh (in-place edits need an
// object to anchor to); any other unparsable document is rejected, not overwritten.
const editInto = (text: string, patch: Record<string, unknown>): string | undefined => {
  const fresh = `${JSON.stringify(patch, null, 2)}\n`
  const errors: ParseError[] = []
  const parsed = parse(text, errors, { allowTrailingComma: true }) as unknown
  if (errors.length > 0) return stripComments(text).trim().length === 0 ? fresh : undefined
  if (!isRecord(parsed)) return fresh
  let edited = text
  for (const [jsonPath, value] of leaves(patch)) {
    edited = applyEdits(edited, modify(edited, jsonPath, value, { formattingOptions: formattingOf(edited) }))
  }
  return edited
}

const layer = Layer.effect(
  Service,
  Effect.gen(function* () {
    const fs = yield* FSUtil.Service
    const global = yield* Global.Service

    return Service.of({
      merge: Effect.fn("ConfigWriter.merge")(function* (patch) {
        const existing = yield* Effect.forEach(ConfigDiscovery.names, (name) =>
          fs.existsSafe(path.join(global.config, name)).pipe(Effect.map((found) => ({ name, found }))),
        )
        // Config.latest resolves shared keys with findLast over these names, so a
        // later file shadows an earlier one: write into the shadowing file or the
        // merge would be invisible at load time.
        const target = (existing.findLast((item) => item.found) ?? existing[0]).name
        const filepath = path.join(global.config, target)

        const text = yield* fs.readFileStringSafe(filepath).pipe(
          Effect.mapError((error) => new WriteError({ message: `failed to read global config: ${error.message}` })),
        )
        const document = encode(patch)
        const content =
          text === undefined ? `${JSON.stringify(document, null, 2)}\n` : editInto(text, document)
        if (content === undefined)
          return yield* new WriteError({ message: `global config is not a valid JSON document: ${filepath}` })
        return yield* fs.writeWithDirs(filepath, content).pipe(
          Effect.mapError((error) => new WriteError({ message: `failed to write global config: ${error.message}` })),
        )
      }),
    })
  }),
)

export const node = makeGlobalNode({ service: Service, layer, deps: [FSUtil.node, Global.node] })
