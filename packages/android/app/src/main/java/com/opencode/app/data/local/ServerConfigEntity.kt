package com.opencode.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val username: String,
    val hasPassword: Boolean,
    val createdAt: Long,
    val lastConnectedAt: Long?,
)

@Entity(
    tableName = "opened_directories",
    primaryKeys = ["serverId", "directory"],
)
data class OpenedDirectoryEntity(
    val serverId: String,
    val directory: String,
    val displayName: String,
    val openedAt: Long = System.currentTimeMillis(),
)
