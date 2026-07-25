export * as HistoryTool from "./history"

import type { Context as PluginContext } from "@opencode/plugin/effect/plugin"
import { ToolFailure } from "@opencode/ai"
import { Effect, Schema } from "effect"
import { Database } from "../database/database"

// ── Constants ───────────────────────────────────────────────────────────────

const TIME_RE = /^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2}(?::\d{2})?))?$/

const searchName = "history_search"
const viewName = "history_view"

// ── Schemas ─────────────────────────────────────────────────────────────────

const SearchInput = Schema.Struct({
  keyword: Schema.Array(Schema.String).annotate({
    description: "搜索关键词列表，每个关键词使用 SQL LIKE 匹配（%keyword%）",
  }),
  match_mode: Schema.optional(Schema.Literals(["AND", "OR"])).annotate({
    description: "多关键词匹配模式：AND=所有关键词都必须匹配，OR=任一关键词匹配即可",
  }),
  role: Schema.optional(Schema.Literals(["all", "user", "assistant"])).annotate({
    description: "过滤角色：all=全部，user=仅用户消息，assistant=仅助手消息",
  }),
  limit: Schema.optional(Schema.Number).annotate({
    description: "最多返回条数，默认 20",
  }),
  offset: Schema.optional(Schema.Number).annotate({
    description: "跳过前 N 条结果，用于分页，默认 0",
  }),
  session: Schema.optional(Schema.String).annotate({
    description: "限定在某个 session ID 内搜索，空字符串=不限",
  }),
  directory: Schema.optional(Schema.String).annotate({
    description:
      "限定工作区范围。空字符串=默认按当前项目搜索（同一 git 仓库的所有 worktree 都会被包含）；'%'=搜索所有项目；也可传具体目录路径进行模糊匹配",
  }),
  full: Schema.optional(Schema.Boolean).annotate({
    description: "是否输出完整文本（默认只输出关键词附近片段）",
  }),
  context_lines: Schema.optional(Schema.Number).annotate({
    description: "非 full 模式下，关键词命中行前后各展示多少行，默认 3",
  }),
  time_from: Schema.optional(Schema.String).annotate({
    description:
      "起始时间（本地时间），格式：'YYYY-MM-DD'、'YYYY-MM-DD HH:MM' 或 'YYYY-MM-DD HH:MM:SS'。留空=不限制",
  }),
  time_to: Schema.optional(Schema.String).annotate({
    description: "结束时间（本地时间），格式同上。留空=不限制。例如 '2026-05-23' 表示到当天 23:59:59",
  }),
})

const ViewInput = Schema.Struct({
  session_id: Schema.String.annotate({
    description: "Session ID（如 ses_xxxxx）",
  }),
  last: Schema.optional(Schema.Number).annotate({
    description: "只看最后 N 轮对话，0=查看全部",
  }),
})

const StringOutput = Schema.String

// ── Helpers ─────────────────────────────────────────────────────────────────

function parseTime(input: string | undefined | null, boundary: "from" | "to"): number | null {
  if (!input || !input.trim()) return null
  const s = input.trim()
  if (/^\d+$/.test(s)) return Number(s)

  const m = s.match(TIME_RE)
  if (!m) return null

  const datePart = m[1]
  const timePart = m[2]

  if (timePart) {
    const hasSeconds = timePart.includes(":", timePart.indexOf(":") + 1)
    if (hasSeconds) return new Date(`${datePart}T${timePart}`).getTime()
    if (boundary === "from") return new Date(`${datePart}T${timePart}:00`).getTime()
    return new Date(`${datePart}T${timePart}:59`).getTime() + 999
  }
  if (boundary === "from") return new Date(`${datePart}T00:00:00`).getTime()
  return new Date(`${datePart}T23:59:59`).getTime() + 999
}

function fmtTime(ms: number): string {
  return new Date(ms)
    .toLocaleString("sv-SE", { timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone })
    .replace("T", " ")
}

function makeSnippet(text: string, keywords: string[], contextLines: number): string {
  const lines = text.split("\n")
  const kwLows = keywords.map((k) => k.toLowerCase())
  const hitLines = lines
    .map((ln, i) => {
      const lnLow = ln.toLowerCase()
      return kwLows.some((kw) => lnLow.includes(kw)) ? i : -1
    })
    .filter((i) => i >= 0)

  if (hitLines.length === 0) {
    return lines.slice(0, contextLines).join("\n") + (lines.length > contextLines ? "\n..." : "")
  }

  const half = contextLines
  const regions: [number, number][] = []
  for (const hl of hitLines) {
    const lo = Math.max(0, hl - half)
    const hi = Math.min(lines.length, hl + half + 1)
    if (regions.length > 0 && lo <= regions[regions.length - 1][1]) {
      regions[regions.length - 1][1] = hi
    } else {
      regions.push([lo, hi])
    }
  }

  const parts = regions.map(([lo, hi]) => lines.slice(lo, hi).join("\n"))
  const result = parts.join("\n  ...\n")
  const pre = regions[0][0] > 0 ? "..." : ""
  const suf = regions[regions.length - 1][1] < lines.length ? "..." : ""
  return `${pre}\n${result}\n${suf}`.trim()
}

// ── SQL Fragments ───────────────────────────────────────────────────────────

const SESSION_FILTER = `s.parent_id IS NULL`

const SEARCH_SQL_BASE = `
SELECT
    sess.id            AS session_id,
    sess.title         AS session_title,
    sub.id             AS message_id,
    sub.m_role         AS role,
    sub.p_text         AS text,
    sub.m_time         AS time_ms
FROM (
    SELECT
        sm.id, sm.session_id, 'user' AS m_role,
        json_extract(sm.data, '$.text') AS p_text,
        sm.time_created AS m_time
    FROM session_message sm
    JOIN session s ON s.id = sm.session_id
    WHERE sm.type = 'user'
      AND json_extract(sm.data, '$.text') IS NOT NULL
      AND ${SESSION_FILTER}

    UNION ALL

    SELECT
        sm.id, sm.session_id, 'assistant',
        (
            SELECT group_concat(json_extract(value, '$.text'), char(10))
            FROM json_each(json_extract(sm.data, '$.content'))
            WHERE json_extract(value, '$.type') = 'text'
        ),
        sm.time_created
    FROM session_message sm
    JOIN session s ON s.id = sm.session_id
    WHERE sm.type = 'assistant'
      AND json_extract(sm.data, '$.finish') = 'stop'
      AND EXISTS (
          SELECT 1 FROM json_each(json_extract(sm.data, '$.content'))
          WHERE json_extract(value, '$.type') = 'text'
      )
      AND ${SESSION_FILTER}
) AS sub
JOIN session sess ON sess.id = sub.session_id
WHERE {EXCLUDE_SESSION}
`

function buildLikeClause(keywords: string[], matchMode: "AND" | "OR"): string {
  const joiner = matchMode === "AND" ? " AND " : " OR "
  const likeClauses = keywords.map(() => `sub.p_text LIKE ?`).join(joiner)
  return keywords.length > 1 && matchMode === "OR" ? `(${likeClauses})` : likeClauses
}

function buildSearchSQL(
  keywords: string[],
  matchMode: "AND" | "OR",
  excludeSessionSQL: string,
  dirFilterSQL: string,
  timeSQL: string,
): string {
  return (
    SEARCH_SQL_BASE.replace("{EXCLUDE_SESSION}", excludeSessionSQL) +
    `  AND ${buildLikeClause(keywords, matchMode)}\n` +
    `  AND (? = 'all' OR sub.m_role = ?)` +
    `  AND (? = '' OR sub.session_id = ?)` +
    dirFilterSQL +
    timeSQL +
    `\nORDER BY sub.m_time DESC\nLIMIT ?\nOFFSET ?\n`
  )
}

function buildCountSQL(
  keywords: string[],
  matchMode: "AND" | "OR",
  excludeSessionSQL: string,
  dirFilterSQL: string,
  timeSQL: string,
): string {
  return (
    `SELECT COUNT(*) AS total FROM (\n` +
    SEARCH_SQL_BASE.replace("{EXCLUDE_SESSION}", excludeSessionSQL) +
    `  AND ${buildLikeClause(keywords, matchMode)}\n` +
    `  AND (? = 'all' OR sub.m_role = ?)` +
    `  AND (? = '' OR sub.session_id = ?)` +
    dirFilterSQL +
    timeSQL +
    `\n) AS cnt`
  )
}

const VIEW_SESSION_SQL = `
SELECT id, title, directory, time_created
FROM session s
WHERE s.id = ? AND ${SESSION_FILTER}
`

const VIEW_MESSAGES_SQL = `
SELECT
    sm.id             AS message_id,
    sm.type           AS role,
    sm.time_created   AS time_ms,
    CASE
        WHEN sm.type = 'user' THEN json_extract(sm.data, '$.text')
        ELSE (
            SELECT group_concat(json_extract(value, '$.text'), char(10))
            FROM json_each(json_extract(sm.data, '$.content'))
            WHERE json_extract(value, '$.type') = 'text'
        )
    END AS text
FROM session_message sm
JOIN session s ON s.id = sm.session_id
WHERE sm.session_id = ?
  AND ${SESSION_FILTER}
  AND sm.type IN ('user', 'assistant')
  AND (sm.type = 'user' OR json_extract(sm.data, '$.finish') = 'stop')
ORDER BY sm.seq ASC
`

// ── Search ──────────────────────────────────────────────────────────────────

type SearchRow = {
  session_id: string
  session_title: string
  message_id: string
  role: string
  text: string
  time_ms: number
}

type SqlParam = string | number | null

type UnsafeFn = <A extends object>(
  sql: string,
  params?: readonly unknown[],
) => Effect.Effect<ReadonlyArray<A>, unknown>

function doSearch(unsafe: UnsafeFn, args: any, sessionID: string): Effect.Effect<string, ToolFailure> {
  return Effect.gen(function* () {
    const keywords: string[] = Array.isArray(args.keyword) && args.keyword.length > 0 ? args.keyword : []
    const matchMode = args.match_mode === "OR" ? ("OR" as const) : ("AND" as const)
    const role = args.role ?? "all"
    const limit = Math.min(200, Math.max(1, Math.floor(args.limit ?? 20)))
    const offset = Math.min(10000, Math.max(0, Math.floor(args.offset ?? 0)))
    const session = args.session ?? ""
    const full = args.full ?? false
    const contextLines = args.context_lines ?? 3
    const inputDir = (args.directory && args.directory.trim()) || ""
    const timeFrom = parseTime(args.time_from, "from")
    const timeTo = parseTime(args.time_to, "to")

    if (keywords.length === 0) return "未提供任何搜索关键词"

    let timeFilterSQL = ""
    const timeFilterParams: SqlParam[] = []
    if (timeFrom !== null) {
      timeFilterSQL += " AND sub.m_time >= ?"
      timeFilterParams.push(timeFrom)
    }
    if (timeTo !== null) {
      timeFilterSQL += " AND sub.m_time <= ?"
      timeFilterParams.push(timeTo)
    }

    let dirFilterSQL = ""
    const dirFilterParams: SqlParam[] = []

    if (inputDir === "%") {
      // no filter
    } else if (inputDir === "") {
      const currentSession = (yield* unsafe<{ directory: string }>(
        "SELECT directory FROM session WHERE id = ? LIMIT 1",
        [sessionID],
      )) as readonly { directory: string }[]
      const currentDir = currentSession[0]?.directory ?? ""

      const lookupRows = (yield* unsafe<{ project_id: string }>(
        "SELECT DISTINCT project_id FROM session WHERE directory = ? LIMIT 1",
        [currentDir],
      )) as readonly { project_id: string }[]
      if (lookupRows.length > 0) {
        dirFilterSQL = " AND sess.project_id = ?"
        dirFilterParams.push(lookupRows[0].project_id)
      } else {
        dirFilterSQL = " AND sess.directory = ?"
        dirFilterParams.push(currentDir)
      }
    } else {
      dirFilterSQL = " AND sess.directory LIKE ?"
      dirFilterParams.push(inputDir)
    }

    const excludeCurrent = session !== sessionID
    const excludeSessionSQL = excludeCurrent ? "sess.id != ?" : "1=1"
    const excludeParams: SqlParam[] = excludeCurrent ? [sessionID] : []

    const searchSQL = buildSearchSQL(keywords, matchMode, excludeSessionSQL, dirFilterSQL, timeFilterSQL)
    const countSQL = buildCountSQL(keywords, matchMode, excludeSessionSQL, dirFilterSQL, timeFilterSQL)
    const kwDisplay = keywords.join(matchMode === "AND" ? " AND " : " OR ")

    const baseParams: SqlParam[] = [
      ...excludeParams,
      ...keywords.map((kw): SqlParam => `%${kw}%`),
      role,
      role,
      session,
      session,
      ...dirFilterParams,
      ...timeFilterParams,
    ]

    const rows = (yield* unsafe<SearchRow>(searchSQL, [...baseParams, limit, offset])) as readonly SearchRow[]

    if (rows.length === 0) {
      const countRows = (yield* unsafe<{ total: number }>(countSQL, baseParams)) as readonly { total: number }[]
      const total = countRows[0]?.total ?? 0
      if (total === 0) return `未找到包含「${kwDisplay}」的消息`
      return `未找到包含「${kwDisplay}」的消息（offset=${offset} 已超出范围，共 ${total} 条匹配）`
    }

    let total: number | null = null
    const needCount = offset > 0 || rows.length === limit
    if (needCount) {
      const countRows = (yield* unsafe<{ total: number }>(countSQL, baseParams)) as readonly { total: number }[]
      total = countRows[0]?.total ?? 0
    }

    const lines: string[] = []
    if (needCount && total !== null && (total > limit || offset > 0)) {
      lines.push(`共 ${total} 条匹配，当前显示第 ${offset + 1}–${offset + rows.length} 条`)
      lines.push("")
    }

    for (let i = 0; i < rows.length; i++) {
      const row = rows[i]
      const label = row.role === "user" ? "user" : "asst"
      const idx = String(i + 1).padStart(3, " ")
      lines.push(`${idx}. [${label}]  ${fmtTime(row.time_ms)}  ${row.session_title}`)
      lines.push(`     ${row.session_id}`)
      const content = full ? row.text : makeSnippet(row.text, keywords, contextLines)
      for (const ln of content.split("\n")) lines.push(`     ${ln}`)
      lines.push("")
    }

    return lines.join("\n")
  }).pipe(
    Effect.mapError(
      (error) =>
        new ToolFailure({
          message: `history_search query failed`,
          ...(error instanceof Error ? { error: { name: error.name, message: error.message } } : {}),
        }),
    ),
  )
}

type ViewRow = { message_id: string; role: string; time_ms: number; text: string | null }

function doView(unsafe: UnsafeFn, args: any): Effect.Effect<string, ToolFailure> {
  return Effect.gen(function* () {
    const infoRows = (yield* unsafe<{ id: string; title: string; directory: string; time_created: number }>(
      VIEW_SESSION_SQL,
      [args.session_id],
    )) as readonly { id: string; title: string; directory: string; time_created: number }[]
    const info = infoRows[0]

    if (!info) return `Session 不存在或已被排除: ${args.session_id}`

    const lines: string[] = []
    lines.push("============================================================")
    lines.push(`  ${info.title}`)
    lines.push(`  ${info.directory}`)
    lines.push(`  ${fmtTime(info.time_created)}   ${info.id}`)
    lines.push("============================================================")
    lines.push("")

    const messages = (yield* unsafe<ViewRow>(VIEW_MESSAGES_SQL, [args.session_id])) as readonly ViewRow[]
    const nonEmpty = messages.filter((m) => m.text?.trim())
    if (nonEmpty.length === 0) {
      lines.push("  (无符合条件的内容)")
      return lines.join("\n")
    }

    const selected = (args.last ?? 0) > 0 ? nonEmpty.slice(-(args.last ?? 0)) : nonEmpty
    const msgOffset = nonEmpty.length - selected.length

    const sep = "----------------------------------------------------------"
    for (let i = 0; i < selected.length; i++) {
      const msg = selected[i]
      const label = msg.role === "user" ? "user" : "asst"
      const idx = msgOffset + i + 1
      lines.push(sep)
      lines.push(`  [${idx}] ${label}  ${fmtTime(msg.time_ms)}`)
      lines.push("")
      for (const ln of msg.text!.split("\n")) lines.push(`      ${ln}`)
      lines.push("")
    }

    lines.push(sep)
    return lines.join("\n")
  }).pipe(
    Effect.mapError(
      (error) =>
        new ToolFailure({
          message: `history_view query failed`,
          ...(error instanceof Error ? { error: { name: error.name, message: error.message } } : {}),
        }),
    ),
  )
}

// ── Plugin ──────────────────────────────────────────────────────────────────

export const Plugin = {
  id: "opencode.tool.history",
  effect: Effect.fn("HistoryTool.Plugin")(function* (ctx: PluginContext) {
    const { db } = yield* Database.Service

    yield* ctx.tool
      .transform((draft) =>
        draft.add(
          ({
            name: searchName,
            options: { codemode: true },
            description: `搜索历史会话消息，用于回忆之前的讨论、决策和经验。

当用户提到之前做过的事情、讨论过的话题，或者你不确定的上下文时，
用这个工具从历史会话中检索相关信息。

默认行为：
- 自动排除当前会话（你所在的会话不会被搜索到）
- 默认搜索当前项目目录范围内的会话（同一 git 仓库的所有 worktree）
- 设置 directory="%" 可搜索所有项目的会话`,
            input: SearchInput,
            output: StringOutput,
            execute: (input, context) =>
              Effect.gen(function* () {
                const result = yield* doSearch(db.$client.unsafe, input, context.sessionID)
                return { output: result, content: result }
              }),
          }),
        ),
      )
      .pipe(Effect.orDie)

    yield* ctx.tool
      .transform((draft) =>
        draft.add(
          ({
            name: viewName,
            options: { codemode: true },
            description: `查看某个历史会话的完整对话内容。

当你需要了解某个会话的完整上下文时使用，例如查看之前的完整工作过程、
决策讨论、问题排查过程等。`,
            input: ViewInput,
            output: StringOutput,
            execute: (input) =>
              Effect.gen(function* () {
                const result = yield* doView(db.$client.unsafe, input)
                return { output: result, content: result }
              }),
          }),
        ),
      )
      .pipe(Effect.orDie)
  }),
}
