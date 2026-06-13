package com.opencode.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Route {
    @Serializable
    data object ServerList : Route()

    @Serializable
    data class AddServer(val serverId: String? = null) : Route()

    @Serializable
    data class ProjectList(val serverId: String) : Route()

    @Serializable
    data class SessionList(val serverId: String, val projectId: String, val directory: String) : Route()

    @Serializable
    data class Chat(val serverId: String, val sessionId: String, val directory: String) : Route()
}
