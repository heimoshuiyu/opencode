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

使用 turbo 构建所有包和预编译二进制。需要指定 `OPENCODE_CHANNEL` 和 `OPENCODE_VERSION` 环境变量，使用 `--env-mode loose` 绕过 turbo strict 模式的环境变量过滤，并跳过 electron 桌面包的构建：

**重要**:
- `OPENCODE_VERSION` 应该与即将创建的 tag 名称一致（即上一个 tag 加 `-hmsy` 后缀）
- 例如上一个 tag 是 `v1.1.35`，则 `OPENCODE_VERSION=v1.1.35-hmsy`

```bash
PREV_TAG=$(git describe --tags --abbrev=0)
NEW_TAG="${PREV_TAG}-hmsy"
OPENCODE_CHANNEL=local OPENCODE_VERSION="$NEW_TAG" bun turbo build --env-mode loose --filter='!@opencode-ai/desktop-electron' --filter='!@opencode-ai/desktop'
```

这会：
- 构建 SDK、Web UI、Plugin 等依赖包
- 在 `./packages/opencode/dist` 目录生成预编译二进制文件
- 跳过 `@opencode-ai/desktop-electron` 和 `@opencode-ai/desktop`（Tauri）的构建，因为它们需要额外依赖且在本地 Linux 环境下无法构建

### 2. 查找构建产物

列出所有可用的预编译平台：

```bash
ls ./packages/opencode/dist/
```

典型的平台包括：
- Linux: `opencode-linux-x64`, `opencode-linux-arm64`
- macOS: `opencode-darwin-x64`, `opencode-darwin-arm64`
- Windows: `opencode-windows-x64`

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

创建新版本标签（在上一个 tag 后加上 `-hmsy`）：

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

### 构建和分析
```bash
PREV_TAG=$(git describe --tags --abbrev=0)
NEW_TAG="${PREV_TAG}-hmsy"
OPENCODE_CHANNEL=local OPENCODE_VERSION="$NEW_TAG" bun turbo build --env-mode loose --filter='!@opencode-ai/desktop-electron' --filter='!@opencode-ai/desktop'
git describe --tags --abbrev=0
git log $(git describe --tags --abbrev=0)..HEAD --oneline
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
3. **动态 Commits**: 每次执行时，从实际的上一个 tag 到现在的 commits 都会不同，需要实时分析
4. **权限问题**: 如果无法推送到 `origin`，使用 `hmsy` 远程仓库
5. **CloudFront**: Invalidation 通常需要几分钟才能完成
6. **并行处理**: 代码分析阶段可以并发启动多个 sub-agents 以提高效率

## 资源

- S3 Bucket: `s3://opencode-hmsy`
- CloudFront Distribution: `E30UYS44QZ0UX4`
- CloudFront URL: https://d3ir6x3lfy3u68.cloudfront.net
- GitHub Repository: heimoshuiyu/opencode
