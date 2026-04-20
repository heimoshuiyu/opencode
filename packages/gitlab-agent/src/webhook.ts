import type { Config, ProjectConfig } from "./config"

export type MRTask = {
  projectSlug: string
  projectId: number
  projectPath: string
  mrIid: number
  sourceBranch: string
  targetBranch: string
  userId: number
  username: string
  noteId: number
  userPrompt: string
  projectConfig: ProjectConfig
}

type NoteWebhookPayload = {
  object_kind: string
  event_type: string
  user: {
    id: number
    username: string
    name: string
  }
  project_id: number
  project: {
    id: number
    path_with_namespace: string
  }
  object_attributes: {
    id: number
    note: string
    noteable_type: string
    action: string
    noteable_id: number
  }
  merge_request: {
    id: number
    iid: number
    title: string
    source_branch: string
    target_branch: string
    state: string
  } | null
}

function extractUserPrompt(noteBody: string, mention: string): string | null {
  const lower = noteBody.toLowerCase()
  const mentionLower = mention.toLowerCase()
  if (!lower.includes(mentionLower)) return null

  const trimmed = noteBody.trim()
  if (lower.trim() === mentionLower) {
    return "Review this merge request and provide feedback on code quality, bugs, and improvements."
  }
  return trimmed
}

export type ParseResult =
  | { ok: true; task: MRTask }
  | { ok: false; error: string; status: number }
  | { ok: true; skipped: string }

export function parseWebhook(
  body: NoteWebhookPayload,
  projectSlug: string,
  token: string | undefined,
  config: Config,
): ParseResult {
  const decodedSlug = decodeURIComponent(projectSlug)
  const projectConfig = config.projects[decodedSlug]

  if (!projectConfig) {
    return { ok: false, error: "Unknown project", status: 404 }
  }

  if (token !== projectConfig.webhookSecret) {
    return { ok: false, error: "Invalid token", status: 401 }
  }

  if (body.object_kind !== "note") {
    return { ok: true, skipped: "not a note event" }
  }

  if (body.object_attributes.noteable_type !== "MergeRequest") {
    return { ok: true, skipped: "not an MR comment" }
  }

  if (body.object_attributes.action !== "create") {
    return { ok: true, skipped: "not a new comment" }
  }

  if (!body.merge_request) {
    return { ok: true, skipped: "no merge request in payload" }
  }

  const userPrompt = extractUserPrompt(body.object_attributes.note, config.mention)
  if (!userPrompt) {
    return { ok: true, skipped: "mention not found" }
  }

  const task: MRTask = {
    projectSlug: decodedSlug,
    projectId: body.project_id,
    projectPath: body.project.path_with_namespace,
    mrIid: body.merge_request.iid,
    sourceBranch: body.merge_request.source_branch,
    targetBranch: body.merge_request.target_branch,
    userId: body.user.id,
    username: body.user.username,
    noteId: body.object_attributes.id,
    userPrompt,
    projectConfig,
  }

  return { ok: true, task }
}
