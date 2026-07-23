export * as ConfigVoice from "./voice.js"

import { Schema } from "effect"
import { optional } from "../schema.js"
import { Voice } from "../voice.js"

const ContextPairs = Schema.Int.check(Schema.isBetween({ minimum: 1, maximum: 20 }))

export interface Whisper extends Schema.Schema.Type<typeof Whisper> {}
export const Whisper = Schema.Struct({
  url: Schema.String.pipe(optional).annotate({ description: "Whisper API URL" }),
  apiKey: Schema.String.pipe(optional).annotate({ description: "Whisper API key" }),
  model: Schema.String.pipe(optional).annotate({ description: "Whisper model name" }),
  language: Schema.String.pipe(optional).annotate({ description: "Whisper language code" }),
}).annotate({ identifier: "Config.Voice.Whisper" })

export const Lalm = Voice.Lalm
export type Lalm = Voice.Lalm

export interface Info extends Schema.Schema.Type<typeof Info> {}
export const Info = Schema.Struct({
  type: Voice.Backend.pipe(optional).annotate({ description: "Transcription provider type" }),
  whisper: Whisper.pipe(optional).annotate({ description: "Whisper transcription settings" }),
  lalm: Lalm.pipe(optional).annotate({
    description: "Large Audio Language Model transcription settings",
  }),
  hot_words: Schema.Array(Schema.String).pipe(optional).annotate({
    description: "Hot words to improve transcription accuracy for domain-specific terms",
  }),
  context_pairs: ContextPairs.pipe(optional).annotate({
    description:
      "Number of recent user/assistant conversation pairs to include as transcription context (default: 3, maximum: 20)",
  }),
}).annotate({ identifier: "Config.Voice" })
