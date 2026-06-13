package com.opencode.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Provider API response: { all: [...], default: {...}, connected: [...] } ──

@Serializable
data class ProviderListResponseDTO(
    val all: List<ProviderDTO> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class ProviderDTO(
    val id: String,
    val name: String = "",
    val models: Map<String, ModelDTO> = emptyMap(),
)

@Serializable
data class ModelDTO(
    val id: String = "",
    val name: String = "",
    @SerialName("providerID") val providerId: String = "",
    val attachment: Boolean = false,
    val reasoning: Boolean = false,
    val status: String = "active",
)

// ── Agent ──

@Serializable
data class AgentDTO(
    val name: String,
    val description: String = "",
    val mode: String = "all",
    val native: Boolean = false,
    val hidden: Boolean = false,
    val color: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
)

// ── Project ──

@Serializable
data class ProjectDTO(
    val id: String,
    val worktree: String = "",
    val vcs: String? = null,
    val name: String? = null,
    val icon: ProjectIconDTO? = null,
    val time: ProjectTimeDTO? = null,
)

@Serializable
data class ProjectIconDTO(
    val url: String? = null,
    val override: String? = null,
    val color: String? = null,
)

@Serializable
data class ProjectTimeDTO(
    val created: Long = 0,
    val updated: Long = 0,
    val initialized: Long? = null,
)

// ── Config ──

@Serializable
data class ConfigDTO(
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    val provider: JsonObject? = null,
    val username: String? = null,
)

// ── Health ──

@Serializable
data class HealthDTO(
    val healthy: Boolean = false,
    val version: String? = null,
)

// ── Todo ──

@Serializable
data class TodoDTO(
    val id: String,
    val content: String,
    val status: String,
    val priority: String,
)

// ── Permission (defined in SessionDto.kt) ──
