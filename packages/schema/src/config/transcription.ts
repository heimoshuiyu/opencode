export * as ConfigTranscription from "./transcription.js"

import { Schema } from "effect"
import { optional } from "../schema.js"
import { Transcription } from "../transcription.js"

export interface Whisper extends Schema.Schema.Type<typeof Whisper> {}
export const Whisper = Schema.Struct({
  ...Transcription.Whisper.fields,
  url: Schema.String.pipe(optional).annotate({ description: "Whisper-compatible transcription endpoint" }),
  apiKey: Schema.String.pipe(optional).annotate({ description: "API key for the Whisper endpoint" }),
  allowed_urls: Schema.Array(Schema.String)
    .pipe(optional)
    .annotate({
      description: "Additional exact Whisper endpoint URLs clients may select. Keys are not shared between endpoints.",
    }),
}).annotate({ identifier: "Config.Transcription.Whisper" })

export interface Info extends Schema.Schema.Type<typeof Info> {}
export const Info = Schema.Struct({
  ...Transcription.Settings.fields,
  whisper: Whisper.pipe(optional).annotate({ description: "Whisper transcription settings" }),
  context_pairs: Transcription.ContextPairs.pipe(optional).annotate({
    description:
      "Number of recent user/assistant conversation pairs to include as transcription context (default: 3, maximum: 20)",
  }),
}).annotate({ identifier: "Config.Transcription" })
