package com.opencode.app.domain.model

data class Provider(
    val id: String,
    val name: String,
    val models: List<ModelInfo> = emptyList(),
)

data class ModelInfo(
    val id: String,
    val name: String,
    val providerId: String,
    val attachmentSupported: Boolean = false,
    val reasoningSupported: Boolean = false,
)

data class AgentInfo(
    val name: String,
    val description: String,
    val mode: String = "all",
    val isBuiltIn: Boolean = true,
)

data class Todo(
    val id: String,
    val content: String,
    val status: String,
    val priority: String,
)
