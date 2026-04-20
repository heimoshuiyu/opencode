FROM oven/bun:1 AS build
WORKDIR /app
COPY sdk/ /tmp/sdk/
COPY package.json ./
RUN sed -i 's|"@opencode-ai/sdk": "workspace:\*"|"@opencode-ai/sdk": "file:/tmp/sdk"|' package.json \
    && sed -i 's|"cross-spawn": "catalog:"|"cross-spawn": "^7.0.6"|' /tmp/sdk/package.json \
    && bun install --production

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ripgrep git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY opencode /usr/local/bin/opencode
RUN chmod +x /usr/local/bin/opencode && opencode --version

WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./
COPY --from=build /usr/local/bin/bun /usr/local/bin/bun
COPY src/ ./src/

RUN mkdir -p /tmp/opencode-workspaces

EXPOSE 3000

CMD ["bun", "run", "src/index.ts"]
