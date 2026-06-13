package com.opencode.app.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.repository.ServerRepository
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.ServerState
import com.opencode.app.domain.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerListUiState(
    val servers: List<ServerConfig> = emptyList(),
    val states: Map<String, ServerState> = emptyMap(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    val uiState: StateFlow<ServerListUiState> = combine(
        serverRepository.observeServers(),
        serverRepository.observeServerStates(),
    ) { servers, states ->
        servers.forEach { connectionManager.registerConfig(it) }
        ServerListUiState(servers = servers, states = states)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerListUiState())

    fun connect(server: ServerConfig) {
        viewModelScope.launch {
            connectionManager.registerConfig(server)
            connectionManager.updateState(server.id, ServerStatus.CONNECTING)
            try {
                val conn = connectionManager.connect(server)
                val result = conn.healthApi.checkHealth()
                if (result.isSuccessful) {
                    connectionManager.updateState(
                        server.id, ServerStatus.CONNECTED,
                        version = result.body()?.version,
                    )
                } else {
                    connectionManager.updateState(
                        server.id, ServerStatus.ERROR,
                        error = "HTTP ${result.code()}",
                    )
                }
            } catch (e: Exception) {
                connectionManager.updateState(
                    server.id, ServerStatus.ERROR,
                    error = e.message ?: "Connection failed",
                )
            }
        }
    }

    fun disconnect(serverId: String) {
        connectionManager.disconnect(serverId)
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.deleteServer(serverId)
        }
    }
}
