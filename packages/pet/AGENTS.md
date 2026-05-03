# OpenCode Pet - 桌面宠物

OpenCode 的桌面宠物伴侣，根据编码活动实时改变状态和动画，并通过 AI 评论气泡实时互动。

## 开发

```bash
cd packages/pet
bun install
bun run dev
```

## 构建

```bash
bun run build
bun run package          # 当前平台
bun run package:mac
bun run package:win
bun run package:linux
```

## 架构

```
src/
├── main/          # Electron 主进程
│   └── index.ts   # 透明窗口 + 系统托盘 + SSE 连接 + Pet Observer
├── preload/       # Preload 脚本 (IPC bridge)
│   ├── index.ts   # contextBridge 暴露 window.pet API
│   └── types.ts   # IPC 类型定义 + PetState (17 种状态)
└── renderer/      # 渲染进程 (WebView)
    ├── index.html # 透明背景 + 聊天输入框 + 设置面板
    ├── main.ts    # 入口：冷却队列 + 渲染器 + 设置面板 + 聊天交互
    ├── types.ts   # PetState 类型定义
    ├── pet/
    │   ├── motion-map.ts  # 17 状态 → 动作映射表
    │   └── renderer.ts    # Live2D 渲染器 (pixi.js v6 + pixi-live2d-display)
    ├── opencode/          # (预留)
    └── ui/                # (预留)
```

## 与 OpenCode 通信

通过 SSE 连接 OpenCode Server (`/global/event`)，监听 20+ 种事件驱动宠物状态（17 种）。

### Activity 状态（持久，循环动画）

| 事件 | 状态 |
|---|---|
| `session.status` = busy | 🤔 thinking |
| `session.status` = idle | 😺 idle |
| `session.status` = retry | 🔄 retrying |
| `message.part.delta` | 💬 generating |
| `message.part.updated` (tool, running) | 🔧 acting |
| `permission.asked` / `question.asked` | 👀 waiting-user |
| `permission.replied` / `question.replied` | 🤔 thinking |
| 30s 无活动 | 😴 sleeping |
| SSE 断开 | 📡 disconnected |

### Reaction 状态（瞬态，超时回退到当前 Activity）

| 事件 | 状态 | 持续 |
|---|---|---|
| SSE 连接成功 | 👋 greeting | 2s |
| 用户直接对话（聊天输入框） | 💬 chatting | 30s |
| `file.edited` / `worktree.ready` | 📝 file-written | 2s |
| `todo.updated` (全部完成) / `workspace.ready` | 🎉 completed | 4s |
| `installation.updated` | 🆙 upgraded | 3s |
| `session.error` / `workspace.failed` / `worktree.failed` / `mcp.browser.open.failed` | ❌ error | 3s |
| `pty.exited` | 💻 pty-exited | 2.5s |
| `session.compacted` | 📦 compacted | 2.5s |
| `vcs.branch.updated` / `mcp.tools.changed` / `project.updated` / `installation.update-available` / `pty.created` | 🌐 env-changed | 2s |

## Pet Observer（AI 评论气泡）

宠物使用独立的 OpenCode workspace + `deepseek/deepseek-v4-flash` 模型生成评论：
- 每个 skin 有独立 workspace（`~/.config/pet/workspace-{skin}/`），含 `persona.md` 定义人格
- 用户消息/助手回复 → 注入 SYSTEM-REMINDER → prompt_async → AI 回复 → 气泡显示
- 点击宠物 → 聊天输入框 → 直接和宠物对话
- 超长回复（>50 字符）自动缩短

## 皮肤系统

```
skins/
├── lib/
│   └── live2dcubismcore.min.js   # Cubism 4 Core 运行时
├── miku/                         # PJSK 初音未来（默认模型）
│   └── persona.md                # AI 人格定义
├── bongo-cat/                    # Bongo Cat Live2D 猫咪
│   └── persona.md
└── hiyori/                       # Hiyori 日向（官方示例）
    └── persona.md
```

每个 Live2D 皮肤目录包含 `*.model3.json` 入口文件、相关资源、以及可选的 `persona.md`。

## 约定

- 渲染进程只通过 `window.pet` API 与主进程通信
- 主进程 IPC 注册集中在 `src/main/index.ts` 的 `registerIpcHandlers()` 中
- 新增宠物状态需同步修改 7 个文件：`preload/types.ts` → `renderer/types.ts` → `main/index.ts` → `pet/motion-map.ts` → `pet/renderer.ts` → `main.ts` → `index.html`
- 配置通过 electron-store 持久化（非环境变量），设置面板 GUI 可直接编辑
- 使用 `model.internalModel.motionManager.motionGroups` 获取动作列表（不是 `model.settings.motions`）
- 不要使用 `-webkit-app-region: drag`（会吞掉右键事件），用 JS + IPC 实现拖拽
- `model.width` 返回 `scale.x × localBounds.width`，始终使用 `originalModelWidth`/`originalModelHeight`
- 冷却队列：Activity 间 500ms，Reaction 间 3500ms，期间新状态写入 `pendingState`（后写入覆盖）
