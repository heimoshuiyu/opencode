import { Config } from "@opencode/core/config"
import { ConfigWriter } from "@opencode/core/config/writer"
import { Effect } from "effect"
import { HttpApiBuilder, HttpApiSchema } from "effect/unstable/httpapi"
import { InvalidRequestError } from "@opencode/protocol/errors"
import { Api } from "../api"

export const ConfigHandler = HttpApiBuilder.group(Api, "server.config", (handlers) =>
  handlers
    .handle("config.get", () => Config.Service.use((config) => config.entries()))
    .handle(
      "config.update",
      Effect.fn(function* (ctx) {
        const writer = yield* ConfigWriter.Service
        yield* writer.merge(ctx.payload).pipe(
          Effect.mapError((error) => new InvalidRequestError({ message: error.message, kind: "config_write" })),
        )
        return HttpApiSchema.NoContent.make()
      }),
    ),
)
