# GitLab OpenCode Agent

在自建 GitLab 上运行的 AI 代码审查机器人。用户在 Merge Request 中 @opencode，Agent 自动审查代码并回复。

## 工作原理

```
用户在 MR 里评论 "@opencode review this"
        │
        ▼
 GitLab 发送 Note Hook Webhook (POST)
        │
        ▼
┌──────────────────────────────────────────────────┐
│  Agent 服务 (常驻 Docker 容器, 端口 3000)          │
│                                                   │
│  ① 验证 Webhook 签名                              │
│  ② 检查评论是否包含 @opencode                      │
│  ③ 检查评论者是否有 write 权限                     │
│  ④ 加入串行处理队列                                │
│  ⑤ 为该 MR 创建独立工作目录                        │
│  ⑥ git clone MR 源分支                             │
│  ⑦ 通过 GitLab API 拉取 MR diff + 评论             │
│  ⑧ 启动 opencode serve (本地 HTTP API)             │
│  ⑨ 通过 SDK 发送 prompt，AI 审查并修改代码          │
│  ⑩ 从 SSE 事件流中提取 AI 的文本回复               │
│  ⑪ 有代码改动则 git commit + push                  │
│  ⑫ 通过 GitLab API 将审查结果回写为 MR 评论        │
│  ⑬ 清理工作目录，处理下一个任务                    │
└──────────────────────────────────────────────────┘
```

### 核心设计

| 设计 | 选择 | 原因 |
|------|------|------|
| 运行方式 | 常驻服务 + 串行队列 | GitLab CI 不支持评论触发 pipeline，需要自己接收 Webhook |
| AI 调用 | `opencode serve` + SDK | 比 `opencode run --format json` 更可靠，SDK 直接管理会话 |
| 隔离 | 每任务独立工作目录 + 独立 serve 进程 | 避免会话状态污染 |
| 并发 | 串行处理 | 简单可靠，避免资源争抢（每个 opencode 实例约 500MB 内存） |

### 数据流详解

```
Webhook 到达
  │
  ├─ 验证 X-Gitlab-Token header
  ├─ 检查 object_kind == "note" && noteable_type == "MergeRequest"
  ├─ 检查评论内容包含 @opencode
  └─ 构造 MRTask 入队
       │
       ▼
  processMR():
    │
    ├─ 权限检查: GitLab API 查 member access_level >= 30
    ├─ 发评论: "👀 opencode is reviewing..."
    ├─ git clone --depth=50 --branch=源分支 (带 token 的 URL)
    ├─ git config user.name/email
    ├─ GitLab API: GET /merge_requests/:iid/changes → diff + 文件列表
    ├─ GitLab API: GET /merge_requests/:iid/notes    → 评论列表
    ├─ 拼接 prompt (MR 标题/描述/评论/diff + 用户指令)
    ├─ spawn opencode serve (localhost:4096)
    ├─ SDK: session.create() → sessionID
    ├─ SDK: session.promptAsync({ model, parts: [prompt] })
    ├─ SSE 流: 监听 /event → 等待 session.status == idle
    ├─ 从 SSE 事件中提取最后一个 type=="text" 的 part
    ├─ git status --porcelain → 有改动则 add + commit + push
    ├─ GitLab API: POST /merge_requests/:iid/notes → 审查评论
    ├─ kill opencode serve
    └─ rm -rf 工作目录
```

---

## 部署方式

### 方式一：Docker（推荐）

#### 从 GitHub Release 下载 opencode（适合 CI/CD 构建）

```bash
docker build -f Dockerfile.release \
  --build-arg OPENCODE_VERSION=0.50.0 \
  -t gitlab-opencode-agent .
```

不传 `OPENCODE_VERSION` 则安装最新版：

```bash
docker build -f Dockerfile.release -t gitlab-opencode-agent .
```

#### 使用本地已编译的 opencode 二进制

需要先用多阶段构建准备二进制：

```bash
# 假设你已经有 opencode 二进制在宿主机上
# Dockerfile 中 COPY --from=opencode-builder 要求你先用另一个阶段把二进制放进去
# 最简单的方式：把二进制复制到当前目录，修改 Dockerfile

cp /usr/local/bin/opencode ./opencode
```

然后修改 `Dockerfile` 第 9 行为：

```dockerfile
COPY opencode /usr/local/bin/opencode
```

构建：

```bash
docker build -t gitlab-opencode-agent .
```

#### 启动容器

```bash
docker run -d \
  --name gitlab-opencode-agent \
  -p 3000:3000 \
  -e ANTHROPIC_API_KEY=sk-ant-xxx \
  -e PROJECT_A_GITLAB_TOKEN=glpat-xxx \
  -e PROJECT_A_WEBHOOK_SECRET=your-secret \
  -v ./config.json:/app/config.json:ro \
  gitlab-opencode-agent
```

#### docker-compose

见同目录下的 `docker-compose.yml`。

### 方式二：本地运行

前提：已安装 [Bun](https://bun.sh) 和 `opencode`。

```bash
cd packages/gitlab-agent

# 安装依赖
bun install

# 复制并编辑配置
cp config.example.json config.json

# 设置环境变量
export ANTHROPIC_API_KEY=sk-ant-xxx
export PROJECT_A_GITLAB_TOKEN=glpat-xxx
export PROJECT_A_WEBHOOK_SECRET=your-secret

# 启动
bun run src/index.ts
```

---

## 连接自建 GitLab

### 第一步：创建 Project Access Token

在你的 GitLab 项目中：

1. 进入 **Settings → Access Tokens**
2. 创建一个新 Token：
   - **Name**: `opencode-agent`
   - **Role**: `Maintainer`（需要写评论和 push 代码）
   - **Scopes**: 勾选 `api`、`write_repository`
3. 复制生成的 `glpat-xxx` token

> 如果你想用一个 Token 管理多个项目，可以用 Group Access Token 或 Personal Access Token。

### 第二步：配置 Webhook

在每个需要 Agent 的项目中：

1. 进入 **Settings → Webhooks**
2. 点击 **Add new webhook**
3. 填写：
   - **URL**: `http://你的服务器IP:3000/webhook/group%2Fproject-name`
     - 注意：`group/project` 中的 `/` 要 URL 编码为 `%2F`
     - 例如项目路径是 `dev-team/backend-api`，URL 就是 `http://10.0.0.5:3000/webhook/dev-team%2Fbackend-api`
   - **Secret token**: 自定义一个密钥字符串（要和 config.json 中的一致）
   - **Trigger**: 只勾选 **Comment events**
   - 取消其他所有 Trigger
   - **SSL verification**: 如果 GitLab 用自签名证书，取消勾选（仅测试环境）
4. 保存

### 第三步：编写 config.json

```bash
cp config.example.json config.json
```

```json
{
  "port": 3000,
  "workspaceBase": "/tmp/opencode-workspaces",
  "opencodeModel": "anthropic/claude-sonnet-4-20250514",
  "mention": "@opencode",
  "auth": {
    "anthropic": {
      "type": "api",
      "key": "$ANTHROPIC_API_KEY"
    }
  },
  "projects": {
    "dev-team/backend-api": {
      "gitlabUrl": "https://gitlab.yourcompany.com",
      "gitlabToken": "$BACKEND_API_GITLAB_TOKEN",
      "webhookSecret": "$BACKEND_API_WEBHOOK_SECRET"
    }
  }
}
```

> **注意**: `projects` 的 key 必须是 GitLab 项目的完整路径（`group/project`），要和 Webhook URL 中编码后的一致。

设置对应的环境变量：

```bash
export ANTHROPIC_API_KEY="sk-ant-xxxxxxxxxxxxx"
export BACKEND_API_GITLAB_TOKEN="glpat-xxxxxxxxxxxxx"
export BACKEND_API_WEBHOOK_SECRET="你自己定义的密钥"
```

### 第四步：使用

在任意 MR 中评论：

```
@opencode review this
```

Agent 会：
1. 回复 "👀 opencode is reviewing..."
2. 拉取代码和 MR 上下文
3. AI 分析并给出审查意见
4. 如果有代码修复，自动 commit 并 push
5. 将审查结果作为 MR 评论发回

也可以附带具体指令：

```
@opencode 这个函数有并发问题，帮我检查一下
```

---

## 配置参考

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `port` | number | 是 | HTTP 服务监听端口 |
| `workspaceBase` | string | 是 | 工作目录基路径，用于 clone 代码 |
| `opencodeModel` | string | 是 | AI 模型，格式 `provider/model` |
| `mention` | string | 是 | 触发关键词，默认 `@opencode` |
| `auth` | object | 是 | AI 提供商认证，格式同 opencode 的 `auth.json` |
| `auth.<provider>.type` | string | 是 | 认证类型，通常为 `api` |
| `auth.<provider>.key` | string | 是 | API Key，支持 `$ENV_VAR` 引用环境变量 |
| `projects` | object | 是 | 项目配置，key 为 GitLab 项目路径 |
| `projects.<slug>.gitlabUrl` | string | 是 | GitLab 实例地址 |
| `projects.<slug>.gitlabToken` | string | 是 | Project Access Token，支持 `$ENV_VAR` |
| `projects.<slug>.webhookSecret` | string | 是 | Webhook Secret Token，支持 `$ENV_VAR` |

**支持的 AI 提供商**: Anthropic、OpenAI、Google 等，与 opencode 支持的提供商一致。auth 字段的格式参考 [opencode 文档](https://opencode.ai/docs/providers)。

---

## API 端点

### `GET /health`

健康检查，返回队列状态。

```json
{
  "status": "ok",
  "queueLength": 0,
  "isProcessing": false,
  "currentTask": null
}
```

### `POST /webhook/:projectSlug`

接收 GitLab Note Hook。`:projectSlug` 为 URL 编码的项目路径。

- 验证 `X-Gitlab-Token` header
- 仅处理 MR 评论且包含 mention 关键词的事件
- 返回 `200` 并将任务加入队列

---

## 故障排查

### Webhook 触发但没有反应

1. 检查 GitLab 项目 **Settings → Webhooks** 中该 Webhook 的 **Recent Deliveries**，看 HTTP 响应码和返回内容
2. 确认 `X-Gitlab-Token` 和 config.json 中 `webhookSecret` 一致
3. 确认 Trigger 只勾选了 **Comment events**，不是 Merge Request events

### Agent 回复 "you don't have write permissions"

评论者需要在 GitLab 项目中有 Developer (30) 或更高权限。

### git clone 失败

Agent 使用 HTTPS + Project Access Token 进行 clone。确认：
- Token 有 `write_repository` scope
- GitLab 地址可以从容器内部访问（注意 Docker 网络和防火墙）
- 如果是私有 CA，需要在容器中安装证书

### AI 不回复或回复内容为空

1. 检查 `ANTHROPIC_API_KEY`（或其他 provider key）环境变量是否正确设置
2. 检查容器日志：`docker logs gitlab-opencode-agent`
3. opencode 二进制需要 `libgcc`、`libstdc++`、`ripgrep`，Dockerfile 已包含

### 自签名证书的 GitLab

在 `docker-compose.yml` 中添加：

```yaml
environment:
  NODE_TLS_REJECT_UNAUTHORIZED: "0"
```

或在 GitLab Webhook 配置中取消 **SSL verification**。
