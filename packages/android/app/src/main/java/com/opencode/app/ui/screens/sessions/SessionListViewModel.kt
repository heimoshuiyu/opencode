package com.opencode.app.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.repository.SessionRepository
import com.opencode.app.data.session.SessionStore
import com.opencode.app.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionListUiState(
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val directory: String = "/",
    val directoryInput: String = "/",
    val workingSessions: Set<String> = emptySet(),
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val connectionManager: ServerConnectionManager,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    private var currentServerId: String? = null

    fun load(serverId: String, directory: String) {
        currentServerId = serverId
        _uiState.value = _uiState.value.copy(directory = directory, directoryInput = directory)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            connectionManager.getConnection(serverId)?.setDirectory(directory)
            sessionRepository.fetchSessions(serverId, directory = directory)
                .onSuccess { sessions ->
                    val working = sessionStore.getStatusMap(serverId).value.filter { it.value }.keys
                    _uiState.value = _uiState.value.copy(
                        sessions = sessions
                            .filter { it.parentSessionId == null } // hide sub-agent sessions
                            .sortedWith(
                                compareByDescending<Session> { it.id in working }
                                    .thenByDescending { it.updatedAt }
                            ),
                        isLoading = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
        }
        // Observe working sessions and re-sort when status changes
        viewModelScope.launch {
            sessionStore.getStatusMap(serverId).collect { statusMap ->
                val working = statusMap.filter { it.value }.keys
                _uiState.value = _uiState.value.copy(
                    workingSessions = working,
                    sessions = _uiState.value.sessions.sortedWith(
                        compareByDescending<Session> { it.id in working }
                            .thenByDescending { it.updatedAt }
                    ),
                )
            }
        }
        viewModelScope.launch {
            sessionStore.refreshStatusMap(serverId, directory)
        }
    }

    fun updateDirectoryInput(text: String) {
        _uiState.value = _uiState.value.copy(directoryInput = text)
    }

    fun applyDirectory(serverId: String) {
        val newDir = _uiState.value.directoryInput.trim().ifBlank { "/" }
        load(serverId, newDir)
    }

    suspend fun listSubdirectories(directory: String): List<String> {
        val serverId = currentServerId ?: return emptyList()
        val conn = connectionManager.getConnection(serverId) ?: return emptyList()
        return try {
            val response = conn.fileApi.listFiles(directory = directory)
            if (response.isSuccessful) {
                response.body()
                    ?.filter { it.type == "directory" }
                    ?.map { it.name }
                    ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun search(serverId: String, query: String) {
        val directory = _uiState.value.directory
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.isBlank()) {
            load(serverId, directory)
        } else {
            viewModelScope.launch {
                sessionRepository.fetchSessions(serverId, directory = directory, search = query)
                    .onSuccess { sessions ->
                        _uiState.value = _uiState.value.copy(
                            sessions = sessions.sortedByDescending { it.updatedAt },
                        )
                    }
            }
        }
    }

    fun createSession(serverId: String, onCreated: (Session) -> Unit) {
        val directory = _uiState.value.directory
        viewModelScope.launch {
            sessionRepository.createSession(serverId, directory = directory)
                .onSuccess { session -> onCreated(session) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    fun archiveSession(serverId: String, sessionId: String) {
        val directory = _uiState.value.directory
        viewModelScope.launch {
            sessionRepository.archiveSession(serverId, sessionId)
                .onSuccess { load(serverId, directory) }
        }
    }
}
