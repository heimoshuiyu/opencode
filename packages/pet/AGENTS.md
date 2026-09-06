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

通过 SSE 连接 OpenCode v2 Server（`GET /api/event`，扁平事件信封 `{id, created, type, data, location?}`），监听 20+ 种事件驱动宠物状态（17 种）。

- 认证：HTTP Basic，用户名固定 `opencode`，密码为服务凭据；设置面板留空 URL 时自动读取服务注册文件 `~/.local/state/opencode/service.json`（`{url, password}`），回退端口 49374
- 心跳是 SSE 注释行（`: heartbeat`），不是数据事件
- 会话 API：`GET/POST /api/session`（list 响应为 `{data, cursor}` 包装）、`POST /api/session/:id/prompt`（body `{text}`，天然异步，agent 在建会话时绑定）
- 释放配置缓存：`DELETE /api/debug/location?location[directory]=...`

### Activity 状态（持久，循环动画）

| 事件 | 状态 |
|---|---|
| `session.status` = busy | 🤔 thinking |
| `session.status` = idle | 😺 idle |
| `session.status` = retry | 🔄 retrying |
| `session.text.delta` / `session.reasoning.delta` / `session.tool.input.delta` | 💬 generating |
| `session.tool.called` | 🔧 acting |
| `permission.asked` / `form.created` | 👀 waiting-user |
| `permission.replied` / `form.replied` / `form.cancelled` | 🤔 thinking |
| 30s 无活动 | 😴 sleeping |
| SSE 断开 | 📡 disconnected |

### Reaction 状态（瞬态，超时回退到当前 Activity）

| 事件 | 状态 | 持续 |
|---|---|---|
| SSE 连接成功 | 👋 greeting | 2s |
| 用户直接对话（聊天输入框） | 💬 chatting | 30s |
| `filesystem.changed` | 📝 file-written | 2s |
| `installation.updated` | 🆙 upgraded | 3s |
| `session.execution.failed` / `session.compaction.failed` | ❌ error | 3s |
| `pty.exited` | 💻 pty-exited | 2.5s |
| `session.compaction.ended` | 📦 compacted | 2.5s |
| `vcs.branch.updated` / `mcp.status.changed` / `project.updated` / `installation.update-available` / `pty.created` / `worktree.resolved` | 🌐 env-changed | 2s |

注意：v1 的 `todo.updated`（completed 反应）、`question.*`、`workspace.*`、`worktree.ready/failed` 在 v2 公共事件流上不存在；`completed` 状态目前无触发事件，保留在状态机中。子会话通过 `session.created` 的 `parentID` 识别。

## Pet Observer（AI 评论气泡）

宠物使用独立的 OpenCode workspace 生成评论。**模型不在代码里固定**：观察者会话创建时不带 model（`session.model` 为空），运行时跟随 location 默认模型（全局 config `model` 或内置默认）；用户可在会话里手动切换模型（TUI 模型选择器 / `POST /api/session/:id/model`），切换持久化在该会话上。想给宠物工作区设一个便宜的默认模型，可在 `~/.config/pet/workspace-{skin}/.opencode/opencode.json` 里加 `"model": "..."`（属于用户配置，仍可被会话级切换覆盖）：
- 每个 skin 有独立 workspace（`~/.config/pet/workspace-{skin}/`），含 `persona.md` 定义人格
- `.opencode/agents/pet-commenter.md`：agent 定义（纯人格 prompt，无 frontmatter——模型跟随会话默认）
- `.opencode/opencode.json`：v2 ruleset 语法全量拒绝 `{"permissions": [{"action": "*", "resource": "*", "effect": "deny"}]}`
- 用户消息（`session.inbox.enqueued` 的 item payload）/助手回复（idle 时冲刷 `session.text.ended` 累积文本）→ 注入 SYSTEM-REMINDER → `POST /api/session/:id/prompt` → 宠物回复捕获自 `session.text.ended` → 气泡显示
- 注入无客户端门限（无冷却/busy 判断）：v2 服务端 inbox 持久排队，运行中按入队顺序在安全步边界织入（steer），空闲立即执行，消息不丢不乱序
- 点击宠物 → 聊天输入框 → 直接和宠物对话
- 超长回复（>50 字符）立即注入缩短请求，由服务端排在当前回复之后

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
- `pet://` 协议必须同时声明 `corsEnabled: true` 特权并在响应里带 `Access-Control-Allow-Origin: *`：dev 模式下渲染进程来自 vite 源（http://localhost:5173），Electron 42 起跨源 fetch 自定义 scheme 两者缺一不可
- monorepo 里 electron-vite 从其自身依赖解析 electron 二进制（bun 隔离布局下可能解析到根目录 hoist 的 42.x 而非本包声明的 ^35）；bun install 不跑 electron 的 postinstall，`dist/electron` 缺失时需在对应 `.bun/electron@*/node_modules/electron` 目录手动执行 `bun install.js`
- 主进程 IPC 注册集中在 `src/main/index.ts` 的 `registerIpcHandlers()` 中
- 新增宠物状态需同步修改 7 个文件：`preload/types.ts` → `renderer/types.ts` → `main/index.ts` → `pet/motion-map.ts` → `pet/renderer.ts` → `main.ts` → `index.html`
- 配置通过 electron-store 持久化（非环境变量），设置面板 GUI 可直接编辑
- 使用 `model.internalModel.motionManager.motionGroups` 获取动作列表（不是 `model.settings.motions`）
- 不要使用 `-webkit-app-region: drag`（会吞掉右键事件），用 JS + IPC 实现拖拽
- `model.width` 返回 `scale.x × localBounds.width`，始终使用 `originalModelWidth`/`originalModelHeight`
- 冷却队列：Activity 间 500ms，Reaction 间 3500ms，期间新状态写入 `pendingState`（后写入覆盖）
