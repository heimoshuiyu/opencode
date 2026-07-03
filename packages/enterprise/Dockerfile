# Strategy B: host builds .output via `bun run build`, this image only runs it.
# Runtime: Nitro node-server preset, self-contained (node_modules traced into .output).
FROM node:22-slim

WORKDIR /app
COPY .output/ ./.output/

ENV HOST=0.0.0.0
ENV PORT=3002
EXPOSE 3002

CMD ["node", ".output/server/index.mjs"]
