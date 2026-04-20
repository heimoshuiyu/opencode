import type { ProjectConfig } from "./config"

type GitLabNote = {
  id: number
  type: string
  body: string
  attachment: string | null
  author: {
    id: number
    username: string
    name: string
  }
  created_at: string
  updated_at: string
  system: boolean
  noteable_id: number
  noteable_type: string
  resolvable: boolean
}

type GitLabMRChange = {
  old_path: string
  new_path: string
  diff: string
  new_file: boolean
  renamed_file: boolean
  deleted_file: boolean
}

type GitLabMR = {
  id: number
  iid: number
  title: string
  description: string
  state: string
  source_branch: string
  target_branch: string
  author: {
    id: number
    username: string
    name: string
  }
  created_at: string
  updated_at: string
  merge_status: string
  additions: number
  deletions: number
  changes: GitLabMRChange[]
}

type GitLabProjectMember = {
  id: number
  username: string
  name: string
  access_level: number
}

const ACCESS_LEVEL_WRITE = 30

export class GitLabClient {
  private config: ProjectConfig
  private baseUrl: string

  constructor(config: ProjectConfig) {
    this.config = config
    this.baseUrl = config.gitlabUrl.replace(/\/$/, "")
  }

  private async request<T>(path: string, options?: RequestInit): Promise<T> {
    const url = `${this.baseUrl}/api/v4${path}`
    const res = await fetch(url, {
      ...options,
      headers: {
        "PRIVATE-TOKEN": this.config.gitlabToken,
        "Content-Type": "application/json",
        ...options?.headers,
      },
    })
    if (!res.ok) {
      const body = await res.text()
      throw new Error(`GitLab API error ${res.status} ${url}: ${body}`)
    }
    return res.json() as Promise<T>
  }

  async getMR(projectId: number, mrIid: number): Promise<GitLabMR> {
    return this.request<GitLabMR>(`/projects/${projectId}/merge_requests/${mrIid}`)
  }

  async getMRChanges(projectId: number, mrIid: number): Promise<GitLabMR> {
    return this.request<GitLabMR>(`/projects/${projectId}/merge_requests/${mrIid}/changes`)
  }

  async getMRNotes(projectId: number, mrIid: number): Promise<GitLabNote[]> {
    return this.request<GitLabNote[]>(
      `/projects/${projectId}/merge_requests/${mrIid}/notes?sort=asc&per_page=100`,
    )
  }

  async createMRNote(projectId: number, mrIid: number, body: string): Promise<GitLabNote> {
    return this.request<GitLabNote>(`/projects/${projectId}/merge_requests/${mrIid}/notes`, {
      method: "POST",
      body: JSON.stringify({ body }),
    })
  }

  async getProjectMember(projectId: number, userId: number): Promise<GitLabProjectMember | null> {
    try {
      return await this.request<GitLabProjectMember>(
        `/projects/${projectId}/members/${userId}`,
      )
    } catch {
      return null
    }
  }

  async hasWriteAccess(projectId: number, userId: number): Promise<boolean> {
    const member = await this.getProjectMember(projectId, userId)
    if (!member) return false
    return member.access_level >= ACCESS_LEVEL_WRITE
  }

  getCloneUrl(projectPath: string): string {
    return `${this.baseUrl}/${projectPath}.git`
  }

  get projectPath(): string {
    return this.baseUrl
  }
}
