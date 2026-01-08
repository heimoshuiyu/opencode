import { describe, expect, test } from "bun:test"
import path from "path"
import { BashTool } from "../../src/tool/bash"
import { Instance } from "../../src/project/instance"
import { tmpdir } from "../fixture/fixture"
import type { PermissionNext } from "../../src/permission/next"
import { Truncate } from "../../src/tool/truncation"

const ctx = {
  sessionID: "test",
  messageID: "",
  callID: "",
  agent: "build",
  abort: AbortSignal.any([]),
  metadata: () => {},
  ask: async () => {},
}

const projectRoot = path.join(__dirname, "../..")

describe("tool.bash", () => {
  test("basic", async () => {
    await Instance.provide({
      directory: projectRoot,
      fn: async () => {
        const bash = await BashTool.init()
        const result = await bash.execute(
          {
            command: "echo 'test'",
            description: "Echo test message",
            background: false,
          },
          ctx,
        )
        expect(result.metadata.exit).toBe(0)
        expect(result.metadata.output).toContain("test")
      },
    })
  })
})

describe("tool.bash permissions", () => {
  test("asks for bash permission with correct pattern", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }
        await bash.execute(
          {
            command: "echo hello",
            description: "Echo hello",
            background: false,
          },
          testCtx,
        )
        expect(requests.length).toBe(1)
        expect(requests[0].permission).toBe("bash")
        expect(requests[0].patterns).toContain("echo hello")
      },
    })
  })

  test("asks for bash permission with multiple commands", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        await expect(
          bash.execute(
            {
              command: "curl https://example.com",
              description: "Fetch URL",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })

  test("denies all commands with wildcard deny", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "*": "deny",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        await expect(
          bash.execute(
            {
              command: "ls",
              description: "List files",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })

  test("more specific pattern overrides general pattern", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "*": "deny",
                "ls *": "allow",
                "pwd*": "allow",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // ls should be allowed
        const result = await bash.execute(
          {
            command: "ls -la",
            description: "List files",
            background: false,
          },
          ctx,
        )
        expect(result.metadata.exit).toBe(0)

        // pwd should be allowed
        const pwd = await bash.execute(
          {
            command: "pwd",
            description: "Print working directory",
            background: false,
          },
          ctx,
        )
        expect(pwd.metadata.exit).toBe(0)

        // cat should be denied
        await expect(
          bash.execute(
            {
              command: "cat /etc/passwd",
              description: "Read file",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })

  test("denies dangerous subcommands while allowing safe ones", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "find *": "allow",
                "find * -delete*": "deny",
                "find * -exec*": "deny",
                "*": "deny",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // Basic find should work
        const result = await bash.execute(
          {
            command: "find . -name '*.ts'",
            description: "Find typescript files",
            background: false,
          },
          ctx,
        )
        expect(result.metadata.exit).toBe(0)

        // find -delete should be denied
        await expect(
          bash.execute(
            {
              command: "find . -name '*.tmp' -delete",
              description: "Delete temp files",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")

        // find -exec should be denied
        await expect(
          bash.execute(
            {
              command: "find . -name '*.ts' -exec cat {} \\;",
              description: "Find and cat files",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })

  test("allows git read commands while denying writes", async () => {
    await using tmp = await tmpdir({
      git: true,
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "git status*": "allow",
                "git log*": "allow",
                "git diff*": "allow",
                "git branch": "allow",
                "git commit *": "deny",
                "git push *": "deny",
                "*": "deny",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // git status should work
        const status = await bash.execute(
          {
            command: "git status",
            description: "Git status",
            background: false,
          },
          ctx,
        )
        expect(status.metadata.exit).toBe(0)

        // git log should work
        const log = await bash.execute(
          {
            command: "git log --oneline -5",
            description: "Git log",
            background: false,
          },
          ctx,
        )
        expect(log.metadata.exit).toBe(0)

        // git commit should be denied
        await expect(
          bash.execute(
            {
              command: "git commit -m 'test'",
              description: "Git commit",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")

        // git push should be denied
        await expect(
          bash.execute(
            {
              command: "git push origin main",
              description: "Git push",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })

  test("denies external directory access when permission is deny", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              external_directory: "deny",
              bash: {
                "*": "allow",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // Should deny cd to parent directory (cd is checked for external paths)
        await expect(
          bash.execute(
            {
              command: "cd ../",
              description: "Change to parent directory",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow()
      },
    })
  })

  test("denies workdir outside project when external_directory is deny", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              external_directory: "deny",
              bash: {
                "*": "allow",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        await expect(
          bash.execute(
            {
              command: "ls",
              workdir: "/tmp",
              description: "List /tmp",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow()
      },
    })
  })

  test("handles multiple commands in sequence", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "echo *": "allow",
                "curl *": "deny",
                "*": "deny",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // echo && echo should work
        const result = await bash.execute(
          {
            command: "echo foo && echo bar",
            description: "Echo twice",
            background: false,
          },
          ctx,
        )
        expect(result.metadata.exit).toBe(0)
      },
    })
  })

  test("asks for external_directory permission when cd to parent", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }
        await bash.execute(
          {
            command: "cd ../",
            description: "Change to parent directory",
          },
          testCtx,
        )
        const extDirReq = requests.find((r) => r.permission === "external_directory")
        expect(extDirReq).toBeDefined()
      },
    })
  })

  test("asks for external_directory permission when workdir is outside project", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }
        await bash.execute(
          {
            command: "ls",
            workdir: "/tmp",
            description: "List /tmp",
          },
          testCtx,
        )
        const extDirReq = requests.find((r) => r.permission === "external_directory")
        expect(extDirReq).toBeDefined()
        expect(extDirReq!.patterns).toContain("/tmp")
      },
    })
  })

  test("does not ask for external_directory permission when rm inside project", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }

        await Bun.write(path.join(tmp.path, "tmpfile"), "x")

        await bash.execute(
          {
            command: "rm tmpfile",
            description: "Remove tmpfile",
          },
          testCtx,
        )

        const extDirReq = requests.find((r) => r.permission === "external_directory")
        expect(extDirReq).toBeUndefined()
      },
    })
  })

  test("includes always patterns for auto-approval", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }
        await bash.execute(
          {
            command: "git log --oneline -5",
            description: "Git log",
          },
          testCtx,
        )
        expect(requests.length).toBe(1)
        expect(requests[0].always.length).toBeGreaterThan(0)
        expect(requests[0].always.some((p) => p.endsWith("*"))).toBe(true)
      },
    })
  })

  test("does not ask for bash permission when command is cd only", async () => {
    await using tmp = await tmpdir({ git: true })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        const requests: Array<Omit<PermissionNext.Request, "id" | "sessionID" | "tool">> = []
        const testCtx = {
          ...ctx,
          ask: async (req: Omit<PermissionNext.Request, "id" | "sessionID" | "tool">) => {
            requests.push(req)
          },
        }
        await bash.execute(
          {
            command: "cd .",
            description: "Stay in current directory",
          },
          testCtx,
        )
        const bashReq = requests.find((r) => r.permission === "bash")
        expect(bashReq).toBeUndefined()
      },
    })
  })

  test("denies curl command when echo && curl is used", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(
          path.join(dir, "opencode.json"),
          JSON.stringify({
            permission: {
              bash: {
                "echo *": "allow",
                "curl *": "deny",
                "*": "deny",
              },
            },
          }),
        )
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const bash = await BashTool.init()
        // echo && curl should fail (curl is denied)
        await expect(
          bash.execute(
            {
              command: "echo hi && curl https://example.com",
              description: "Echo then curl",
              background: false,
            },
            ctx,
          ),
        ).rejects.toThrow("restricted")
      },
    })
  })
})

describe("tool.bash truncation", () => {
  test("truncates output exceeding line limit", async () => {
    await Instance.provide({
      directory: projectRoot,
      fn: async () => {
        const bash = await BashTool.init()
        const lineCount = Truncate.MAX_LINES + 500
        const result = await bash.execute(
          {
            command: `seq 1 ${lineCount}`,
            description: "Generate lines exceeding limit",
          },
          ctx,
        )
        expect((result.metadata as any).truncated).toBe(true)
        expect(result.output).toContain("truncated")
        expect(result.output).toContain("The tool call succeeded but the output was truncated")
      },
    })
  })

  test("truncates output exceeding byte limit", async () => {
    await Instance.provide({
      directory: projectRoot,
      fn: async () => {
        const bash = await BashTool.init()
        const byteCount = Truncate.MAX_BYTES + 10000
        const result = await bash.execute(
          {
            command: `head -c ${byteCount} /dev/zero | tr '\\0' 'a'`,
            description: "Generate bytes exceeding limit",
          },
          ctx,
        )
        expect((result.metadata as any).truncated).toBe(true)
        expect(result.output).toContain("truncated")
        expect(result.output).toContain("The tool call succeeded but the output was truncated")
      },
    })
  })

  test("does not truncate small output", async () => {
    await Instance.provide({
      directory: projectRoot,
      fn: async () => {
        const bash = await BashTool.init()
        const result = await bash.execute(
          {
            command: "echo hello",
            description: "Echo hello",
          },
          ctx,
        )
        expect((result.metadata as any).truncated).toBe(false)
        expect(result.output).toBe("hello\n")
      },
    })
  })

  test("full output is saved to file when truncated", async () => {
    await Instance.provide({
      directory: projectRoot,
      fn: async () => {
        const bash = await BashTool.init()
        const lineCount = Truncate.MAX_LINES + 100
        const result = await bash.execute(
          {
            command: `seq 1 ${lineCount}`,
            description: "Generate lines for file check",
          },
          ctx,
        )
        expect((result.metadata as any).truncated).toBe(true)

        const filepath = (result.metadata as any).outputPath
        expect(filepath).toBeTruthy()

        const saved = await Bun.file(filepath).text()
        const lines = saved.trim().split("\n")
        expect(lines.length).toBe(lineCount)
        expect(lines[0]).toBe("1")
        expect(lines[lineCount - 1]).toBe(String(lineCount))
      },
    })
  })
})
