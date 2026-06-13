package com.opencode.app.data.remote

import com.opencode.app.data.local.SecureCredentialStore
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.ServerState
import com.opencode.app.domain.model.ServerStatus
import com.opencode.app.domain.model.SseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConnectionManager @Inject constructor(
    private val credentialStore: SecureCredentialStore,
) {
    private val connections = mutableMapOf<String, ServerConnection>()

    private val _serverStates = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, ServerState>> = _serverStates.asStateFlow()

    fun getConnection(serverId: String): ServerConnection? = connections[serverId]

    fun getOrCreateConnection(config: ServerConfig): ServerConnection {
        return connections.getOrPut(config.id) {
            val password = if (config.hasPassword) credentialStore.getPassword(config.id) else null
            ServerConnection(config, password)
        }
    }

    fun connect(config: ServerConfig): ServerConnection {
        val conn = getOrCreateConnection(config)
        updateState(config.id, ServerStatus.CONNECTED)
        return conn
    }

    fun disconnect(serverId: String) {
        connections[serverId]?.disconnectSafely()
        connections.remove(serverId)
        updateState(serverId, ServerStatus.DISCONNECTED)
    }

    fun disconnectAll() {
        connections.values.forEach { it.disconnectSafely() }
        connections.clear()
        _serverStates.value = emptyMap()
    }

    fun updateState(serverId: String, status: ServerStatus, version: String? = null, error: String? = null) {
        val current = _serverStates.value
        val existing = current[serverId]
        val config = existing?.config ?: connections[serverId]?.config
        if (config == null) {
            _serverStates.value = current - serverId
            return
        }
        val state = ServerState(
            config = config,
            status = status,
            version = version ?: existing?.version,
            errorMessage = error ?: existing?.errorMessage,
        )
        _serverStates.value = current + (serverId to state)
    }

    fun registerConfig(config: ServerConfig) {
        if (_serverStates.value.containsKey(config.id)) return
        val state = ServerState(config = config, status = ServerStatus.DISCONNECTED)
        _serverStates.value = _serverStates.value + (config.id to state)
    }

    fun unregisterConfig(serverId: String) {
        disconnect(serverId)
        _serverStates.value = _serverStates.value - serverId
    }

    private fun ServerConnection.disconnectSafely() {
        disconnect()
    }

    fun allEvents(): Flow<SseEvent> {
        return merge(*connections.values.map { it.events }.toTypedArray())
    }
}
