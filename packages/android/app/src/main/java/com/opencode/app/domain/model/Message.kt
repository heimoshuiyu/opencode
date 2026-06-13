package com.opencode.app.domain.model

sealed class MessagePart {
    data class Text(val text: String) : MessagePart()

    data class Reasoning(val text: String, val startMs: Long = 0, val endMs: Long = 0) : MessagePart()

    data class Tool(
        val tool: String,
        val status: ToolStatus,
        val title: String? = null,
        val input: String? = null,
        val output: String? = null,
        val error: String? = null,
        val childSessionId: String? = null,
        val inputJson: String? = null,
        val metadataJson: String? = null,
        val callId: String? = null,
    ) : MessagePart()

    data class File(val mime: String, val url: String, val filename: String? = null) : MessagePart()

    data class StepStart(val agent: String? = null) : MessagePart()

    data class StepFinish(
        val cost: Double = 0.0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val reason: String? = null,
    ) : MessagePart()

    data class Agent(val name: String) : MessagePart()

    data class Subtask(val agent: String, val description: String, val prompt: String) : MessagePart()

    data class Compaction(val auto: Boolean = false) : MessagePart()

    data object Snapshot : MessagePart()

    data class Patch(val files: List<String>) : MessagePart()

    data class Retry(val attempt: Int, val error: String) : MessagePart()
}

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR }

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val createdAt: Long = 0,
    val completedAt: Long = 0,
    val parts: List<MessagePart> = emptyList(),
    val modelId: String? = null,
    val providerId: String? = null,
    val agent: String? = null,
    val mode: String? = null,
    val cost: Double = 0.0,
    val tokenTotal: Long = 0,
    val parentId: String? = null,
    val error: String? = null,
    val isStreaming: Boolean = false,
) {
    val textContent: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    val hasError: Boolean get() = error != null

    val visibleParts: List<MessagePart>
        get() = parts.filterNot {
            it is MessagePart.StepStart ||
            it is MessagePart.StepFinish ||
            (it is MessagePart.Tool && it.tool == "todowrite")
        }
}

enum class MessageRole { USER, ASSISTANT }
