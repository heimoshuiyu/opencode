const packages = new Set([
  "@ai-sdk/openai",
  "@ai-sdk/google",
  "@ai-sdk/openai-compatible",
  "@opencode/ai/providers/openai",
  "@opencode/ai/providers/google",
  "@opencode/ai/providers/openai-compatible",
])

/** The catalog picker and the service must accept the same audio protocols, including legacy package names. */
export function transcriptionModelIssue(model: { package?: string; capabilities: { input: readonly string[] } }) {
  if (!model.capabilities.input.some((mime) => mime.startsWith("audio"))) return "audio"
  const pkg = model.package?.replace(/^@opencode-ai\/ai\//, "@opencode/ai/").replace(/^aisdk:/, "")
  if (!pkg || !packages.has(pkg)) return "package"
}
