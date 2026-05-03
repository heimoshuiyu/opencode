# OpenCode Pet — 桌面宠物项目计划

## 0. 开发备忘

### ⚠️ Wayland always-on-top 限制

在 Linux Wayland 桌面环境下，`alwaysOnTop` 可能不生效。这是 Wayland 协议的设计决定，不是 Electron bug。XWayland 下通常可以工作。可能的 workaround：
- 添加 `--ozone-platform=x11` 强制 XWayland 模式（需要测试是否影响透明窗口）
- 让用户在合成器层面配置窗口规则（如 Hyprland 的 `windowrule = pin`）

### 初音未来模型信息

- **来源**：`wasd090900to/live2Dpet_Miku` GitHub repo，PJSK（世界计划）模型
- **位置**：`skins/miku/21miku_normal_3.0_f_t03.model3.json`
- **内容**：1MB moc3 + 2MB 贴图 + 412 个动作文件（脸部表情 + 全身动画，含多种服装变体）
- **无表情组**（Expressions），只有动作组（Motions）
- **pet:// URL**：`pet://miku/21miku_normal_3.0_f_t03.model3.json`（注意 hostname=miku）

### CubismCore 加载架构

```
skins/lib/live2dcubismcore.min.js   ← 从 npm 包 live2dcubismcore 复制出来的纯 Core 文件
                                     ← 渲染进程通过 fetch("pet://lib/...") + (0,eval)() 加载
                                     ← 设置 window.Live2DCubismCore
                                     ← 然后 import pixi.js + pixi-live2d-display/cubism4
```

不要用 `import "live2dcubismcore"` — 那个 npm 包把 pixi-live2d-display 也打包进去了，会和我们的 pixi.js v6 冲突。

### pixi-live2d-display API 备忘

- `model.internalModel.motionManager.motionGroups` — ✅ 正确方式获取动作组
- `model.motion(group, index?, priority?)` — priority: 1=IDLE, 2=NORMAL(默认), 3=FORCE
- `Live2DModel.from(url)` — 返回完全初始化的 model（含 motionManager）
- `model.settings` — `Object.keys()` 返回空（属性在原型链上），不可用于获取 motions

## 1. 项目概述

OpenCode Pet 是一个基于 Electron 的桌面宠物应用，以透明窗口形式悬浮在桌面上，通过监听 OpenCode Server 的 SSE 事件流来感知用户的编码活动，并实时改变宠物的表情、动画和状态。

项目位置：`packages/pet/`，作为 OpenCode monorepo 的一个子包管理。

## 2. 技术选型决策

### 为什么选 Electron 而不是 Tauri？

经过调研和对比，最终选择 **Electron** 而非 Tauri：

- **透明窗口稳定性**：Electron 的 `transparent: true` + `frame: false` 在 Windows/macOS/Linux 三平台表现一致且成熟；Tauri 在 Linux 上依赖系统 WebView 和窗口合成器，透明效果不可靠
- **渲染一致性**：Electron 内置 Chromium，所有平台渲染结果相同（对 Live2D WebGL 渲染至关重要）；Tauri 使用系统 WebView（macOS=WKWebView, Linux=WebKitGTK），行为有差异
- **Live2D 兼容性**：Chromium 的 WebGL 支持最好，pixi-live2d-display 在 Electron 上零问题
- 用户明确表示不在意安装包体积和内存占用，消除了 Tauri 的唯一核心优势

### 为什么选 pixi.js v6 而非 v8？

`pixi-live2d-display`（最成熟的 Live2D 渲染库）只兼容 PixiJS v6，其 peerDependencies 声明 `"@pixi/core": "^6"`。PixiJS v8 目前没有稳定的 Live2D 渲染方案。

### 为什么选 "独立程序 + SSE" 而非 OpenCode 插件？

OpenCode 提供了 4 个集成层次：

| 方案 | 优点 | 缺点 |
|------|------|------|
| ① SDK + SSE 事件流（最终选择） | 完全独立，不侵入 OpenCode，升级不受影响 | 需确保服务器在运行 |
| ② Server Plugin 插件 | 进程内零延迟 | 紧耦合，仍需外部渲染程序 |
| ③ Config Hook | 最简单 | 只支持 `file_edited` 和 `session_completed` 两种事件 |
| ④ TUI Plugin | 可在终端内渲染 | 不是真正"桌面"宠物，终端渲染能力有限 |

选择方案①的理由：解耦最好、能力充足（SSE 覆盖 30+ 种事件）、技术栈自由、分发简单。

## 3. 系统架构

### 整体数据流

```
┌───────────────────────────────────────────────────────────┐
│  Electron App (packages/pet)                              │
│                                                           │
│  ┌─────────────────┐    IPC    ┌───────────────────────┐ │
│  │  Main Process    │◄────────►│  Renderer Process     │ │
│  │  (Node.js)       │          │  (Chromium WebView)   │ │
│  │                  │          │                       │ │
│  │  - 透明窗口管理   │          │  - PixiJS v6          │ │
│  │  - 系统托盘       │          │  - pixi-live2d-display│ │
│  │  - SSE 客户端    │          │  - Live2D 渲染器      │ │
│  │  - pet:// 协议   │          │  - 气泡对话 UI        │ │
│  │  - 窗口位置记忆   │          │  - 设置面板          │ │
│  │  - 窗口拖拽(JS)   │          │  - JS 拖拽交互       │ │
│  └────────┬─────────┘          └───────────────────────┘ │
│           │                                               │
└───────────┼───────────────────────────────────────────────┘
            │ HTTP (SSE + REST)
            ▼
┌─────────────────────┐
│  OpenCode Server     │
│  :4096               │
│                      │
│  /global/event (SSE) │ ── 全局事件流
│  /session/* (REST)   │ ── 会话查询
│  /file/* (REST)      │ ── 文件状态
│  /config (REST)      │ ── 配置管理
└─────────────────────┘
```

### 三进程架构（遵循 Electron 最佳实践）

```
Main Process (src/main/index.ts)
├── 创建透明 BrowserWindow
├── 注册 pet:// 自定义协议（服务 skins/ 目录的文件给渲染进程）
├── 注册系统托盘（Tray）
├── 注册 IPC handlers
│   ├── get-pet-state — 获取当前宠物状态
│   ├── get-activity-state — 获取当前 Activity 状态
│   ├── set-always-on-top — 设置窗口置顶
│   ├── set-click-through — 设置鼠标穿透
│   ├── show-window / hide-window — 窗口显示/隐藏
│   ├── resize-window — 调整窗口大小（设置面板展开/收起）
│   ├── move-window-by — JS 拖拽移动窗口（dx, dy 像素偏移）
│   ├── list-skins — 列出 skins/ 下含 model3.json 的目录
│   ├── get-skin-model — 获取指定 skin 的 pet:// model URL
│   ├── set-skin — 设置当前皮肤（影响 pet observer workspace）
│   ├── get-connection-status — SSE 连接状态
│   ├── get-server-config / set-server-config — 配置管理（electron-store 持久化）
│   ├── connect / disconnect — SSE 连接管理
│   ├── pet-chat-send — 用户直接对话（注入 pet observer session）
│   ├── pet-bubble — 气泡对话推送（主进程→渲染进程）
│   └── quit — 退出应用
├── 启动 SSE 连接（fetch-based，支持 Basic Auth）
├── 解析 GlobalEvent → 提取 directory + 解包 payload → 映射宠物状态（Activity/Reaction 两层）
├── 追踪子会话/活跃会话/用户消息 ID
├── Pet Observer 子系统
│   ├── 为每个 skin 初始化独立 workspace（含 pet-commenter agent + persona.md）
│   ├── 检测用户/助手消息 → 注入 SYSTEM-REMINDER → prompt_async
│   ├── 捕获 pet observer 助手回复 → 显示为气泡
│   └── 超长回复缩短机制（>50 字符自动请求精简版）
└── 通过 webContents.send("pet-state" / "pet-bubble") 推送给渲染进程

Preload (src/preload/index.ts)
├── contextBridge.exposeInMainWorld("pet", api)
└── 类型安全的 IPC 封装（window.pet.*）

Renderer (src/renderer/)
├── index.html — 透明背景 + flex 纵向布局（宠物+设置面板）
├── main.ts — 入口：状态管理（冷却队列 + idle→sleep）+ 渲染器 + IPC 监听 + 设置面板
├── pet/motion-map.ts — 固定动作映射表（16 状态 → Miku 动作列表）
├── pet/renderer.ts — Live2D 渲染器（PixiJS v6 + pixi-live2d-display）
│   ├── getMotions() — 返回当前模型的动作名列表
│   ├── getCurrentModel() — 返回当前模型 URL
│   ├── switchModel(url) — 切换模型（销毁旧模型，加载新模型）
│   ├── startAnimation(state) — 播放状态动画（Activity 循环，Reaction 单次）
│   └── triggerMotion(name) — 手动触发指定动作（FORCE 优先级）
└── types.ts — PetState 类型定义（16 种状态）
```

### 关键技术细节

#### 透明窗口

```ts
new BrowserWindow({
  transparent: true,     // 背景透明
  frame: false,          // 无标题栏
  alwaysOnTop: true,     // 始终置顶（Wayland 下可能不生效）
  skipTaskbar: true,     // 不在任务栏显示
  hasShadow: false,      // macOS 去掉窗口阴影
  resizable: true,       // Wayland/GTK 需要 resizable 才能缩小窗口
})
```

#### 拖拽（JS 实现，非 CSS drag）

**重要**：不能使用 `-webkit-app-region: drag`，因为它会吞掉右键 `contextmenu` 事件，导致设置面板无法通过右键打开。

当前方案：JS mousedown/mousemove/mouseup + IPC `move-window-by(dx, dy)`：
```ts
document.addEventListener("mousedown", (e) => { /* 左键开始拖拽 */ })
document.addEventListener("mousemove", (e) => { /* 计算偏移，调用 moveWindowBy */ })
document.addEventListener("mouseup", () => { /* 结束拖拽 */ })
```

#### 设置面板

- **触发方式**：右键点击宠物任意位置
- **展开效果**：窗口从 200×200 扩展到 300×520（宠物在上方 200px，设置面板在下方 320px）
- **布局**：`body` 纵向 flex，`#pet-container`（200px 高）在上，`#settings-overlay`（flex:1）在下
- **关闭**：点击 ✕ 按钮或按 Escape，窗口缩回 200×200
- **功能**：
  - Debug：实时显示当前状态、正在播放的 motion、映射/可用动作数
  - Model：切换模型（bongo-cat / hiyori / miku），点击按钮即可热切换
  - Options：Always on top 开关 + 缩放滑块（0.5x–3.0x）
  - State：16 个状态按钮手动触发宠物状态
  - Motions：列出当前模型所有动作（412 个），带 filter 输入框，点击触发动作
  - 动作按类别颜色区分：🔵 w-body（全身）、⚫ face（表情）、🩷 special（特殊）

#### Live2D 模型加载

渲染进程通过 `pet://` 自定义协议加载模型文件：
1. 主进程在 `protocol.registerSchemesAsPrivileged` 中注册 `pet` scheme
2. 主进程 `protocol.handle("pet", ...)` 将请求映射到 `skins/` 目录的文件
3. 渲染进程使用 `pet://miku/21miku_normal_3.0_f_t03.model3.json` 加载模型
4. 安全检查：确保请求路径不超出 skins 目录（防路径遍历）

#### SSE 连接

使用 Node.js 原生 `fetch` + `ReadableStream` 读取 SSE 流（而非 EventSource，因为需要自定义 Basic Auth header）。

`/global/event` 返回的是 `GlobalEvent`（包含所有项目的事件，带 `directory`/`project`/`workspace` 元信息），主进程将完整 GlobalEvent 传给 `handleOpenCodeEvent()`，内部解包 `globalEvent.payload` 取实际事件、`globalEvent.directory` 供 HTTP API 路由：

```
GlobalEvent = { directory, project?, workspace?, payload: { type, properties } }

fetch("/global/event", { headers: { Authorization: "Basic ..." } })
  → response.body.getReader()
  → 逐行解析 "data: {...}" 格式
  → JSON.parse → globalEvent → handleOpenCodeEvent(globalEvent)
  → event = globalEvent.payload, directory = globalEvent.directory
  → event.properties 取事件数据
  → setPetState()
  → webContents.send("pet-state", state)
```

宠物不对 `directory` 做过滤——响应所有项目的事件。连接断开后每 5 秒自动重连。

### Pet Observer（AI 评论气泡系统）

宠物通过 Pet Observer 子系统实时生成评论，显示在宠物头顶的气泡中。

#### 架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│  Main Process                                                        │
│                                                                      │
│  SSE 事件流                                                          │
│    │                                                                 │
│    ├─ 用户消息检测 (message.updated + message.part.updated)           │
│    │    → 包装为 SYSTEM-REMINDER                                      │
│    │    → injectUserMessage() → prompt_async → pet observer session  │
│    │                                                                 │
│    ├─ 助手回复检测 (session.status=idle → assistantTextBySession)     │
│    │    → 包装为 SYSTEM-REMINDER                                      │
│    │    → injectUserMessage() → prompt_async → pet observer session  │
│    │                                                                 │
│    ├─ 直接对话 (IPC pet-chat-send)                                    │
│    │    → setReaction("chatting", 30s)                                │
│    │    → 包装为 SYSTEM-REMINDER (type: direct)                       │
│    │    → injectUserMessage() → prompt_async → pet observer session  │
│    │                                                                 │
│    └─ Pet observer session 回复捕获 (message.part.updated)            │
│         → petAssistantMessageIDs 匹配 → showPetBubble()              │
│         → webContents.send("pet-bubble", text)                        │
│         → 20s 后自动清除                                               │
│                                                                      │
│  配置持久化: electron-store (url/username/password)                    │
│  Observer session: OpenCode workspace → pet-commenter agent           │
└─────────────────────────────────────────────────────────────────────┘
```

#### Per-Skin 独立 Workspace

每个皮肤拥有独立的 OpenCode workspace 目录，确保不同皮肤的 AI 人格互不干扰：

```
~/.config/pet/
├── workspace-miku/
│   └── .opencode/
│       ├── opencode.json              # { "permission": { "*": "deny" } }
│       └── agents/
│           └── pet-commenter.md       # 由 buildAgentPrompt(skin) 生成
├── workspace-hiyori/
│   └── ...
└── workspace-bongo-cat/
    └── ...
```

- **persona.md**：每个 `skins/{name}/persona.md` 定义皮肤人格（好奇、毒舌、暖心等）
- **agent 配置**：启动时 `initAllSkinWorkspaces()` 为所有检测到的 skin 生成 workspace
- **模型**：使用 `deepseek/deepseek-v4-flash`（轻量、快速、低成本）
- **权限**：`{ "*": "deny" }` 禁止 pet observer 执行任何工具

#### 事件注入时机

| 触发条件 | SYSTEM-REMINDER 类型 | 内容 |
|----------|---------------------|------|
| 用户发送消息（实时） | 用户消息通知 | Working Directory + Session + Time + 用户文本 |
| 助手完成回复（session idle 时） | 助手回复通知 | Working Directory + Session + Time + 助手完整回复文本 |
| 用户直接和宠物对话 | 直接对话 (type: direct) | Time + 用户输入 |

#### 关键机制

- **冷却**：2s（`OBSERVER_COOLDOWN_MS`）防止高频触发
- **Busy 超时保护**：60s（`PET_BUSY_TIMEOUT_MS`）防止 session 卡住
- **超长回复缩短**：回复 > 50 字符时不显示，自动注入缩短提醒，下次回复显示精简版
- **Session 复用**：优先复用 workspace 中已有的 session，新 session 仅在无可用时创建
- **Skin 切换**：切换皮肤时 `petSessionID` 置 null，下次触发时使用新 skin 的 workspace

#### 聊天交互

用户点击宠物 → 弹出聊天输入框 → 输入文字回车 → IPC `pet-chat-send` → 主进程包装为 SYSTEM-REMINDER(type: direct) → 注入 pet observer session → 宠物进入 `chatting` 状态（30s）→ AI 回复显示为气泡。

## 4. 宠物状态系统（v4 — 基于活动阶段，17 种状态）

> **v3 → v4 的核心变化**：v3 以宠物"情绪"为维度（happy/sad/angry），同一情绪映射多种不相关场景，用户无法从宠物判断 OpenCode 在干什么。v4 以 **OpenCode 的活动阶段** 为维度，让用户看一眼宠物就知道 AI 在思考、在输出、在执行工具还是在等我操作。

### 4.1 OpenCode 事件系统内部架构

宠物通过 `/global/event` SSE 端点接收事件。理解事件系统的内部结构对调试和扩展宠物状态至关重要。

#### 三层事件架构

```
┌─────────────────────────────────────────────────────────────────┐
│  OpenCode 事件源                                                 │
│                                                                  │
│  ① BusEvent（普通事件）                                           │
│     定义：BusEvent.define("type", Schema)                        │
│     发布：Bus.publish(def, properties)                           │
│     特点：不持久化，瞬态                                           │
│     例子：session.status, message.part.delta,                    │
│           permission.asked/replied, file.edited                  │
│                                                                  │
│  ② SyncEvent（同步事件）                                          │
│     定义：SyncEvent.define({ type, version, aggregate, schema }) │
│     发布：SyncEvent.run(def, data)                               │
│     特点：持久化到 DB（event table + sequence tracker），          │
│           通过 projector 更新物化视图                              │
│     例子：message.part.updated, message.updated,                 │
│           session.created/updated/deleted                        │
│                                                                  │
│  ③ 直接 GlobalBus.emit（全局事件）                                │
│     发布：GlobalBus.emit("event", { directory, project,          │
│              workspace, payload })                                │
│     特点：不经过 Bus，直接广播                                     │
│     例子：workspace.ready/failed, worktree.ready/failed,         │
│           project.updated                                         │
└─────────────────────────────────────────────────────────────────┘
```

#### GlobalBus（全局事件中枢）

```ts
// packages/opencode/src/bus/global.ts
type GlobalEvent = { directory?, project?, workspace?, payload: any }
const GlobalBus = new EventEmitter<{ event: [GlobalEvent] }>()
```

所有事件最终都汇聚到 GlobalBus，SSE 端点从这里订阅。

#### Bus.publish 的发布流程

```ts
// packages/opencode/src/bus/index.ts
function publish(def, properties) {
  // 1. 发布到实例内 PubSub（供 Effect 内部订阅）
  yield* PubSub.publish(typed, payload)
  yield* PubSub.publish(wildcard, payload)

  // 2. 同时发射到 GlobalBus（供 SSE 端点）
  GlobalBus.emit("event", { directory, project, workspace, payload })
}
```

#### SyncEvent 的双重发射机制

SyncEvent（如 `message.part.updated`）会被发射 **两次** 到 GlobalBus：

```ts
// packages/opencode/src/sync/index.ts → process()
Database.effect(() => {
  // ① 通过 Bus.publish → GlobalBus.emit（直接事件）
  ProjectBus.publish(def, data)  // payload: { type: "message.part.updated", properties: {...} }

  // ② 直接 GlobalBus.emit（sync 包装事件）
  GlobalBus.emit("event", {
    payload: { type: "sync", syncEvent: { type: "message.part.updated.1", ...event } }
  })
})
```

宠物系统对这两条路径有不同处理：
- ① 的 `message.part.updated` → 用于检测工具执行状态（`acting`）
- ② 的 `sync` → 仅用于追踪子会话（`session.created`/`session.deleted`）

#### SSE 端点实现

```ts
// packages/opencode/src/server/routes/instance/httpapi/handlers/global.ts
function eventResponse() {
  const events = Stream.callback<GlobalBusEvent>((queue) => {
    const handler = (event) => Queue.offerUnsafe(queue, event)
    return GlobalBus.on("event", handler)  // 订阅 GlobalBus
  })
  const heartbeat = Stream.tick("10 seconds").pipe(
    Stream.map(() => ({ payload: { type: "server.heartbeat", properties: {} } }))
  )
  return Stream.make({ payload: { type: "server.connected" } })
    .concat(events.merge(heartbeat))
    .map(eventData)         // JSON.stringify
    .pipeThroughChannel(Sse.encode())  // SSE framing
}
```

#### 事件发射源代码位置

| 事件类型 | 定义位置 | 发射位置 |
|----------|---------|---------|
| `session.status` | `session/status.ts` | `prompt.ts`(busy), `run-state.ts`(idle/cancel), `processor.ts`(busy/retry) |
| `message.part.delta` | `session/message-v2.ts` | `processor.ts`(text-delta, reasoning-delta) |
| `message.part.updated` | `session/message-v2.ts`(SyncEvent) | `processor.ts`(step-start/finish, text-start/end, tool pending/running/completed, patch) |
| `message.updated` | `session/message-v2.ts`(SyncEvent) | `processor.ts`(finish-step, cleanup), `prompt.ts`(初始创建) |
| `permission.asked/replied` | `permission/index.ts` | `permission/index.ts`(评估+用户回复) |
| `question.asked/replied` | `question/index.ts` | `question/index.ts`(工具提问+用户回答) |
| `file.edited` | `file/index.ts` | `tool/write.ts`, `tool/edit.ts`, `tool/apply_patch.ts` |
| `session.diff` | `session/session.ts` | `session/summary.ts`(每次 step-finish 后计算) |
| `session.compacted` | `session/compaction.ts` | `session/compaction.ts`(压缩完成) |
| `pty.created/exited` | `pty/index.ts` | `pty/index.ts`(用户通过 UI 打开终端，**不是** bash 工具) |
| `server.heartbeat` | — | SSE 端点内置，每 10s |

**重要**：bash 工具使用 `ChildProcessSpawner` 执行命令，**不**创建 Pty 会话。`pty.created`/`pty.exited` 只在用户通过 UI 打开终端时触发。

#### 典型会话事件时序

以 "创建 hello.ts 文件" 为例，SSE 客户端看到的精确事件序列：

```
时间轴          SSE 事件                                          间隔特征
─────────────────────────────────────────────────────────────────────────────
Phase 1: 初始化（同步 DB 写入链，<5ms 全部完成）

  T+0ms      session.status { type: "busy" }                  ─┐
  T+0ms      message.updated (user 消息)                        │ 紧密跟随
  T+0ms      message.part.updated (用户文本 part)                │ 全部同步 DB 写入
  T+0ms      session.updated (session touched)                  │
  T+0ms      message.updated (assistant 消息创建)                │
  T+0ms      message.part.updated { type: "step-start" }        │
  T+0ms      message.part.updated { type: "text", text: "" }   ─┘

              ╸╸╸╸╸╸╸╸ [LLM 思考中] ╸╸╸╸╸╸╸╸                    ← 200ms~3s

Phase 2: 文本流式输出

  T+500ms    message.part.delta { "I'll" }                    ─┐
  T+550ms    message.part.delta { " create" }                   │ ~50ms/个，高频流式
  T+600ms    message.part.delta { " that" }                     │ 纯 BusEvent，不写 DB
  ...（多次 delta）                                              │
  T+1200ms   message.part.updated { text: "完整文本" }          ─┘ 单个 SyncEvent 持久化

Phase 3: 工具调用（紧密跟随，<20ms）

  T+1210ms   message.part.updated { tool: "write", pending }  ─┐ 紧密跟随
  T+1220ms   message.part.updated { tool: "write", running }  ─┘ pending→running <10ms

Phase 4: 权限请求（可能长时间阻塞）

  T+1230ms   permission.asked                                ─┐ 阻塞等待用户
             [用户操作，1s ~ ∞]                                 │ 最长不确定间隔
  T+??s      permission.replied { reply: "once" }            ─┘

Phase 5: 工具执行 + 文件事件（紧密跟随）

  T+??+50ms  file.edited { file: "hello.ts" }                ─┐
  T+??+50ms  message.part.updated { tool, running, title }     │ 紧密跟随
  T+??+100ms message.part.updated { tool, completed }         ─┘
  T+??+110ms message.part.updated { step-finish, tokens }     ─┐
  T+??+110ms message.updated (assistant, finish reason)         │ 循环决策前的一组事件
  T+??+110ms message.part.updated { patch, files }            ─┘

Phase 6: 多轮循环（回到 Phase 1，间隔极短）

  T+??+150ms session.status { type: "busy" }                  ← 新一轮循环
             ╸╸╸╸╸╸╸╸ [LLM 第二轮] ╸╸╸╸╸╸╸╸                    ← 200ms~2s
  ...（重复 Phase 2-5）

Phase 7: 完成

  T+end      message.updated (assistant, completed)
  T+end      session.status { type: "idle" }                  ← 最终状态
```

#### 工具调用的完整事件序列

一个工具从开始到结束的 `message.part.updated` 状态变化：

```
tool-input-start → { type: "tool", state: { status: "pending" } }     ← 模型开始生成工具 JSON
  (tool-input-delta → 无事件发射)
  (tool-input-end   → 无事件发射)
tool-call       → { type: "tool", state: { status: "running" } }      ← JSON 生成完毕，工具开始执行
  (metadata callback → { type: "tool", state: { status: "running", title/metadata 更新 } }) × 0~N
  [工具执行中，可能持续数秒到数分钟]
tool-result     → { type: "tool", state: { status: "completed" } }    ← 工具执行完毕
  或
tool-error      → { type: "tool", state: { status: "error" } }       ← 工具执行失败
```

宠物系统只对 `status: "running"` 做出反应（进入 `acting`）。工具完成后（completed/error），下一个 `message.part.delta` 会将状态切到 `generating`。

#### 事件间隔分类

| 间隔类型 | 位置 | 典型时长 | 对宠物的影响 |
|----------|------|----------|-------------|
| **紧密跟随** | 初始化簇、工具启动簇、工具完成簇、循环重启簇 | <50ms | 多个事件几乎同时到达，需要冷却队列防抖 |
| **LLM 首 token** | `session.status=busy` → 第一个 `message.part.delta` | 200ms~3s | 宠物应表现"等待思考" |
| **Delta 流** | 连续的 `message.part.delta` | ~50ms/个，持续1~30s | 宠物应表现"正在输出" |
| **等待权限** | `permission.asked` → `permission.replied` | **不确定，1s~∞** | 宠物必须表现"在等用户" |
| **工具执行** | `tool:running` → `tool:completed` | 几ms~几分钟 | 宠物应表现"在执行" |
| **重试等待** | `session.status=retry` → `session.status=busy` | 2s~30s（指数退避） | 宠物应表现"遇到问题，重试中" |
| **会话间空闲** | `session.status=idle` → 下次 `busy` | 用户决定 | 30s 后 → sleeping |

#### `session.status` 的发射位置

`session.status=busy` 会在三个位置被重复发射（均为同一状态，不触发宠物状态变更）：

1. **Runner.onBusy**（`run-state.ts:61`）— Runner 从 Idle 转为 Running 时
2. **runLoop 顶部**（`prompt.ts:1285`）— 每次 `while(true)` 循环迭代
3. **processor start**（`processor.ts:219`）— LLM 流发出 `"start"` 事件时

`session.status=idle` 仅在 Runner 完成全部工作后发射（`run-state.ts:59`），确保所有清理已完成。

#### `session.diff` 的触发频率

`session.diff` 在 `session/summary.ts` 的 `summarize()` 函数中发射。`summarize()` 在以下时机被调用：

1. **每个 step-finish 后**（`processor.ts:391-396`）— 无论是否有文件变更，都会计算 diff 并发布
2. **loop 第一次迭代时**（`prompt.ts:1422`）— 对上一轮用户消息做摘要

这意味着 `session.diff` **每步都触发一次**，即使没有文件变更（diff=[] 也会发布）。v4 宠物已移除 `session.diff` 对 `file-written` 的触发，仅依赖精确的 `file.edited` 事件。

#### 事件系统关键代码位置

| 组件 | 文件 |
|------|------|
| GlobalBus 定义 | `packages/opencode/src/bus/global.ts` |
| Bus（实例级 PubSub） | `packages/opencode/src/bus/index.ts` |
| BusEvent 定义注册 | `packages/opencode/src/bus/bus-event.ts` |
| SyncEvent 系统 | `packages/opencode/src/sync/index.ts` |
| SyncEvent projector 注册 | `packages/opencode/src/server/projectors.ts` |
| Session projector 实现 | `packages/opencode/src/session/projectors.ts` |
| 全局 SSE 端点（HTTP API） | `packages/opencode/src/server/routes/instance/httpapi/handlers/global.ts` |
| 实例 SSE 端点 | `packages/opencode/src/server/routes/instance/httpapi/event.ts` |
| 会话主循环 | `packages/opencode/src/session/prompt.ts` |
| LLM 流事件处理 | `packages/opencode/src/session/processor.ts` |
| Runner 状态机 | `packages/opencode/src/session/run-state.ts` |
| 会话状态管理 | `packages/opencode/src/session/status.ts` |
| 权限系统 | `packages/opencode/src/permission/index.ts` |
| 文件事件定义 | `packages/opencode/src/file/index.ts` |
| Pty 会话管理 | `packages/opencode/src/pty/index.ts` |

### 4.2 设计原则

**两层状态架构：**

| 层 | 类型 | 特征 | 用途 |
|---|---|---|---|
| **Activity（持久）** | 不自动回退，由事件驱动切换 | 表示 OpenCode 当前工作阶段 |
| **Reaction（瞬态）** | 超时后回退到**当前 Activity**（不是一律 idle） | 对特定事件的短暂反应 |

**设计决策记录：**
- `message.part.delta` 每个 token 都触发 `generating`（渲染进程去重），确保工具完成后能正确回到 generating
- `permission.replied` 设为 `thinking`（非 `acting`），因为授权后 AI 的下一个动作不确定，`thinking` 是最安全的中间态
- `session.diff` 不再触发 `file-written`，因为它每步都触发（即使无文件变更），`file.edited` 已精确覆盖
- Reaction 回退到当前 Activity，而非一律 idle（文件编辑发生在工具执行期间，回退到 idle 是错误的）

### 4.3 状态定义（17 种）

#### 持久状态（Activity）— 8 个

表示 OpenCode 此刻正在做什么，由事件驱动切换，不自动回退。

| # | 状态 | 含义 | 进入信号 | 退出信号 |
|---|------|------|----------|----------|
| 1 | `idle` | 空闲待命 | `session.status=idle`（所有会话空闲） | `session.status=busy` |
| 2 | `thinking` | AI 正在思考 | `session.status=busy` / `permission.replied` / `question.replied` | 第一个 `message.part.delta`，或 `message.part.updated`(tool, running)，或 `permission.asked` / `question.asked` |
| 3 | `generating` | AI 正在输出 | 任何 `message.part.delta` | 工具启动，或 `session.status=idle` |
| 4 | `acting` | 工具正在执行 | `message.part.updated`（tool, status=running） | 工具完成/错误，或 `session.status=idle` |
| 5 | `waiting-user` | 等待用户操作 | `permission.asked` 或 `question.asked` | `permission.replied` 或 `question.replied` |
| 6 | `retrying` | 请求失败，重试中 | `session.status=retry` | `session.status=busy`（重试开始流式输出） |
| 7 | `sleeping` | 休眠 | 在 `idle` 状态下 30s 无任何事件 | 任何事件到达 |
| 8 | `disconnected` | 未连接到 OpenCode | SSE 连接断开 | SSE 连接成功 |

**状态转换图：**

```
  disconnected ──[SSE 连接]──→ greeting ──→ idle
                                                      │
                    ┌───── session.status=busy ────────┤
                    ▼                                  │
                thinking                               │
               ╱        ╲                              │
    [delta]   ╱          ╲ [tool:running]              │
             ▼            ▼                            │
         generating    acting                          │
             │            │                            │
             │    [permission.asked]                   │
             │    [question.asked]                     │
             │            │                            │
             │            ▼                            │
             │     waiting-user ──[replied]──→ acting/thinking
             │                                         │
             ╰────────────╰─── session.status=idle ────┘
                    │
                    │ [error + retryable]
                    ▼
                 retrying ──[delay 过后]──→ thinking

  idle ──[30s 无事件]──→ sleeping ──[任何事件]──→ idle
```

#### 瞬态反应（Reaction）— 9 个

对特定事件的短暂响应。**超时后回退到当前 Activity 状态**（不是一律 idle）。

| # | 状态 | 触发事件 | 持续时间 | 含义 |
|---|------|----------|----------|------|
| 9 | `greeting` | SSE 连接成功 | 2s | 打招呼，回退到 `idle` |
| 10 | `chatting` | 用户通过聊天输入框直接对话 | 30s | 宠物正在被直接对话，前倾听（会被 pet session 事件覆盖） |
| 11 | `file-written` | `file.edited` / `worktree.ready` | 2s | 文件已保存 |
| 12 | `completed` | `todo.updated`（全部完成）/ `workspace.ready` | 4s | 任务完成，庆祝 |
| 13 | `upgraded` | `installation.updated` | 3s | OpenCode 已升级 |
| 14 | `error` | `session.error` / `workspace.failed` / `worktree.failed` / `mcp.browser.open.failed` | 3s | 出错了 |
| 15 | `pty-exited` | `pty.exited`（附带 exitCode） | 2.5s | 终端进程退出（exitCode 决定动画风格：0=成功，≠0=失败） |
| 16 | `compacted` | `session.compacted` | 2.5s | 上下文已压缩 |
| 17 | `env-changed` | `vcs.branch.updated` / `mcp.tools.changed` / `project.updated` / `installation.update-available` / `pty.created` | 2s | 环境发生变化 |

#### 未来交互反应（暂未实现）

| 触发方式 | 状态 | 持续时间 | 说明 |
|----------|------|----------|------|
| 用户点击宠物 | `shy` | 2s | 害羞/脸红反应 |

### 4.4 完整事件 → 状态映射

#### 持久状态映射

| SSE 事件 | 条件 | 新状态 | 变化说明 |
|----------|------|--------|---------|
| `session.status` | `status=busy` | `thinking` | 拆分：busy 初始阶段是"思考"，不是笼统的"工作" |
| `session.status` | `status=idle` | `idle` | 不变 |
| `session.status` | `status=retry` | `retrying` | **新增**：独立状态 |
| `message.part.delta` | 每次 | `generating` | 每个 token 直接触发，不再需要"首次"守卫 |
| `message.part.updated` | tool, status=running | `acting` | 仅 running 状态触发 |
| `permission.asked` | — | `waiting-user` | 需要用户操作 |
| `question.asked` | — | `waiting-user` | 同上 |
| `permission.replied` | — | `thinking` | 回到思考状态（下一个事件会立即覆盖） |
| `question.replied` | — | `thinking` | 回到会话处理 |
| SSE 断开 | — | `disconnected` | 不变 |
| 30s 无事件 | — | `sleeping` | 不变 |

> **设计决策**：`message.part.delta` 每个 token 都触发 `generating`（渲染进程去重相同状态），确保工具完成后 AI 继续输出文字时能正确回到 `generating`。之前的 `hasReceivedDelta` 标志导致工具执行完毕后状态卡在 `acting`，已移除。

> **设计决策**：`permission.replied` 设为 `thinking` 而非 `acting`，因为授权完毕后 AI 可能继续生成文本或调用下一个工具，`thinking` 是最安全的中间状态，下一个事件会立即将其覆盖为准确状态。

#### 瞬态反应映射

| SSE 事件 / 触发方式 | 新状态 | 变化说明 |
|----------|--------|---------|
| SSE 连接成功 | `greeting` → idle | 不变 |
| 用户聊天输入（IPC `pet-chat-send`） | `chatting` → current activity | **新增**：用户直接和宠物对话，30s 超时 |
| `file.edited` | `file-written` → current activity | 精确信号：工具实际写入文件时触发 |
| `worktree.ready` | `file-written` → current activity | worktree 初始化完成 |
| ~~`session.diff`~~ | ~~`file-written`~~ | **已移除**：每步都触发（即使无文件变更），与 `file.edited` 重复 |
| `todo.updated`（全部完成） | `completed` → current activity | 有 `allDone` 守卫 |
| `workspace.ready` | `completed` → current activity | 不变 |
| `installation.updated` | `upgraded` → current activity | 不变 |
| `session.error` | `error` → current activity | 不变 |
| `workspace.failed` | `error` → current activity | 不变 |
| `worktree.failed` | `error` → current activity | 不变 |
| `mcp.browser.open.failed` | `error` → current activity | 不变 |
| `pty.exited` | `pty-exited` → current activity | 仅用户终端退出（bash 工具不触发） |
| `session.compacted` | `compacted` → current activity | 不变 |
| `vcs.branch.updated` | `env-changed` → current activity | 不变 |
| `mcp.tools.changed` | `env-changed` → current activity | 不变 |
| `project.updated` | `env-changed` → current activity | 不变 |
| `installation.update-available` | `env-changed` → current activity | 不变 |
| `pty.created` | `env-changed` → current activity | 仅用户打开终端时 |

#### 不再触发宠物状态的事件

| SSE 事件 | v3 行为 | v4 行为 | 原因 |
|----------|---------|---------|------|
| `session.idle` | → `idle` | 忽略 | `session.status=idle` 已覆盖，冗余 |
| `command.executed` | → `thinking` | 忽略 | 紧随其后必有 `session.status=busy`，由 busy 触发 `thinking` |
| `message.part.updated`（非 tool） | → `thinking`（idle 时） | 忽略 | 流式内容的 part 更新已在 `generating` 中体现 |
| `server.heartbeat` | 唤醒 sleeping | 唤醒 sleeping | 不变 |

### 4.5 典型会话的状态流

以 "创建 hello.ts 文件" 为例：

```
用户发送 prompt
  → thinking（低头沉思，200ms~3s，等待 LLM）
  → generating（有节奏地输出文本）
  → acting（工具开始执行）
  → waiting-user（抬头看向用户，等批准）     ← 关键：用户知道需要操作
  → thinking（用户批准后回到思考）
  → acting（继续执行工具）
  → file-written（短暂开心，2s） → acting（回退到工具执行中）
  → thinking（第二轮循环，等待 LLM）
  → generating（输出"已创建 hello.ts"）
  → idle（完成）
```

### 4.6 渲染层动画映射

#### Activity 状态的动画风格

| 状态 | 动画风格 | 播放策略 | 建议动作关键词 |
|------|----------|----------|---------------|
| **idle** | 轻松闲逛，东张西望 | 循环：5-8s | tilthead, pose, lookaround, wink, piece |
| **thinking** | 专注前倾，低头沉思 | 循环：4-6s | think, forward, tilthead |
| **generating** | 有节奏地点头/说话 | 循环：3-5s | nod, piece, glad, pose（更活跃的节奏） |
| **acting** | 认真工作，决意 | 循环：4-6s | purpose, nod, pose（坚定风格） |
| **waiting-user** | 抬头看向用户，歪头期待 | 循环：5-8s | tilthead, forward, lookaround（看向"屏幕外"） |
| **retrying** | 焦急，坐立不安 | 循环：5-8s | fidget, trouble, sigh |
| **sleeping** | 打盹 | 持续 | sleep |
| **disconnected** | 焦急寻找，四处张望 | 循环：6-10s | trouble, fidget, lookaround, sigh |

#### Reaction 状态的动画风格

| 状态 | 动画风格 | 播放策略 | 建议动作关键词 |
|------|----------|----------|---------------|
| **greeting** | 挥手打招呼 | 单次 | greeting, shakehand, piece |
| **chatting** | 前倾听用户说话，歪头好奇 | 单次 | forward, tilthead, nod, wink, shy, blushed |
| **file-written** | 开心，比耶 | 单次 | glad, piece, delicious |
| **completed** | 大庆祝，唱歌 | 单次 | guts, musical, wandahoi, armopen, hug |
| **upgraded** | 惊喜，转圈 | 单次 | wandahoi, cheek, glad |
| **error** | 难过，叹气 | 单次 | sad, sigh, shakehead |
| **pty-exited** | exitCode=0: 松口气 / ≠0: 摇头叹气 | 单次 | exitCode=0: relief, glad / exitCode≠0: sigh, sad, shakehead |
| **compacted** | 松口气后继续 | 单次 | relief, nod, sigh |
| **env-changed** | 惊讶，歪头 | 单次 | cheek, catch, tilthead |
| **shy** | 脸红，扭捏 | 单次 | blushed, shy, wink |

### 4.7 实现要点

#### 主进程需要追踪的状态

v4 主进程维护以下追踪状态：

```ts
// 核心状态
let currentPetState: PetState = "idle"
let activityState: PetState = "idle"        // 当前 Activity 状态（Reaction 超时后回退到此）
let reactionTimer: ReturnType<typeof setTimeout> | null = null

// SSE 连接配置（供 HTTP API 调用复用）
let serverUrl = ""
let serverCredentials = ""

// Pet Observer 状态
let petSessionID: string | null = null
let petSessionBusy = false
let petCommentTimer: ReturnType<typeof setTimeout> | null = null
let petBusyTimeout: ReturnType<typeof setTimeout> | null = null
let pendingShortenReminder: string | null = null
let lastObserverTime = 0
const OBSERVER_COOLDOWN_MS = 2_000
const PET_BUSY_TIMEOUT_MS = 60_000
const MAX_BUBBLE_LENGTH = 50

// 会话追踪
const childSessionIDs = new Set<string>()   // 子 agent 会话（过滤其事件）
const activeSessions = new Set<string>()     // 当前活跃会话
const userMessageIDs = new Set<string>()     // 用户消息 ID（用于识别用户输入文本）
const assistantMessageIDs = new Set<string>()// 助手消息 ID（用于累积助手回复文本）
const assistantTextBySession = new Map<string, string>() // 助手回复文本累积器
const petAssistantMessageIDs = new Set<string>() // Pet observer 助手消息 ID（用于捕获气泡内容）
const sessionTitles = new Map<string, string>()  // 会话标题缓存

// 已移除的追踪变量：
// - hasReceivedDelta：导致工具完成后无法回到 generating，已移除
// - permissionPending：未使用，已移除

function setPetState(state: PetState) {
  currentPetState = state
  if (ACTIVITY_STATES.has(state)) activityState = state
  mainWindow?.webContents.send("pet-state", state)
}

function setReaction(reaction: PetState, durationMs: number) {
  if (reactionTimer) { clearTimeout(reactionTimer); reactionTimer = null }
  setPetState(reaction)
  reactionTimer = setTimeout(() => {
    reactionTimer = null
    setPetState(activityState)  // 回退到当前 Activity
  }, durationMs)
}

function handleOpenCodeEvent(globalEvent) {
  const event = globalEvent.payload             // 解包 GlobalEvent
  const directory = globalEvent.directory       // 项目目录（供 HTTP API 路由）
  const data = event.properties ?? event.data ?? {}

  switch (event.type) {
    case "sync": {
      // 追踪子会话 + 用户消息 ID
      if (syncEvent.type?.startsWith("session.created")) { ... }
      if (syncEvent.type?.startsWith("message.updated") && syncEvent.data?.info?.role === "user") {
        userMessageIDs.add(syncEvent.data.info.id)
      }
      break
    }
    case "session.status": {
      // ⚠️ status 是对象 { type: "idle" }，不是字符串
      const statusType = data.status?.type
      if (statusType === "busy") {
        activeSessions.add(data.sessionID)
        if (!isChildSession(data.sessionID)) setPetState("thinking")
      } else if (statusType === "idle") {
        activeSessions.delete(data.sessionID)
        if (!isChildSession(data.sessionID) && directory) {
          fetchLastAssistantMessage(data.sessionID, directory)
            .then(text => { if (text) console.log(`[pet] assistant response: ${text}`) })
        }
        if (activeSessions.size === 0) setPetState("idle")
      } else if (statusType === "retry") {
        if (!isChildSession(data.sessionID)) setPetState("retrying")
      }
      break
    }
    case "message.updated": {
      if (data.info?.role === "user") userMessageIDs.add(data.info.id)
      break
    }
    case "message.part.delta": {
      if (isChildSession(data.sessionID)) break
      setPetState("generating")              // 每个 token 都触发，渲染进程去重
      break
    }
    case "message.part.updated": {
      if (isChildSession(data.sessionID)) break
      if (data.part?.type === "tool" && data.part?.state?.status === "running") {
        setPetState("acting")
      }
      // 识别用户消息文本
      if (data.part?.type === "text" && data.part.messageID && userMessageIDs.has(data.part.messageID)) {
        console.log(`[pet] user message: ${data.part.text}`)
        userMessageIDs.delete(data.part.messageID)
      }
      break
    }
    case "permission.asked":
    case "question.asked":
      setPetState("waiting-user")
      break
    case "permission.replied":
    case "question.replied":
      setPetState("thinking")                // 安全中间态，下个事件立即覆盖
      break
    case "file.edited":
      setReaction("file-written", 2000)
      break
    case "worktree.ready":
      setReaction("file-written", 2000)
      break
    // ... 其他 Reaction 类似
  }
}

// 用户直接对话（通过 IPC pet-chat-send，非 SSE 事件）
ipcMain.on("pet-chat-send", (text) => {
  setReaction("chatting", 30_000)  // 30s 超时，通常会被 pet session 事件覆盖
  injectUserMessage(SYSTEM-REMINDER wrapping text)  // 注入 pet observer session
})
```

#### 渲染进程的职责

1. **冷却队列**：Activity 间 500ms，Reaction 间 3500ms，防止快速事件导致动画闪烁
2. **Reaction 回退**：v3 中一律回到 `idle`。v4 中由主进程在 Reaction 超时后推送 `activityState`
3. **`pty-exited` 的 exitCode 传递**：主进程将 exitCode 作为附加数据传递，渲染进程据此选择动画
4. **idle → sleeping**：渲染进程维护 30s 计时器

#### 新增宠物状态需同步修改的文件

`preload/types.ts` → `renderer/types.ts`（类型）→ `main/index.ts`（事件映射+Activity 追踪+Reaction 机制）→ `pet/motion-map.ts`（动作映射）→ `pet/renderer.ts`（播放策略+循环间隔）→ `main.ts`（气泡文案+冷却队列+Reaction 回退）→ `index.html`（设置面板按钮）。共 17 种状态。

### 4.8 Miku 之外模型的兼容策略

对于动作数量少的模型，按 Activity/Reaction 分类降级：

| 模型 | 动作数 | 降级策略 |
|---|---|---|
| bongo-cat | 2 动作 + 3 表情 | Activity: idle/sleeping/generating→idle动画, thinking/acting/retrying/waiting-user→摇摆喵. Reaction: 正面→雷霆喵, 负面→雷霆喵, neutral→摇摆喵 |
| Hiyori | 11 动画 | Activity: idle/sleeping→Idle随机, thinking/generating/acting/retrying/waiting-user→TapBody+Idle混合. Reaction: 全部→TapBody |

### 4.9 v3 → v4 迁移清单

- [x] 更新 `PetState` 类型（`src/preload/types.ts` + `src/renderer/types.ts`）
- [x] 重写 `handleOpenCodeEvent()`（`src/main/index.ts`）
- [x] 新增 Activity 状态追踪变量（`activityState`）
- [x] 实现 `setReaction()` 机制（Reaction 超时后回退到 `activityState`）
- [x] 重写 `motion-map.ts`（17 个状态）
- [x] 更新 `renderer.ts` 的播放策略（新 Activity 的循环间隔）
- [x] 更新 `main.ts` 的冷却队列（Activity 转换缩短冷却）、回退逻辑、UI 标签、气泡文案
- [x] 更新 `index.html` 设置面板按钮（17 个）
- [x] 移除 `hasReceivedDelta` 标志（每个 delta 直接触发 generating）
- [x] `permission.replied` 改为 `thinking`（非 `acting`）
- [x] 移除 `session.diff` 对 `file-written` 的触发（仅保留 `file.edited`）
- [x] 移除右下角状态标签（减少透明间隙）
- [ ] 测试典型场景：简单问答、工具调用+权限、多轮 agentic loop、retry、compaction、SSE 断连重连

## 5. 已完成的功能

### 基础架构

- [x] Electron 项目搭建（electron-vite 构建链）
- [x] 透明无边框窗口（alwaysOnTop, skipTaskbar, resizable:true, 等比缩放）
- [x] 窗口拖拽（JS mousedown/mousemove/mouseup + IPC move-window-by）
- [x] 窗口位置记忆（pet-position.json，仅保存位置，不保存尺寸）
- [x] 按比例缩放（scale slider 0.5x–3.0x，窗口尺寸匹配模型实际宽高比）
- [x] 逐像素点击穿透（`setIgnoreMouseEvents(true, {forward:true})` + `elementFromPoint` 检测）
- [x] 系统托盘（显示/隐藏/移至中心/退出）
- [x] 系统托盘 tooltip 显示连接状态（✅ Connected / ❌ Disconnected）
- [x] IPC 通信体系（contextBridge + ipcMain/ipcRenderer）
- [x] pet:// 自定义协议（安全地服务 skins/ 目录文件给渲染进程）
- [x] 环境变量配置连接（OPENCODE_SERVER_HOST/PORT/USERNAME/PASSWORD）
- [x] electron-builder 三平台打包配置（dmg/nsis/AppImage/deb）

### 渲染引擎

- [x] Live2D 渲染器（pixi.js v6 + pixi-live2d-display/cubism4 + live2dcubismcore）
- [x] Live2D CubismCore 加载修复（fetch+间接 eval，不使用 npm import）
- [x] pet:// 协议路径修复（skinsDir 计算 + hostname 拼接）
- [x] 全身动作替换 face 动作（face 动作变化太小，改为只用 w- 系列全身动作）
- [x] 模型切换功能（PetRenderer.switchModel，销毁旧模型加载新模型）
- [x] 动作手动触发功能（PetRenderer.triggerMotion，FORCE 优先级）
- [x] 渲染进程日志转发到主进程（console-message 事件）

### SSE 连接

- [x] OpenCode SSE 事件流连接（fetch-based，支持 Basic Auth）
- [x] SSE 事件解析修正（GlobalEvent 完整传递，directory 用于 HTTP API 路由）
- [x] 自动重连（指数退避：1s~30s）
- [x] session.status bug 修复（status 是 `{ type }` 对象，不是字符串）
- [x] 用户消息文本识别（通过 message.updated 追踪 user 消息 ID，message.part.updated 提取文本）
- [x] 助手回复文本累积（assistantTextBySession，session idle 时注入 pet observer）
- [x] 配置持久化（electron-store 存储 url/username/password）

### Pet Observer（AI 评论气泡）

- [x] Per-skin 独立 workspace（`~/.config/pet/workspace-{skin}/`，含 pet-commenter agent + persona.md）
- [x] 用户消息实时注入（message.part.updated 检测用户文本 → SYSTEM-REMINDER → prompt_async）
- [x] 助手回复注入（session idle → 累积文本 → SYSTEM-REMINDER → prompt_async）
- [x] 直接对话交互（点击宠物 → 聊天输入框 → IPC pet-chat-send → SYSTEM-REMINDER type:direct）
- [x] 气泡显示与自动清除（pet-bubble IPC，20s 后自动隐藏）
- [x] 超长回复缩短机制（>50 字符自动注入缩短提醒，下次回复显示精简版）
- [x] 冷却保护（2s cooldown）+ busy 超时保护（60s）
- [x] Skin 切换自动切换 workspace（petSessionID 置 null，下次使用新 workspace）

### 模型资源

- [x] bongo-cat 猫咪（3 表情 + 2 动画 + 音效）
- [x] Hiyori 日向（10 个 idle 动画 + 点击动画 + 物理 + 姿势）
- [x] PJSK 初音未来（412 个动作 + 物理效果，当前默认模型）

### UI 交互

- [x] JS 拖拽替代 CSS drag（修复右键菜单无法弹出的问题）
- [x] ~~右下角状态标签（半透明黑底，实时显示当前状态文字）~~（已移除，减少透明间隙）
- [x] 直接聊天交互（点击宠物 → 弹出聊天输入框 → 回车发送 → AI 评论气泡显示回复）
- [x] 设置面板（右键打开，窗口等比扩展）
  - 模型切换（bongo-cat / hiyori / miku 热切换）
  - Always on top 开关
  - 17 个状态按钮手动触发（含 chatting）
  - 动作列表（412 个，带 filter，点击触发，按类别颜色区分）
  - 缩放滑块（0.5x–3.0x）

### 状态系统（v4 — 已实现）

- [x] 17 种宠物状态（8 Activity + 9 Reaction）
- [x] Activity/Reaction 两层架构（主进程追踪 activityState，Reaction 超时回退到当前 Activity）
- [x] 每个 SSE 事件直接映射对应状态（最新事件胜出），渲染进程冷却队列去重
- [x] Activity 间 500ms 冷却，Reaction 间 3500ms 冷却
- [x] 固定动作映射表（`motion-map.ts`，16 状态 → 精选动作）
- [x] Reaction 自动回退（file-written 2s, completed 4s, error 3s 等）
- [x] 30s idle → sleeping 自动进入
- [x] Activity 循环播放 / Reaction 单次播放
- [x] 状态变化时弹出气泡对话提醒
- [x] 设置面板 17 个状态按钮（中文名 + emoji，含 chatting）
- [x] 设置面板顶部 Debug 信息（当前状态、正在播放的 motion、映射/可用动作数）
- [x] 子会话过滤（`childSessionIDs` 追踪，忽略子 agent 事件）

## 6. 宠物状态与事件映射（v4 快速参考，17 种状态）

> 详细设计见第 4 节。本节为快速查阅的速查表。

### Activity 状态（持久）

```
┌───────────┬──────────────┬──────────────┬──────────────┐
│   idle    │   thinking   │  generating  │   acting     │
│  😺 空闲   │  🤔 AI思考中  │  💬 AI输出中  │  🔧 工具执行  │
│  5-8s 循环 │  4-6s 循环    │  3-5s 循环   │  4-6s 循环   │
├───────────┼──────────────┼──────────────┼──────────────┤
│ waiting-  │  retrying    │  sleeping    │disconnected  │
│  user     │  🔄 重试中    │  😴 休眠     │  📡 断开连接  │
│  👀 等用户  │  5-8s 循环   │  8-12s 循环  │  6-10s 循环  │
│  5-8s 循环 │              │              │              │
└───────────┴──────────────┴──────────────┴──────────────┘
```

### Reaction 状态（瞬态，回退到当前 Activity）

```
┌────────────┬─────────────┬──────────────┬──────────────┐
│  greeting  │  chatting   │ file-written │  completed   │
│  👋 打招呼  │  💬 被对话   │  📝 文件已保存│  🎉 任务完成  │
│  2s        │  30s        │  2s          │  4s          │
├────────────┼─────────────┼──────────────┼──────────────┤
│  upgraded  │   error     │ pty-exited   │  compacted   │
│  🆙 已升级  │  ❌ 出错     │  💻 终端退出  │  📦 已压缩   │
│  3s        │  3s         │  2.5s        │  2.5s        │
├────────────┼─────────────┼──────────────┼──────────────┤
│env-changed │             │              │              │
│  🌐 环境变化 │             │              │              │
│  2s        │             │              │              │
└────────────┴─────────────┴──────────────┴──────────────┘
```

### 事件 → 状态速查

| SSE 事件 | 状态 | 类型 |
|----------|------|------|
| `session.status=busy` | `thinking` | Activity |
| `session.status=idle` | `idle` | Activity |
| `session.status=retry` | `retrying` | Activity |
| `message.part.delta` | `generating` | Activity |
| `message.part.updated`（tool, running） | `acting` | Activity |
| `permission.asked` / `question.asked` | `waiting-user` | Activity |
| `permission.replied` | → `thinking` | Activity |
| `question.replied` | → `thinking` | Activity |
| SSE 断开 | `disconnected` | Activity |
| 30s 无事件 | `sleeping` | Activity |
| SSE 连接成功 | `greeting` | Reaction |
| 用户聊天输入（IPC） | `chatting` | Reaction |
| `file.edited` / `worktree.ready` | `file-written` | Reaction |
| `todo.updated`（全部完成）/ `workspace.ready` | `completed` | Reaction |
| `installation.updated` | `upgraded` | Reaction |
| `session.error` / `workspace.failed` / `worktree.failed` / `mcp.browser.open.failed` | `error` | Reaction |
| `pty.exited` | `pty-exited` | Reaction |
| `session.compacted` | `compacted` | Reaction |
| `vcs.branch.updated` / `mcp.tools.changed` / `project.updated` / `installation.update-available` / `pty.created` | `env-changed` | Reaction |

## 7. 待完成的功能

### P1 — 用户体验

- [ ] **调试日志清理**：当前有部分 console.log 调试日志（如 `pet:// request: ...`），确认稳定后需要清理
- [ ] **开机自启动**：各平台实现（Mac: LoginItems, Windows: 注册表/启动文件夹, Linux: .desktop autostart）
- [ ] **更多 Live2D 模型**：从 Live2D 官方下载初音未来、Tororo/Hijiki 猫咪等模型
- [ ] **模型下载/安装机制**：用户可以从设置面板下载和安装社区模型
- [ ] **Wayland always-on-top**：研究可用的 workaround 或提供 XWayland 回退选项

### P2 — 交互增强

- [ ] **鼠标悬停反馈**：宠物识别鼠标靠近，做出抬头/转头反应（shy 状态）
- [ ] **拖动时动画**：拖动窗口时宠物进入 surprised 状态
- [ ] **气泡对话内容增强**：显示当前编辑的文件名等更丰富的上下文信息
- [ ] **宠物行走动画**：宠物可以在屏幕底部来回走动
- [ ] **多显示器支持**：记住宠物在不同显示器上的位置

### P3 — 高级功能

- [ ] **语音气泡**：当 AI 回复时，宠物头顶显示 AI 输出的文字片段
- [ ] **成就系统**：追踪编码统计（代码行数、会话次数等），解锁宠物装扮
- [ ] **多宠物**：同时显示多个不同的宠物角色
- [ ] **社区皮肤市场**：在线浏览和下载用户创建的宠物皮肤

## 8. 皮肤系统设计

### 目录结构

```
skins/
├── lib/                          # 运行时库
│   └── live2dcubismcore.min.js   # Cubism 4 Core（从 npm 包复制）
│
├── miku/                         # PJSK 初音未来（当前默认）
│   ├── 21miku_normal_3.0_f_t03.model3.json
│   ├── 21miku_normal_3.0_f_t03.moc3
│   ├── 21miku_normal_3.0_f_t03.physics3.json
│   ├── 21miku_normal_3.0_f_t03.2048/
│   │   └── texture_00.png
│   └── motions/                  # 412 个动作文件
│       ├── face_smile_01.motion3.json    # 脸部表情动作（已弃用，变化太小）
│       ├── w-normal01-nod.motion3.json   # 全身动作（点头）
│       ├── w-cute01-glad.motion3.json    # 全身动作（开心）
│       └── ...
│
├── bongo-cat/                    # Live2D 猫咪（备选）
│   ├── cat.model3.json           # 模型入口
│   ├── demomodel.moc3            # Cubism 4 模型数据
│   ├── demomodel.1024/           # 贴图
│   │   ├── texture_00.png
│   │   ├── texture_01.png
│   │   └── texture_02.png
│   ├── live2d_expression0.exp3.json  # 默认喵
│   ├── live2d_expression1.exp3.json  # 社会喵
│   ├── live2d_expression2.exp3.json  # 天使喵
│   ├── live2d_motion1.motion3.json   # 雷霆喵（带音效）
│   ├── live2d_motion2.motion3.json   # 摇摆喵
│   └── live2d_motion1.flac           # 动作音效
│
├── hiyori/                       # Live2D 日向（官方示例）
│   ├── Hiyori.model3.json
│   ├── Hiyori.moc3
│   ├── Hiyori.2048/
│   ├── Hiyori.physics3.json      # 物理效果（头发、衣服摆动）
│   ├── Hiyori.pose3.json         # 姿势预设
│   ├── Hiyori.cdi3.json          # 参数显示信息
│   ├── Hiyori.userdata3.json     # 用户数据
│   └── motions/                  # 10 个 idle 动画 + 1 个 tap 动画
│
└── default-cat/                  # （已移除，仅保留 Live2D 模型）
```

### 可用资源

| 模型 | 来源 | 许可证 | 适合程度 |
|------|------|--------|---------|
| bongo-cat 猫咪 | [liwenka1/bongo-cat-next](https://github.com/liwenka1/bongo-cat-next) | MIT | ⭐⭐⭐ 完美 |
| Hiyori 日向 | Live2D Cubism SDK Samples | Free Material License | ⭐⭐ 官方示例 |
| Mark-kun | Live2D SDK Samples | Free Material License | ⭐⭐ 简单 Q 版 |
| Hatsune Miku 初音未来 | Live2D 官方示例页 | Free Material + Crypton Guidelines | ⭐⭐ 需单独下载 |
| Tororo & Hijiki 猫 | Live2D 官方示例页 | Free Material | ⭐⭐⭐ 猫模型 |
| Wanko 狗 | Live2D SDK Samples | Free Material License | ⭐⭐ 宠物风 |

Live2D 官方示例下载页：https://www.live2d.com/en/learn/sample/

## 9. 运行方式

### 开发

```bash
cd packages/pet
bun install

# 方式一：自动连接本地 OpenCode（默认 127.0.0.1:4096）
bun run dev

# 方式二：指定远程 OpenCode 服务器
OPENCODE_SERVER_HOST=100.64.0.2 OPENCODE_SERVER_PORT=4096 bun run dev
```

### 环境变量（已弃用，改用 electron-store 持久化配置）

配置已迁移到 electron-store 持久化存储，通过设置面板 GUI 管理。以下环境变量仅作历史参考：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OPENCODE_SERVER_HOST` | `127.0.0.1` | ~~OpenCode 服务器地址~~ |
| `OPENCODE_SERVER_PORT` | `4096` | ~~OpenCode 服务器端口~~ |
| `OPENCODE_SERVER_USERNAME` | `opencode` | ~~Basic Auth 用户名~~ |
| `OPENCODE_SERVER_PASSWORD` | 空 | ~~Basic Auth 密码~~ |

当前配置方式：electron-store `server` 字段（url/username/password），设置面板中可直接编辑。

### 构建 & 打包

```bash
bun run build                  # 构建
bun run package                # 打包当前平台
bun run package:mac            # macOS dmg
bun run package:win            # Windows nsis
bun run package:linux          # Linux AppImage + deb
```

## 10. 参考资源

### 参考项目

| 项目 | 技术栈 | Stars | 说明 |
|------|--------|-------|------|
| [bongo-cat-next](https://github.com/liwenka1/bongo-cat-next) | Tauri 2 + Next.js + TS | 108 | Live2D 猫咪桌面宠物，最佳参考 |
| [chatgpt-desktopPet](https://github.com/kirbystudy/chatgpt-desktopPet) | Electron + JS | 86 | Electron + Live2D + ChatGPT |
| [AI-Girlfriend-Desktop-Pet](https://github.com/DD-MASTERT/AI-Girlfriend-Desktop-Pet) | Python | 205 | Python Live2D 桌面宠物 |
| [Eikanya/Live2d-model](https://github.com/Eikanya/Live2d-model) | — | 3100+ | 大量游戏提取的 Live2D 模型 |

## 11. 代码约定

遵循 OpenCode monorepo 的 AGENTS.md 风格指南：

- 渲染进程只通过 `window.pet` API（preload bridge）与主进程通信
- 主进程 IPC 注册集中在 `src/main/index.ts` 的 `registerIpcHandlers()` 函数中
- v4 新增宠物状态需同步修改 7 个文件：`preload/types.ts` → `renderer/types.ts`（类型）→ `main/index.ts`（事件映射+Activity 追踪+Reaction 机制）→ `pet/motion-map.ts`（动作映射）→ `pet/renderer.ts`（播放策略+循环间隔）→ `main.ts`（UI 标签+气泡文案+冷却队列+Reaction 回退）→ `index.html`（设置面板按钮）。共 17 种状态。
- 使用 `const` 优于 `let`，三元/early return 优于 reassignment
- 避免不必要的解构，使用 dot notation
- `type` 别名而非 `interface`（除非需要 extends）
- 不要使用 `-webkit-app-region: drag`（会吞掉右键事件），用 JS + IPC 实现拖拽
- `model.internalModel.motionManager.motionGroups` 获取动作列表（不是 `model.settings.motions`）
- 每种状态的动作列表在 `pet/motion-map.ts` 中维护为固定数组，运行时过滤模型实际存在的动作后随机选取
- `model.width` 不是原始尺寸（返回 `scale.x × localBounds.width`），始终使用存储的 `originalModelWidth`/`originalModelHeight`
- 短暂情绪状态的回退计时器需检查 `pendingState` 是否存在：有则跳过回退（冷却到期会处理），无则正常回退 idle
- 冷却队列（`setPetState`）：每次状态变更后冷却期（Activity 间 500ms，Reaction 3500ms），期间新状态写入 `pendingState`（后写入覆盖），到期后只应用最后一个；相同状态不缓冲
- 主进程状态追踪简化：只维护 `activityState`（Reaction 回退目标）和 `currentPetState`。已移除 `hasReceivedDelta`（每个 delta 直接触发 generating）和 `permissionPending`（未使用）
- 无独立状态机：OpenCode SSE 事件直传，主进程每个事件直接映射对应状态（最新事件胜出），渲染进程负责冷却队列去重 + 超时回退
