package com.opencode.app.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.app.data.repository.ServerRepository
import com.opencode.app.domain.model.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddServerUiState(
    val name: String = "",
    val url: String = "",
    val username: String = "opencode",
    val password: String = "",
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
)

sealed class TestResult {
    data class Success(val version: String) : TestResult()
    data class Error(val message: String) : TestResult()
}

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddServerUiState())
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    fun loadExisting(serverId: String) {
        viewModelScope.launch {
            serverRepository.getServer(serverId)?.let { config ->
                _uiState.value = _uiState.value.copy(
                    name = config.name,
                    url = config.url,
                    username = config.username,
                )
            }
        }
    }

    fun updateName(v: String) { _uiState.value = _uiState.value.copy(name = v, testResult = null) }
    fun updateUrl(v: String) { _uiState.value = _uiState.value.copy(url = v, testResult = null) }
    fun updateUsername(v: String) { _uiState.value = _uiState.value.copy(username = v) }
    fun updatePassword(v: String) { _uiState.value = _uiState.value.copy(password = v) }

    fun testConnection() {
        val s = _uiState.value
        if (s.url.isBlank()) return
        viewModelScope.launch {
            _uiState.value = s.copy(isTesting = true, testResult = null)
            val result = serverRepository.testConnection(s.url, s.username, s.password.ifBlank { null })
            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResult = result.fold(
                    onSuccess = { TestResult.Success(it) },
                    onFailure = { TestResult.Error(it.message ?: "Unknown error") },
                ),
            )
        }
    }

    fun save(existingId: String?) {
        val s = _uiState.value
        if (s.url.isBlank()) return
        val name = s.name.ifBlank { s.url.removePrefix("http://").removePrefix("https://").substringBefore(":") }
        viewModelScope.launch {
            _uiState.value = s.copy(isSaving = true)
            val pwd = s.password.ifBlank { null }
            if (existingId != null) {
                val existing = serverRepository.getServer(existingId)!!
                serverRepository.updateServer(
                    config = existing.copy(
                        name = name,
                        url = s.url,
                        username = s.username,
                        hasPassword = !pwd.isNullOrBlank(),
                    ),
                    password = pwd,
                )
            } else {
                serverRepository.addServer(name, s.url, s.username, pwd)
            }
            _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
        }
    }
}
