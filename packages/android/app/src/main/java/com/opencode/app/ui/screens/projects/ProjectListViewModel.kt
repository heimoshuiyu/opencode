package com.opencode.app.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.app.data.local.OpenedDirectoryDao
import com.opencode.app.data.local.OpenedDirectoryEntity
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.repository.ServerRepository
import com.opencode.app.data.session.SessionStore
import com.opencode.app.domain.model.Project
import com.opencode.app.domain.model.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectListUiState(
    val server: ServerConfig? = null,
    val projects: List<ProjectDisplay> = emptyList(),
    val openedProjects: List<ProjectDisplay> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val directoryInput: String = "",
    val homeDirectory: String = "",
)

data class ProjectDisplay(
    val project: Project,
    val isWorking: Boolean,
)

@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
    private val openedDirectoryDao: OpenedDirectoryDao,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectListUiState())
    val uiState: StateFlow<ProjectListUiState> = _uiState.asStateFlow()

    private val _serverProjects = MutableStateFlow<List<Project>>(emptyList())
    private val _localProjects = MutableStateFlow<List<Project>>(emptyList())

    fun load(serverId: String) {
        viewModelScope.launch {
            val server = serverRepository.getServer(serverId) ?: return@launch
            _uiState.value = _uiState.value.copy(server = server, isLoading = true, error = null)

            // Observe session status for working indicators
            viewModelScope.launch {
                sessionStore.getStatusMap(serverId).collect { statusMap ->
                    val working = statusMap.filter { it.value }.keys
                    val workingDirs = sessionStore.getWorkingDirectories(serverId, working)
                    val current = _uiState.value
                    val currentDirs = current.projects.map { it.project.worktree }.toSet()
                    val merged = (current.projects.map { it.project } +
                        _serverProjects.value.filter { it.worktree !in currentDirs }
                    ).distinctBy { it.worktree }
                    val allProjects = merged.map { p ->
                        ProjectDisplay(p, p.worktree in workingDirs)
                    }.sortedByDescending { it.isWorking }
                    val opened = sessionStore.openedDirectories.value
                    _uiState.value = current.copy(
                        projects = allProjects.take(5),
                        openedProjects = allProjects.filter { it.project.worktree in opened },
                    )
                }
            }

            // Observe opened directories
            viewModelScope.launch {
                sessionStore.openedDirectories.collect { opened ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        openedProjects = current.projects.filter { it.project.worktree in opened },
                    )
                }
            }

            // Fetch initial status snapshot + session list for directory mapping
            sessionStore.refreshStatusMap(serverId)
            sessionStore.refreshSessionDirectories(serverId)

            // Observe locally opened directories
            openedDirectoryDao.observeByServer(serverId)
                .collect { entities ->
                    _localProjects.value = entities.map {
                        Project(
                            id = "local_${it.directory}",
                            worktree = it.directory,
                            name = it.displayName,
                        )
                    }
                    // Restore opened directories to SessionStore for SSE filtering
                    entities.forEach { sessionStore.addOpenedDirectory(it.directory) }
                    mergeProjects()
                }
        }.let { job ->
            viewModelScope.launch {
                // Also launch the server connection + fetch
                loadFromServer(serverId, job)
            }
        }
    }

    private suspend fun loadFromServer(serverId: String, observerJob: kotlinx.coroutines.Job) {
        val server = serverRepository.getServer(serverId) ?: return
        try {
            val conn = connectionManager.connect(server)
            connectionManager.updateState(serverId, com.opencode.app.domain.model.ServerStatus.CONNECTING)

            val healthResult = conn.healthApi.checkHealth()
            if (healthResult.isSuccessful) {
                connectionManager.updateState(
                    serverId,
                    com.opencode.app.domain.model.ServerStatus.CONNECTED,
                    version = healthResult.body()?.version,
                )
            } else {
                connectionManager.updateState(
                    serverId,
                    com.opencode.app.domain.model.ServerStatus.ERROR,
                    error = "HTTP ${healthResult.code()}",
                )
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Connection failed")
                return
            }

            // Fetch server's home directory for relative path resolution
            try {
                val pathResponse = conn.configApi.getConfig()
                // Use the path API if available
                val homeDir = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val retrofit = conn.retrofit
                        retrofit.create(com.opencode.app.data.remote.api.PathApi::class.java).getPath()
                    }.getOrNull()?.body()?.home ?: "/"
                }
                _uiState.value = _uiState.value.copy(homeDirectory = homeDir)
            } catch (_: Exception) {}

            val projectResponse = conn.projectApi.listProjects()
            if (projectResponse.isSuccessful) {
                _serverProjects.value = projectResponse.body()?.map { dto ->
                    Project(
                        id = dto.id,
                        worktree = dto.worktree,
                        name = dto.name ?: "",
                        vcs = dto.vcs,
                        iconUrl = dto.icon?.url,
                        iconColor = dto.icon?.color,
                        createdAt = dto.time?.created ?: 0,
                        updatedAt = dto.time?.updated ?: 0,
                    )
                } ?: emptyList()
                _uiState.value = _uiState.value.copy(isLoading = false)
                mergeProjects()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load projects: ${projectResponse.code()}",
                )
            }
        } catch (e: Exception) {
            connectionManager.updateState(
                serverId,
                com.opencode.app.domain.model.ServerStatus.ERROR,
                error = e.message,
            )
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    private fun mergeProjects() {
        val server = _serverProjects.value
        val local = _localProjects.value
        val serverDirs = server.map { it.worktree }.toSet()
        val working = sessionStore.getStatusMap(_uiState.value.server?.id ?: "").value.filter { it.value }.keys
        val workingDirs = sessionStore.getWorkingDirectories(_uiState.value.server?.id ?: "", working)

        // Opened projects: from local Room DB only, independent of server
        val openedProjects = _localProjects.value.map { p ->
            ProjectDisplay(p, p.worktree in workingDirs)
        }.sortedByDescending { it.isWorking }

        // Recent projects: from server API only
        val recentProjects = server.map { p ->
            ProjectDisplay(p, p.worktree in workingDirs)
        }.sortedByDescending { it.isWorking }.take(5)

        _uiState.value = _uiState.value.copy(
            projects = recentProjects,
            openedProjects = openedProjects,
        )
    }

    fun openDirectory(directory: String) {
        viewModelScope.launch {
            saveOpenedDirectory(_uiState.value.server?.id ?: return@launch, directory)
            sessionStore.addOpenedDirectory(directory)
        }
    }

    fun closeDirectory(directory: String) {
        viewModelScope.launch {
            val serverId = _uiState.value.server?.id ?: return@launch
            openedDirectoryDao.delete(serverId, directory)
            sessionStore.removeOpenedDirectory(directory)
        }
    }

    fun saveOpenedDirectory(serverId: String, directory: String) {
        viewModelScope.launch {
            val name = directory.trimEnd('/').substringAfterLast('/').ifBlank { directory }
            openedDirectoryDao.upsert(
                OpenedDirectoryEntity(
                    serverId = serverId,
                    directory = directory,
                    displayName = name,
                    openedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun updateDirectoryInput(text: String) {
        _uiState.value = _uiState.value.copy(directoryInput = text)
    }

    suspend fun listSubdirectories(directory: String): List<String> {
        val serverId = _uiState.value.server?.id ?: return emptyList()
        val conn = connectionManager.getConnection(serverId) ?: return emptyList()
        val home = _uiState.value.homeDirectory.ifBlank { "/" }
        val resolved = resolvePath(directory, home)
        return try {
            val response = conn.fileApi.listFiles(directory = resolved)
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

    private fun resolvePath(input: String, home: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("~") -> home + trimmed.removePrefix("~")
            trimmed.startsWith("/") -> trimmed
            trimmed.isBlank() -> home
            else -> "$home/$trimmed"
        }
    }

    fun resolveUserInput(input: String): String {
        val home = _uiState.value.homeDirectory.ifBlank { "/" }
        return resolvePath(input, home)
    }
}
