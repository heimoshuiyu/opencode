# v2-bin

V2 CLI 的运行时容器镜像。将预编译的 `opencode2` 二进制和运行时依赖
(bash、git、ripgrep、ffmpeg、openssh-client)打包进 `debian:trixie`。

支持多架构(amd64 + arm64),构建为单一 manifest list,两个架构共用同一 tag。

## 目录结构

- `Dockerfile.v2` - 镜像定义;构建上下文就是本目录
- `opencode2-amd64` - x86_64 编译后的 CLI 二进制(已 gitignore)
- `opencode2-arm64` - aarch64 编译后的 CLI 二进制(已 gitignore)
- `AGENTS.md` - 本文件

Dockerfile 通过 `ARG TARGETARCH` 自动选择对应架构的二进制(`TARGETARCH`
由 `--platform` 注入,值为 `amd64` 或 `arm64`)。

## 准备二进制

**每次构建前都必须重新复制**，即使目录中已有旧二进制也要覆盖，确保镜像打包的是最新构建产物。源文件位于 `packages/cli/dist/`：

```sh
cp packages/cli/dist/cli-linux-x64/bin/opencode2   v2-bin/opencode2-amd64
cp packages/cli/dist/cli-linux-arm64/bin/opencode2 v2-bin/opencode2-arm64
```

## 构建多架构镜像(manifest list)

主机需启用 qemu-user-static(binfmt),用于 arm64 的 `RUN` 指令:

```sh
podman build \
  -f Dockerfile.v2 \
  --platform linux/amd64,linux/arm64 \
  --manifest docker.io/heimoshuiyu/opencode:v2 \
  v2-bin/
```

Dockerfile 文件名为 `Dockerfile.v2`(非默认名),Podman 不会自动识别,
**必须用 `-f Dockerfile.v2` 指定**,否则报 "no Containerfile or Dockerfile found"。

注意事项(均为 Podman 已知行为):

- 多平台构建**必须用 `--manifest`**,不能用 `-t/--tag`。`--tag` 配合多个
  `--platform` 只会静默产生单架构镜像(见 containers/podman#27211)。
- arm64 的 `RUN apt-get install` 步骤通过 qemu 透明执行。

## 推送 manifest list

```sh
podman manifest push --all \
  docker.io/heimoshuiyu/opencode:v2 \
  docker://docker.io/heimoshuiyu/opencode:v2
```

- **必须用 `manifest push --all`**,普通 `podman push` 只推送当前架构。

## 验证

```sh
podman manifest inspect docker.io/heimoshuiyu/opencode:v2 \
  | jq -r '.manifests[].platform'
```

应输出 `linux/amd64` 和 `linux/arm64` 两项。
