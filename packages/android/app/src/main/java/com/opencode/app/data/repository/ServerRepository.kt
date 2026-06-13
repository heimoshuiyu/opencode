package com.opencode.app.data.repository

import com.opencode.app.data.local.OpenCodeDatabase
import com.opencode.app.data.local.SecureCredentialStore
import com.opencode.app.data.local.ServerConfigEntity
import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.mapper.toEntity
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.ServerState
import com.opencode.app.domain.model.ServerStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val db: OpenCodeDatabase,
    private val credentialStore: SecureCredentialStore,
    private val connectionManager: ServerConnectionManager,
) {
    fun observeServers(): Flow<List<ServerConfig>> =
        db.serverConfigDao().observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getServer(id: String): ServerConfig? =
        db.serverConfigDao().getById(id)?.toDomain()

    suspend fun addServer(name: String, url: String, username: String, password: String?): ServerConfig {
        val config = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url.trimEnd('/'),
            username = username.ifBlank { "opencode" },
            hasPassword = !password.isNullOrBlank(),
        )
        db.serverConfigDao().upsert(config.toEntity())
        if (!password.isNullOrBlank()) {
            credentialStore.savePassword(config.id, password)
        }
        connectionManager.registerConfig(config)
        return config
    }

    suspend fun updateServer(config: ServerConfig, password: String? = null) {
        db.serverConfigDao().upsert(config.toEntity())
        if (password != null) {
            if (password.isBlank()) {
                credentialStore.removePassword(config.id)
            } else {
                credentialStore.savePassword(config.id, password)
            }
        }
        connectionManager.disconnect(config.id)
        connectionManager.registerConfig(config)
    }

    suspend fun deleteServer(id: String) {
        connectionManager.unregisterConfig(id)
        credentialStore.removePassword(id)
        db.serverConfigDao().deleteById(id)
    }

    suspend fun testConnection(url: String, username: String, password: String?): Result<String> = runCatching {
        val tempConfig = ServerConfig(
            id = "temp",
            name = "temp",
            url = url.trimEnd('/'),
            username = username.ifBlank { "opencode" },
            hasPassword = !password.isNullOrBlank(),
        )
        if (!password.isNullOrBlank()) {
            credentialStore.savePassword("temp", password)
        }
        val conn = connectionManager.getOrCreateConnection(tempConfig)
        val health = conn.healthApi.checkHealth()
        if (!password.isNullOrBlank()) credentialStore.removePassword("temp")
        connectionManager.disconnect("temp")
        if (health.isSuccessful) {
            health.body()?.version ?: "unknown"
        } else {
            throw Exception("HTTP ${health.code()}: ${health.message()}")
        }
    }

    fun observeServerStates(): Flow<Map<String, ServerState>> = connectionManager.serverStates
}
