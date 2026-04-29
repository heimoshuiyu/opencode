import * as vscode from "vscode";
import { type ChildProcess, spawn } from "child_process";

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/** Active opencode server processes keyed by cwd */
const servers = new Map<string, { process: ChildProcess; port: number }>();

/** Tracked webview panels keyed by cwd */
const panels = new Map<string, vscode.WebviewPanel>();

// ---------------------------------------------------------------------------
// Activate / Deactivate
// ---------------------------------------------------------------------------

export function deactivate() {
  for (const [, entry] of servers) {
    entry.process.kill();
  }
  servers.clear();
  panels.clear();
}

export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand("opencode.openWebview", () => openWebview(context)),
    vscode.commands.registerCommand("opencode.openNewWebview", () => openWebview(context, { forceNew: true })),
    vscode.commands.registerCommand("opencode.addFilepathToWebview", () => addFilepathToWebview()),
  );
}

// ---------------------------------------------------------------------------
// Server lifecycle
// ---------------------------------------------------------------------------

/** Start (or reuse) an opencode server for the given cwd and return its port. */
async function ensureServer(cwd: string): Promise<number> {
  const existing = servers.get(cwd);
  if (existing && existing.process.exitCode === null) {return existing.port;}

  const port = Math.floor(Math.random() * (65535 - 16384 + 1)) + 16384;

  let spawnError: Error | undefined;
  let stderr = "";

  const { OPENCODE_SERVER_PASSWORD, ...envWithoutPassword } = process.env;
  const proc = spawn("opencode", ["serve", "--port", String(port)], {
    cwd,
    env: {
      ...envWithoutPassword,
      OPENCODE_CALLER: "vscode",
    },
    stdio: "pipe",
    detached: false,
  });

  proc.on("error", (err) => { spawnError = err; });
  proc.stderr?.on("data", (data: Buffer) => { stderr += data.toString(); });

  proc.on("exit", (code, signal) => {
    servers.delete(cwd);
    if (code !== 0 && code !== null) {
      const detail = stderr.trim() || `exit code ${code}, signal ${signal}`;
      vscode.window.showErrorMessage(`opencode exited: ${detail}`);
    }
  });

  await waitForServer(port, () => spawnError);

  servers.set(cwd, { process: proc, port });
  return port;
}

/** Poll until the server responds. */
async function waitForServer(port: number, getSpawnError: () => Error | undefined, maxTries = 15) {
  for (let i = 0; i < maxTries; i++) {
    const err = getSpawnError();
    if (err) {throw new Error(`Failed to start opencode: ${err.message}`);}
    await new Promise((r) => setTimeout(r, 200));
    try {
      const res = await fetch(`http://localhost:${port}/global/health`);
      if (res.ok) {return;}
    } catch {}
  }
  const err = getSpawnError();
  if (err) {throw new Error(`Failed to start opencode: ${err.message}`);}
  throw new Error(`opencode server did not start on port ${port}`);
}

// ---------------------------------------------------------------------------
// Webview
// ---------------------------------------------------------------------------

async function openWebview(context: vscode.ExtensionContext, opts?: { forceNew?: boolean }) {
  const cwd = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!cwd) {
    vscode.window.showErrorMessage("Open a workspace folder first.");
    return;
  }

  const port = await ensureServer(cwd);

  // Reuse existing panel for this workspace unless forceNew
  if (!opts?.forceNew) {
    const existing = panels.get(cwd);
    if (existing) {
      existing.reveal(vscode.ViewColumn.Beside);
      return;
    }
  }

  const panel = vscode.window.createWebviewPanel("opencode", "opencode", vscode.ViewColumn.Beside, {
    enableScripts: true,
    retainContextWhenHidden: true,
    localResourceRoots: [],
  });

  panel.iconPath = {
    light: vscode.Uri.file(context.asAbsolutePath("images/button-dark.svg")),
    dark: vscode.Uri.file(context.asAbsolutePath("images/button-light.svg")),
  };

  panel.onDidDispose(() => panels.delete(cwd));

  panels.set(cwd, panel);
  panel.webview.html = getWebviewHtml(port);
}

function getWebviewHtml(port: number): string {
  const url = `http://localhost:${port}`;
  return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta
    http-equiv="Content-Security-Policy"
    content="
      default-src http://localhost:${port} ws://localhost:${port} http://127.0.0.1:${port} ws://127.0.0.1:${port} data: blob: 'unsafe-inline' 'unsafe-eval';
      style-src http://localhost:${port} 'unsafe-inline';
      script-src http://localhost:${port} 'unsafe-inline' 'unsafe-eval';
      img-src http://localhost:${port} data: https:;
      font-src http://localhost:${port} data:;
      connect-src http://localhost:${port} ws://localhost:${port} http://127.0.0.1:${port} ws://127.0.0.1:${port} data:;
      media-src http://localhost:${port} data:;
    "
  />
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body, iframe { width: 100%; height: 100vh; border: none; overflow: hidden; }
  </style>
</head>
<body>
  <iframe src="${url}" allow="clipboard-read; clipboard-write" />
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// File-reference injection
// ---------------------------------------------------------------------------

async function addFilepathToWebview() {
  const fileRef = getActiveFile();
  if (!fileRef) {return;}

  const cwd = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  const entry = cwd ? servers.get(cwd) : undefined;
  if (!entry) {
    vscode.window.showWarningMessage("No running opencode server for this workspace.");
    return;
  }

  await fetch(`http://localhost:${entry.port}/tui/append-prompt`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text: fileRef }),
  });
}

function getActiveFile() {
  const editor = vscode.window.activeTextEditor;
  if (!editor) {return;}

  const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
  if (!folder) {return;}

  const relativePath = vscode.workspace.asRelativePath(editor.document.uri);
  let ref = `@${relativePath}`;

  const sel = editor.selection;
  if (!sel.isEmpty) {
    const start = sel.start.line + 1;
    const end = sel.end.line + 1;
    ref += start === end ? `#L${start}` : `#L${start}-${end}`;
  }

  return ref;
}
