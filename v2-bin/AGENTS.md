# v2-bin

V2 CLI 的运行时容器镜像。将预编译的 `opencode` 二进制和运行时依赖
(bash、git、ripgrep、ffmpeg、openssh-client)打包进 `debian:trixie`。

支持多架构(amd64 + arm64),构建为单一 manifest list,amd64 + arm64 两个架构
共用 `latest` tag(V2 已转正,不再使用版本 tag;registry 上的 `v2` tag
是历史遗留,已停更)。

> 基础镜像必须写全限定名 `docker.io/library/debian:trixie`,不能用短名
> `debian:trixie`:本机 `~/.config/containers/registries.conf` 只配置了
> docker.io 镜像加速,没有 `unqualified-search-registries`,短名在需要
> 联网拉取的架构(如本地无 arm64 缓存)时会报 "short-name ... did not
> resolve to an alias"。

> V2 转正后 CLI 二进制已由 `opencode2` 改名为 `opencode`
> (commit `bb564f96a3 feat(cli): rename command to opencode`)。npm `bin`、
> install 脚本、`packages/cli/Dockerfile` 等上游仍保留 `opencode2` 作为
> legacy 别名;本镜像只安装正式名 `opencode`,容器内没有 `opencode2` 命令。

## 目录结构

- `Dockerfile.v2` - 镜像定义;构建上下文就是本目录
- `opencode-amd64` - x86_64 编译后的 CLI 二进制(已 gitignore)
- `opencode-arm64` - aarch64 编译后的 CLI 二进制(已 gitignore)
- `AGENTS.md` - 本文件

Dockerfile 通过 `ARG TARGETARCH` 自动选择对应架构的二进制(`TARGETARCH`
由 `--platform` 注入,值为 `amd64` 或 `arm64`)。

## 准备二进制

**每次构建前都必须重新复制**，即使目录中已有旧二进制也要覆盖，确保镜像打包的是最新构建产物。源文件位于 `packages/cli/dist/`：

```sh
cp packages/cli/dist/cli-linux-x64/bin/opencode   v2-bin/opencode-amd64
cp packages/cli/dist/cli-linux-arm64/bin/opencode v2-bin/opencode-arm64
```

## 构建多架构镜像(manifest list)

主机需启用 qemu-user-static(binfmt),用于 arm64 的 `RUN` 指令:

```sh
podman build \
  -f Dockerfile.v2 \
  --platform linux/amd64,linux/arm64 \
  --manifest docker.io/heimoshuiyu/opencode:latest \
  v2-bin/
```

Dockerfile 文件名为 `Dockerfile.v2`(非默认名),Podman 不会自动识别,
**必须用 `-f Dockerfile.v2` 指定**,否则报 "no Containerfile or Dockerfile found"。

注意事项(均为 Podman 已知行为):

- 多平台构建**必须用 `--manifest`**,不能用 `-t/--tag`。`--tag` 配合多个
  `--platform` 只会静默产生单架构镜像(见 containers/podman#27211)。
- 若本地已存在同名 tag 的**单架构**镜像(旧构建残留),`--manifest` 构建在
  两个平台都 commit 之后仍会报 "image is not a manifest list"。先执行
  `podman rmi docker.io/heimoshuiyu/opencode:latest` 删除旧 tag 再构建。
- arm64 的 `RUN apt-get install` 步骤通过 qemu 透明执行。

## 推送 manifest list

本机直连 Docker Hub(registry-1.docker.io)会被网络重置,mirror 只加速拉取、
对推送无效,推送必须走 HTTP 代理(验证方式:`curl -x http://100.64.0.17:3128
https://registry-1.docker.io/v2/` 返回 401 即通):

```sh
HTTPS_PROXY=http://100.64.0.17:3128 \
podman manifest push --all \
  docker.io/heimoshuiyu/opencode:latest \
  docker://docker.io/heimoshuiyu/opencode:latest
```

- **必须用 `manifest push --all`**,普通 `podman push` 只推送当前架构。
- 代理地址 `100.64.0.17:3128` 是 Tailscale 内网地址,不可用时先确认
  Tailscale 连通性或向机主询问新的代理地址。

## 验证

```sh
podman manifest inspect docker.io/heimoshuiyu/opencode:latest \
  | jq -r '.manifests[].platform'
```

应输出 `linux/amd64` 和 `linux/arm64` 两项。若怀疑 inspect
读到的是本地缓存,可改用 registry API 实测(需代理):

```sh
token=$(curl -sS -x http://100.64.0.17:3128 \
  "https://auth.docker.io/token?service=registry.docker.io&scope=repository:heimoshuiyu/opencode:pull" \
  | jq -r .token)
curl -sS -x http://100.64.0.17:3128 \
  -H "Authorization: Bearer $token" \
  -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json" \
  "https://registry-1.docker.io/v2/heimoshuiyu/opencode/manifests/latest" \
  | jq -r '.manifests[] | "\(.platform.os)/\(.platform.architecture)  \(.digest)"'
```
