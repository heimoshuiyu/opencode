package com.opencode.app.domain.model

data class ServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "opencode",
    val hasPassword: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
)

enum class ServerStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class ServerState(
    val config: ServerConfig,
    val status: ServerStatus,
    val version: String? = null,
    val errorMessage: String? = null,
)
