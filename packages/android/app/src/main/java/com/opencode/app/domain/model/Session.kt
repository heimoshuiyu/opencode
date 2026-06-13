package com.opencode.app.domain.model

data class Session(
    val id: String,
    val title: String,
    val projectId: String,
    val directory: String,
    val agent: String? = null,
    val modelId: String? = null,
    val modelProvider: String? = null,
    val cost: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val shareUrl: String? = null,
    val isArchived: Boolean = false,
    val parentSessionId: String? = null,
)

enum class SessionRunStatus { IDLE, BUSY, RETRY }

data class SessionStatusEntry(
    val sessionId: String,
    val status: SessionRunStatus,
    val retryAttempt: Int = 0,
    val retryMessage: String? = null,
)
