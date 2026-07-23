export * as Voice from "./voice.js"

import { Schema } from "effect"
import { optional } from "./schema.js"

export const Backend = Schema.Literals(["whisper", "lalm"]).annotate({ identifier: "Voice.Backend" })
export type Backend = typeof Backend.Type

export interface Whisper extends Schema.Schema.Type<typeof Whisper> {}
export const Whisper = Schema.Struct({
  model: Schema.String.pipe(optional).annotate({ description: "Whisper model name" }),
  language: Schema.String.pipe(optional).annotate({ description: "Whisper language code" }),
}).annotate({ identifier: "Voice.Whisper" })

export interface Lalm extends Schema.Schema.Type<typeof Lalm> {}
export const Lalm = Schema.Struct({
  model: Schema.String.pipe(optional).annotate({
    description: "Model to use for audio transcription in the format of provider/model",
  }),
  system: Schema.String.pipe(optional).annotate({ description: "Large Audio Language Model system prompt" }),
  instruction: Schema.String.pipe(optional).annotate({
    description: "Instruction text appended after the audio content to guide transcription behavior",
  }),
  audio_input_format: Schema.Literals(["input_audio", "audio_url"]).pipe(optional).annotate({
    description: 'Audio input format for OpenAI-compatible LLM APIs: "input_audio" or "audio_url"',
  }),
}).annotate({ identifier: "Voice.Lalm" })

export interface Settings extends Schema.Schema.Type<typeof Settings> {}
export const Settings = Schema.Struct({
  type: Backend.pipe(optional).annotate({ description: "Transcription backend" }),
  whisper: Whisper.pipe(optional).annotate({ description: "Whisper transcription settings" }),
  lalm: Lalm.pipe(optional).annotate({ description: "LALM transcription settings" }),
  hot_words: Schema.Array(Schema.String).pipe(optional).annotate({
    description: "Hot words to improve transcription accuracy",
  }),
}).annotate({ identifier: "Voice.Settings" })
