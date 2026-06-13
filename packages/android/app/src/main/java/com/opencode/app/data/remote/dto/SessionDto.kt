package com.opencode.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── Session ──

@Serializable
data class SessionDTO(
    val id: String,
    val slug: String = "",
    @SerialName("projectID") val projectId: String = "",
    val directory: String = "",
    val title: String = "",
    val agent: String? = null,
    val model: ModelRefDTO? = null,
    val cost: Double = 0.0,
    val tokens: SessionTokensDTO? = null,
    val share: SessionShareDTO? = null,
    val time: SessionTimeDTO? = null,
    @SerialName("parentID") val parentId: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ModelRefDTO(
    val id: String = "",
    @SerialName("providerID") val providerId: String = "",
    @SerialName("modelID") val modelId: String? = null,
    val variant: String? = null,
)

@Serializable
data class SessionTokensDTO(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val total: Long = 0,
    val cache: CacheTokensDTO? = null,
)

@Serializable
data class CacheTokensDTO(val read: Long = 0, val write: Long = 0)

@Serializable
data class SessionShareDTO(val url: String)

@Serializable
data class SessionTimeDTO(
    val created: Long = 0,
    val updated: Long = 0,
    val compacting: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class SessionStatusDTO(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null,
)

@Serializable
data class CreateSessionRequest(
    @SerialName("projectID") val projectId: String? = null,
    val directory: String? = null,
    val agent: String? = null,
    val model: ModelRefDTO? = null,
)

@Serializable
data class PatchSessionRequest(
    val title: String? = null,
    val archived: Long? = null,
)

// ── Message ──

@Serializable
data class MessageDTO(
    val info: MessageInfoDTO? = null,
    val parts: List<PartDTO> = emptyList(),
)

@Serializable
data class MessageInfoDTO(
    val id: String = "",
    @SerialName("sessionID") val sessionId: String = "",
    val role: String = "assistant",
    val time: MessageTimeDTO? = null,
    val model: ModelRefDTO? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val agent: String? = null,
    val cost: Double = 0.0,
    val tokens: SessionTokensDTO? = null,
    val error: ErrorDTO? = null,
    @SerialName("parentID") val parentId: String? = null,
    val mode: String? = null,
    val finish: String? = null,
)

@Serializable
data class MessageTimeDTO(
    val created: Long = 0,
    val completed: Long? = null,
)

@Serializable
data class ErrorDTO(
    val type: String? = null,
    val message: String? = null,
)

// ── Part (polymorphic via type discriminator) ──

@Serializable
data class PartDTO(
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    @SerialName("callID") val callId: String? = null,
    val state: ToolStateDTO? = null,
    val mime: String? = null,
    val url: String? = null,
    val name: String? = null,
    val agent: String? = null,
    val description: String? = null,
    val prompt: String? = null,
    val cost: Double? = null,
    val tokens: SessionTokensDTO? = null,
    val snapshot: JsonElement? = null,
    val files: List<String>? = null,
    val hash: String? = null,
    val reason: String? = null,
    val time: PartTimeDTO? = null,
)

@Serializable
data class PartTimeDTO(
    val start: Long = 0,
    val end: Long? = null,
)

@Serializable
data class ToolStateDTO(
    val status: String,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val error: String? = null,
    val title: String? = null,
    val metadata: JsonObject? = null,
    val time: ToolTimeDTO? = null,
)

@Serializable
data class ToolTimeDTO(val start: Long = 0, val end: Long? = null)

// ── Prompt request ──

@Serializable
data class PromptRequest(
    val parts: List<PromptPartDTO>,
    val model: ModelRefDTO? = null,
    val agent: String? = null,
)

@Serializable
data class PromptPartDTO(
    val type: String = "text",
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
)

// ── Voice transcription ──

@Serializable
data class TranscribeRequestDTO(
    val audio: String,
    val mime: String,
    val prompt: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val voice: VoiceConfigDTO? = null,
)

@Serializable
data class VoiceConfigDTO(
    val type: String = "lalm",
    val lalm: VoiceLalmDTO? = null,
)

@Serializable
data class VoiceLalmDTO(
    val model: VoiceModelRefDTO? = null,
)

@Serializable
data class VoiceModelRefDTO(
    @SerialName("providerID") val providerId: String = "",
    @SerialName("modelID") val modelId: String = "",
)

@Serializable
data class TranscribeResponseDTO(
    val text: String = "",
)

// ── File listing (for directory autocomplete) ──

@Serializable
data class FileEntryDTO(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "file",
)

// ── Permission ──

@Serializable
data class PermissionRequestDTO(
    val id: String = "",
    @SerialName("sessionID") val sessionId: String = "",
    val permission: String = "",
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
)

@Serializable
data class PermissionReplyDTO(
    val reply: String,
    val message: String? = null,
)

@Serializable
data class PathResponseDTO(
    val home: String = "",
    val worktree: String = "",
    val directory: String = "",
)
