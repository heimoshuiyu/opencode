---
name: opencode-release
description: OpenCode 完整版本发布流程：构建、压缩二进制、分析代码改进、创建 GitHub Release、部署到 CloudFront
---

## 我做什么

提供完整的 OpenCode 版本发布工作流程，包括构建项目、压缩多平台二进制文件、分析代码变更、创建 GitHub Release 和部署到 CloudFront。

## 何时使用我

在发布新版本的 OpenCode 时使用此技能，需要：
1. 构建所有平台预编译二进制文件
2. 压缩二进制文件用于分发
3. 分析从上一个 tag 到现在的代码改进
4. 创建包含详细 Release Notes 的 GitHub Release
5. 部署 Web 前端到 CloudFront

## 前置条件

- 已安装并配置 AWS CLI
- 已安装并配置 GitHub CLI (`gh`)
- 拥有 S3 bucket (`s3://opencode-hmsy`) 的写权限
- 拥有 CloudFront distribution (`E30UYS44QZ0UX4`) 的管理权限
- 拥有 GitHub 仓库 (`heimoshuiyu/opencode`) 的推送权限
- 已安装 `zstd` 和 `7z` 命令行工具

## 步骤

### 1. 构建项目

直接调用 opencode 的构建脚本。该脚本内部会自动先构建 Web 前端（`packages/app`），然后用 `Bun.build({ compile })` 编译所有平台的独立二进制文件。

**重要**:
- `OPENCODE_CHANNEL` 必须设为 `prod`
- `OPENCODE_VERSION` 必须使用 npm registry 上已发布的 `@opencode-ai/plugin` 包所对应的版本号（不带 `v` 前缀），例如 `1.14.41`
- 运行时，opencode 会在每个 `.opencode` 插件目录下执行 `npm install @opencode-ai/plugin@<OPENCODE_VERSION>`，因此版本号必须与 npm 上的包版本完全一致，否则插件依赖安装会失败
- Tag 命名仍然使用 `-hmsy` 后缀（如 `v1.14.41-hmsy`），但 `OPENCODE_VERSION` 不带此后缀

**不要使用 `bun turbo build`**：上游 CI 从不使用 turbo 做整体构建。`turbo.json` 中 `build` 任务的 `dependsOn` 为空（无拓扑排序），turbo 会并行触发所有包的 build 脚本。其中 `@opencode-ai/sdk#build` 的 `clean: true` 会删除已提交的 `gen/` 文件，然后因循环依赖无法重新生成，导致所有依赖 SDK 的包构建失败。这与上游 CI 的做法一致——上游 `publish.yml` 直接调用 `./packages/opencode/script/build.ts` 和 `./packages/cli/script/build.ts`。

```bash
PREV_TAG=$(git describe --tags --abbrev=0)
# 从 tag 提取版本号（去掉 v 前缀），例如 v1.14.41 → 1.14.41
VERSION="${PREV_TAG#v}"

# 构建所有平台的 opencode 二进制
OPENCODE_CHANNEL=prod OPENCODE_VERSION="$VERSION" \
  ./packages/opencode/script/build.ts

# 或者仅构建当前平台（更快）
OPENCODE_CHANNEL=prod OPENCODE_VERSION="$VERSION" \
  ./packages/opencode/script/build.ts --single
```

**不要挂载 tmpfs 到 `packages/opencode/dist`**：构建脚本会执行 `rm -rf dist`，tmpfs 挂载点无法被删除，会导致 `rm: dist: Device or resource busy` 错误。构建产物直接写入磁盘即可（约 1.5GB）。

构建脚本的工作流程：
- 自动构建 Web 前端（`packages/app`，Vite）并将产物嵌入二进制
- 编译 12 个平台的独立二进制到 `./packages/opencode/dist/`
- 使用 `@opentui/solid/bun-plugin` 处理 SolidJS
- 对当前平台的二进制运行 smoke test（`--version`）

### 2. 查找构建产物

列出所有可用的预编译平台：

```bash
ls ./packages/opencode/dist/
```

典型的平台包括：
- Linux: `opencode-linux-x64`, `opencode-linux-arm64`, `opencode-linux-x64-baseline`
- Linux (musl): `opencode-linux-x64-musl`, `opencode-linux-arm64-musl`, `opencode-linux-x64-baseline-musl`
- macOS: `opencode-darwin-x64`, `opencode-darwin-arm64`, `opencode-darwin-x64-baseline`
- Windows: `opencode-windows-x64`, `opencode-windows-arm64`, `opencode-windows-x64-baseline`

### 3. 压缩二进制文件

#### Linux 和 macOS (ZSTD)

使用 ZSTD 最大压缩等级和多线程压缩：

```bash
# Linux x64
zstd -19 -T0 ./packages/opencode/dist/opencode-linux-x64/bin/opencode -o ./opencode-linux-x64.zst

# Linux arm64
zstd -19 -T0 ./packages/opencode/dist/opencode-linux-arm64/bin/opencode -o ./opencode-linux-arm64.zst

# macOS x64
zstd -19 -T0 ./packages/opencode/dist/opencode-darwin-x64/bin/opencode -o ./opencode-darwin-x64.zst

# macOS arm64
zstd -19 -T0 ./packages/opencode/dist/opencode-darwin-arm64/bin/opencode -o ./opencode-darwin-arm64.zst
```

#### Windows (7zip)

使用 7zip 打包成 ZIP 格式：

```bash
7z a -tzip opencode-windows-x64.zip ./packages/opencode/dist/opencode-windows-x64/bin/opencode.exe
```

**注意**: 只压缩二进制可执行文件，不需要打包整个目录。

### 4. 查找上一个 Tag

动态查找最近的 tag：

```bash
git describe --tags --abbrev=0
```

### 5. 列出相关 Commits

列出从上一个 tag 到 HEAD 的所有 commits：

```bash
git log $(git describe --tags --abbrev=0)..HEAD --oneline
```

### 6. 分析代码改进

获取详细的 commit 变更信息：

```bash
git log v1.1.35..HEAD --name-status --pretty=format:"COMMIT:%H%nAUTHOR:%an%nDATE:%ad%nMESSAGE:%s%n---" --numstat
```

**重要**: 每次执行时，`v1.1.35` 需要替换为实际的上一个 tag 名称。

建议将 commits 按功能分组，然后并发启动多个 sub-agents 分析：
- Voice 相关 commits
- Background jobs 相关 commits
- Bash 相关 commits
- Web 相关 commits
- Agent/Prompt 相关 commits
- Tool 相关和其他 commits

每个 sub-agent 应该：
1. 使用 `git show <commit-hash>` 查看详细代码变更
2. 分析变更的文件和具体代码修改
3. 理解 commit 的目的和改进点
4. 识别解决的问题或添加的功能

### 7. 创建 Release Notes

基于分析结果，生成详细的 Release Notes，包括：
- 主要功能改进
- 次要功能改进
- Bug 修复
- 代码质量提升
- 国际化改进
- 二进制下载表格

保存为 `release-notes.md` 文件。

### 8. 创建 Tag

**重要**: Tag 的命名规则是在原始 tag 名称后面加上 `-hmsy` 后缀。

首先获取上一个 tag 的名称：

```bash
PREV_TAG=$(git describe --tags --abbrev=0)
echo "Previous tag: $PREV_TAG"
```

创建新版本标签（在上一个 tag 后加上 `-hmsy`，注意 Tag 带 `v` 前缀，与 `OPENCODE_VERSION` 不同）：

```bash
NEW_TAG="${PREV_TAG}-hmsy"
git tag $NEW_TAG
echo "Created tag: $NEW_TAG"
```

推送到远程仓库：

```bash
git push hmsy $NEW_TAG
```

**注意**:
- 新 tag 名称格式：`<原始tag名称>-hmsy`（例如：`v1.1.35-hmsy`）
- 如果推送到 `origin` (sst/opencode) 失败（权限问题），推送到 `hmsy` 仓库

### 9. 创建 GitHub Release

使用 GitHub CLI 创建 release 并上传二进制文件：

```bash
gh release create $NEW_TAG \
  --repo heimoshuiyu/opencode \
  --title "OpenCode $NEW_TAG - Version Title" \
  --notes-file release-notes.md \
  ./opencode-linux-x64.zst \
  ./opencode-linux-arm64.zst \
  ./opencode-darwin-x64.zst \
  ./opencode-darwin-arm64.zst \
  ./opencode-windows-x64.zip
```

### 10. 清理临时文件

删除创建的二进制压缩文件和文档：

```bash
rm -f ./opencode-linux-x64.zst \
       ./opencode-linux-arm64.zst \
       ./opencode-darwin-x64.zst \
       ./opencode-darwin-arm64.zst \
       ./opencode-windows-x64.zip \
       ./release-notes.md
```

### 11. 部署 Web 前端

使用 `web-s3-deploy` 技能构建并部署 Web 前端到 CloudFront：

1. 加载 `web-s3-deploy` 技能
2. 执行技能中的步骤：
   - 构建前端
   - 同步到 S3 bucket
   - 创建 CloudFront invalidation

**注意**: `web-s3-deploy` 技能包含了完整的部署流程和详细命令，不需要手动执行这些步骤。

### 12. 验证部署

通过以下地址验证部署：
- CloudFront HTTPS: https://d3ir6x3lfy3u68.cloudfront.net
- S3 Website: http://opencode-hmsy.s3-website-ap-southeast-1.amazonaws.com

## 命令速查

### 构建
```bash
PREV_TAG=$(git describe --tags --abbrev=0)
VERSION="${PREV_TAG#v}"

# 构建所有平台的 opencode 二进制
OPENCODE_CHANNEL=prod OPENCODE_VERSION="$VERSION" \
  ./packages/opencode/script/build.ts

# 或者仅构建当前平台
OPENCODE_CHANNEL=prod OPENCODE_VERSION="$VERSION" \
  ./packages/opencode/script/build.ts --single
```

### 压缩 (ZSTD)
```bash
zstd -19 -T0 <input> -o <output>.zst
```

### 压缩 (7zip)
```bash
7z a -tzip <output>.zip <input>.exe
```

### 发布
```bash
gh release create <tag> \
  --repo heimoshuiyu/opencode \
  --title "<title>" \
  --notes-file release-notes.md \
  <assets>...
```

### 部署 Web 前端
使用 `web-s3-deploy` 技能：
1. 加载技能: `skill web-s3-deploy`
2. 按照技能步骤执行部署

**注意**: 不需要手动执行部署命令，`web-s3-deploy` 技能包含了完整的构建、同步和 invalidation 流程。

## 注意事项

1. **动态 Tag**: 每次执行时都要使用 `git describe --tags --abbrev=0` 找到上一个 tag，不要硬编码版本号
2. **Tag 命名规则**: 新 tag 名称应该在上一个 tag 名称后面加上 `-hmsy` 后缀（例如：上一个 tag 是 `v1.1.35`，则新 tag 为 `v1.1.35-hmsy`）
3. **版本号对齐**: `OPENCODE_VERSION` 不带 `v` 前缀、不带 `-hmsy` 后缀（如 `1.14.41`），必须与 npm 上已发布的 `@opencode-ai/plugin` 包版本完全一致，否则运行时插件依赖安装会失败
4. **动态 Commits**: 每次执行时，从实际的上一个 tag 到现在的 commits 都会不同，需要实时分析
5. **权限问题**: 如果无法推送到 `origin`，使用 `hmsy` 远程仓库
6. **CloudFront**: Invalidation 通常需要几分钟才能完成
7. **并行处理**: 代码分析阶段可以并发启动多个 sub-agents 以提高效率
8. **不要使用 `bun turbo build`**: turbo 会并行触发 SDK build 的 `clean: true`，删掉已提交的 gen 文件后无法重新生成（循环依赖）。直接调用 `./packages/opencode/script/build.ts`
9. **不要挂载 tmpfs 到 dist**: 构建脚本会 `rm -rf dist`，tmpfs 挂载点无法删除

## 构建排错

### 错误：二进制中嵌入了旧的 Web 资源

**现象**：构建成功，但运行二进制后 Web UI 没有反映最新代码改动。

**原因**：opencode 的 build 脚本（`packages/opencode/script/build.ts`）内部会调用 `bun run --cwd packages/app build` 重新构建 Web UI 并通过 `import ... with { type: "file" }` 嵌入二进制。如果 `packages/app/dist` 目录中残留了旧的构建产物，可能影响嵌入结果。

**解决**：构建前清理旧的 app 产物：`rm -rf packages/app/dist`，然后重新构建。

### 错误：`Could not resolve "./gen/types.gen.js"`

**现象**：构建失败，报错 `Could not resolve "./gen/types.gen.js" from "../sdk/js/src/v2/client.ts"`。

**原因**：`packages/sdk/js/src/v2/gen/` 下的文件被删除或缺失。这些文件是提交在 git 中的（不是 gitignored），不应该被删除。常见原因是有人运行了 `bun turbo build`，触发了 SDK 的 build 脚本（`clean: true` 删除了 gen 文件），然后因循环依赖无法重新生成。

**解决**：从 git 恢复 gen 文件：`git checkout -- packages/sdk/js/src/v2/gen/`，然后用 `./packages/opencode/script/build.ts` 代替 turbo 构建。

### 错误：`rm: dist: Device or resource busy`

**现象**：构建 opencode 时失败，报错 `rm: dist: Device or resource busy`。

**原因**：`packages/opencode/dist` 上挂载了 tmpfs，构建脚本执行 `rm -rf dist` 时无法删除 tmpfs 挂载点本身。

**解决**：不要挂载 tmpfs 到 `packages/opencode/dist`。直接使用磁盘目录即可。

### 验证 Web 资源是否最新

构建完成后，比较时间戳确认二进制中嵌入了最新的 Web 资源：

```bash
# 检查 app dist 产物时间戳
stat -c '%Y %n' packages/app/dist/index.html

# 检查二进制时间戳（应晚于 app dist 产物）
stat -c '%Y %n' packages/opencode/dist/opencode-linux-x64/bin/opencode
```

也可以搜索二进制中的版本号特征字符串：

```bash
strings packages/opencode/dist/opencode-linux-x64/bin/opencode | grep -c "$NEW_TAG"
```

## 资源

- S3 Bucket: `s3://opencode-hmsy`
- CloudFront Distribution: `E30UYS44QZ0UX4`
- CloudFront URL: https://d3ir6x3lfy3u68.cloudfront.net
- GitHub Repository: heimoshuiyu/opencode
