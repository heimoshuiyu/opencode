export * as ConfigInstructionPlugin from "./instruction.js"

import { define } from "@opencode/plugin/effect/plugin"
import type { Entry } from "@opencode/schema/config"
import { FSUtil } from "@opencode/util/fs-util"
import { Global } from "@opencode/util/global"
import { sameDirectory } from "@opencode/util/path"
import { basename, dirname, isAbsolute, join, resolve } from "path"
import { Effect, PubSub, Schedule, Scope, Semaphore, Stream } from "effect"
import { HttpClient, HttpClientRequest } from "effect/unstable/http"
import { Config } from "../../config.js"
import { Watcher } from "../../filesystem/watcher.js"
import { InstructionDiscovery } from "../../instruction-discovery.js"
import { Instructions } from "../../instructions/index.js"
import { Location } from "../../location.js"
import { AbsolutePath } from "../../schema.js"

type Loaded =
  | { readonly type: "available"; readonly files: InstructionDiscovery.File[] }
  | { readonly type: "unavailable" }

/** One config document's `instructions` items bound to that document's directory. */
type Declared = {
  readonly directory: string
  readonly items: readonly string[]
}

const globIgnore = ["node_modules", ".git", "**/{node_modules,.git}/**"]

export const Plugin = define({
  id: "opencode.config.instruction",
  effect: Effect.fn(function* (ctx) {
    const discovery = yield* InstructionDiscovery.Service
    const config = yield* Config.Service
    // Nothing this plugin watches or loads can contribute when both ambient
    // scopes are disabled and no config document declares instructions; skip
    // the resolves, the watcher fiber, and the transform. Config-declared
    // instructions alone keep the plugin active.
    if (!discovery.project && !discovery.global) {
      const hasDeclared = (yield* config.entries()).some(
        (entry) => entry.type === "document" && entry.info.instructions?.some((item) => item.length > 0),
      )
      if (!hasDeclared) return
    }
    yield* Effect.gen(function* () {
      const fs = yield* FSUtil.Service
      const global = yield* Global.Service
      const location = yield* Location.Service
      const watcher = yield* Watcher.Service
      const http = (yield* HttpClient.HttpClient).pipe(
        HttpClient.retryTransient({
          retryOn: "errors-and-responses",
          times: 2,
          schedule: Schedule.exponential(200).pipe(Schedule.jittered),
        }),
        HttpClient.filterStatusOk,
      )
      const changes = yield* PubSub.sliding<string>(1)
      const lock = Semaphore.makeUnsafe(1)
      // Configured watch fibers are forked into the activation scope because
      // refresh also runs from the config-change callback, where no ambient
      // Scope is available.
      const scope = yield* Scope.Scope
      // Configured watches start on first sighting and are never torn down
      // individually: a stale watch after a config edit costs one deduped fs
      // handle and a no-op rescan, and every watch dies with the activation
      // scope. The AGENTS.md ancestor candidates below keep their one-shot
      // subscriptions because that set cannot change while this location is
      // mounted.
      const watched = new Set<string>()
      // URL bodies are fetched when configuration loads, so watcher-driven
      // rescans of local sources never re-request remote ones. Failed fetches
      // are not cached; the next config change retries them.
      const urls = new Map<string, InstructionDiscovery.File>()
      const loaded: { entries: Entry[]; current: Loaded } = {
        entries: [],
        current: { type: "available", files: [] },
      }

      const publish = (update: Watcher.Update) => PubSub.publish(changes, update.path).pipe(Effect.asVoid)

      const declared = (entries: readonly Entry[]): Declared[] =>
        entries.flatMap((entry): Declared[] => {
          const items = entry.type === "document" ? entry.info.instructions : undefined
          if (!items?.length) return []
          return [
            {
              directory: entry.path ? dirname(entry.path) : location.directory,
              items: items.filter((item) => item.length > 0),
            },
          ]
        })

      const observe = Effect.fn("ConfigInstructionPlugin.observe")(function* (input: Watcher.WatchInput) {
        const key = JSON.stringify(input)
        if (watched.has(key)) return
        watched.add(key)
        const updates = yield* watcher.subscribe(input).pipe(
          Effect.catchCause((cause) =>
            Effect.logWarning("configured instruction watch failed", { input, cause }).pipe(Effect.as(Stream.empty)),
          ),
        )
        yield* updates.pipe(
          Stream.runForEach(publish),
          Effect.forkIn(scope, { startImmediately: true }),
        )
      })

      const start = yield* fs.resolve(location.directory)
      const root = yield* fs.resolve(location.project.directory)
      const home = yield* fs.resolve(global.home)
      const project = discovery.project && FSUtil.contains(root, start)
      const stop = FSUtil.contains(home, start) ? home : root
      const globalFile = yield* fs.resolve(join(global.config, "AGENTS.md"))

      // The ancestor walk can reach the global file when the location sits
      // beneath the global config dir; global: false excludes it there too.
      const candidates = [
        ...(discovery.global ? [globalFile] : []),
        ...(project
          ? ancestorDirectories(start, stop)
              .map((directory) => join(directory, "AGENTS.md"))
              .filter((file) => discovery.global || file !== globalFile)
          : []),
      ]
      for (const path of new Set(candidates)) {
        const updates = yield* watcher.subscribe({ path, type: "file" })
        yield* updates.pipe(Stream.runForEach(publish), Effect.forkScoped({ startImmediately: true }))
      }

      const read = Effect.fn("ConfigInstructionPlugin.read")(function* (path: string) {
        const content = yield* fs.readFileStringSafe(path)
        if (content !== undefined) return new InstructionDiscovery.File({ path: AbsolutePath.make(path), content })
        yield* Effect.logDebug("instruction file skipped", { path, reason: "unavailable" })
      })

      const globalSource = Effect.fn("ConfigInstructionPlugin.globalSource")(function* () {
        if (!discovery.global || !(yield* fs.isFile(globalFile))) return []
        const file = yield* read(globalFile)
        return file ? [file] : []
      })

      const projectSource = Effect.fn("ConfigInstructionPlugin.projectSource")(function* () {
        if (!project) return []
        const walked = yield* Effect.forEach(
          yield* fs.up({ targets: ["AGENTS.md"], start, stop, type: "file" }),
          fs.resolve,
        )
        const discovered = new Set(walked.filter((file) => discovery.global || file !== globalFile))
        const files = yield* Effect.forEach(discovered, read, { concurrency: "unbounded" })
        if (files.some((file) => file === undefined)) return Instructions.unavailable
        return files.filter((file): file is InstructionDiscovery.File => file !== undefined)
      })

      const pullUrl = Effect.fn("ConfigInstructionPlugin.pullUrl")(function* (url: string) {
        const content = yield* HttpClientRequest.get(url).pipe(
          http.execute,
          Effect.flatMap((response) => response.text),
          Effect.timeout("10 seconds"),
          Effect.catch((error) =>
            Effect.logWarning("failed to fetch instructions url", { url, error }).pipe(Effect.as(undefined)),
          ),
        )
        if (content === undefined) return
        urls.set(url, new InstructionDiscovery.File({ path: AbsolutePath.make(url), content }))
      })

      const pullUrls = Effect.fn("ConfigInstructionPlugin.pullUrls")(function* () {
        const wanted = new Set(
          declared(loaded.entries).flatMap((group) => group.items.filter(isUrl).map((item) => new URL(item).href)),
        )
        yield* Effect.forEach(wanted, pullUrl, { concurrency: "unbounded" })
        for (const url of urls.keys()) if (!wanted.has(url)) urls.delete(url)
      })

      // A declared single path is one file: an explicit absence is a skipped
      // declaration, while a present-but-unreadable file keeps the last known
      // instructions like the ambient sources do.
      const pathSource = Effect.fn("ConfigInstructionPlugin.pathSource")(function* (group: Declared, item: string) {
        const target = expand(global.home, group.directory, item)
        if (yield* fs.isDir(dirname(target))) {
          yield* observe({ path: dirname(target), type: "entries", names: [basename(target)] })
        }
        if (yield* fs.isDir(target)) {
          yield* Effect.logWarning("declared instruction path is a directory", { target })
          return []
        }
        const content = yield* fs.readFileStringSafe(target)
        if (content !== undefined) return [new InstructionDiscovery.File({ path: AbsolutePath.make(target), content })]
        if (yield* fs.existsSafe(target)) return Instructions.unavailable
        yield* Effect.logWarning("declared instruction file missing", { target })
        return []
      })

      // Glob bases are watched recursively so matches added or removed later are
      // rescanned; matched files that fail to read count as transient failures.
      const globSource = Effect.fn("ConfigInstructionPlugin.globSource")(function* (group: Declared, item: string) {
        const pattern = expand(global.home, group.directory, item)
        const base = globBase(pattern)
        if (!(yield* fs.isDir(base))) {
          yield* Effect.logWarning("declared instruction glob has no base directory", { item, base })
          return []
        }
        yield* observe({ path: base, type: "directory", ignore: globIgnore })
        const matches = yield* fs
          .scan(pattern, { absolute: true, include: "file" })
          .pipe(Effect.orElseSucceed(() => [] as string[]))
        const files: InstructionDiscovery.File[] = []
        for (const match of matches.toSorted()) {
          const content = yield* fs.readFileStringSafe(match)
          if (content === undefined) return Instructions.unavailable
          files.push(new InstructionDiscovery.File({ path: AbsolutePath.make(match), content }))
        }
        return files
      })

      const configuredSource = Effect.fn("ConfigInstructionPlugin.configuredSource")(function* () {
        const files: InstructionDiscovery.File[] = []
        for (const group of declared(loaded.entries)) {
          for (const item of group.items) {
            if (isUrl(item)) {
              const file = urls.get(new URL(item).href)
              if (file) files.push(file)
              continue
            }
            const source = yield* (isGlob(item) ? globSource(group, item) : pathSource(group, item))
            if (!Array.isArray(source)) return source
            files.push(...source)
          }
        }
        return files
      })

      const isolate = <A, E, R>(source: string, effect: Effect.Effect<A, E, R>) =>
        effect.pipe(
          Effect.catchCause((cause) =>
            Effect.logWarning("failed to load instruction source", { source, cause }).pipe(
              Effect.as(Instructions.unavailable),
            ),
          ),
        )

      const refresh = Effect.fn("ConfigInstructionPlugin.refresh")(
        function* (file?: string) {
          const sources = yield* Effect.all({
            global: isolate("global", globalSource()),
            project: isolate("project", projectSource()),
            configured: isolate("instructions", configuredSource()),
          })
          loaded.current =
            Array.isArray(sources.global) && Array.isArray(sources.project) && Array.isArray(sources.configured)
              ? { type: "available", files: [...sources.global, ...sources.project, ...sources.configured] }
              : { type: "unavailable" }
          if (!file) return
          yield* Effect.logDebug("instructions rescanned", {
            file,
            instructions:
              loaded.current.type === "available" ? loaded.current.files.map((item) => item.path) : "unavailable",
          })
        },
        (effect, ..._args: [file?: string]) => lock.withPermit(effect),
      )

      yield* ctx.event.subscribe().pipe(
        Stream.filter((event) => event.type === "config.updated"),
        Stream.runForEach(() =>
          config.entries().pipe(
            Effect.tap((entries) => Effect.sync(() => (loaded.entries = entries))),
            Effect.andThen(pullUrls()),
            Effect.andThen(refresh()),
            Effect.andThen(discovery.reload()),
          ),
        ),
        Effect.forkScoped({ startImmediately: true }),
      )
      // Close the race between the first read and establishing the subscription.
      loaded.entries = yield* config.entries()

      // Editor saves arrive as bursts of watcher events; settle before rescanning once. Subscribe
      // before debouncing so no update slips through while the debounce starts its pull.
      const updates = yield* PubSub.subscribe(changes)
      yield* Stream.fromSubscription(updates).pipe(
        Stream.debounce("100 millis"),
        Stream.runForEach((file) => refresh(file).pipe(Effect.andThen(discovery.reload()))),
        Effect.forkScoped({ startImmediately: true }),
      )
      yield* pullUrls()
      yield* refresh()
      yield* discovery.transform((editor) => {
        if (loaded.current.type === "unavailable") {
          editor.unavailable()
          return
        }
        for (const file of loaded.current.files) editor.add(file)
      })
    }).pipe(
      Effect.catchCause((cause) =>
        Effect.logWarning("failed to activate instruction source", { cause }).pipe(
          Effect.andThen(discovery.transform((editor) => editor.unavailable())),
          Effect.asVoid,
        ),
      ),
    )
  }),
})

function isUrl(item: string) {
  return URL.canParse(item) && /^(https?:)$/.test(new URL(item).protocol)
}

function isGlob(item: string) {
  return /[*?[{]/.test(item)
}

function expand(home: string, directory: string, item: string) {
  const expanded = item.startsWith("~/") ? join(home, item.slice(2)) : item
  return isAbsolute(expanded) ? expanded : resolve(directory, expanded)
}

/** The deepest literal directory a glob resolves under, so watches cover later matches. */
function globBase(pattern: string) {
  const metachar = /[*?[{]/.exec(pattern)
  if (!metachar) return dirname(pattern)
  const prefix = pattern.slice(0, metachar.index)
  const cut = Math.max(prefix.lastIndexOf("/"), prefix.lastIndexOf("\\"))
  return cut === -1 ? dirname(pattern) : cut === 0 ? pattern.slice(0, 1) : pattern.slice(0, cut)
}

// `start` keeps the client's spelling while `stop` may come from git, so a Windows drive
// letter can differ only in case (`c:\repo` vs `C:\repo`). Compare the way FSUtil.contains
// admitted `start` beneath `stop`, or the walk passes `stop` and recurses at the drive root.
function ancestorDirectories(start: string, stop: string): string[] {
  if (sameDirectory(start, stop)) return [start]
  return [start, ...ancestorDirectories(dirname(start), stop)]
}
