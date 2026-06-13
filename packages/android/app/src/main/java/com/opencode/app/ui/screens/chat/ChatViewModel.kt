package com.opencode.app.ui.screens.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.app.voice.VoiceRecorder
import com.opencode.app.data.settings.AppSettings
import com.opencode.app.data.session.SessionStore
import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.remote.dto.TranscribeRequestDTO
import com.opencode.app.data.remote.dto.VoiceConfigDTO
import com.opencode.app.data.remote.dto.VoiceLalmDTO
import com.opencode.app.data.remote.dto.VoiceModelRefDTO
import com.opencode.app.data.repository.ChatRepository
import com.opencode.app.data.repository.ServerRepository
import com.opencode.app.data.repository.SessionRepository
import com.opencode.app.domain.model.AgentInfo
import com.opencode.app.domain.model.ModelInfo
import com.opencode.app.domain.model.Provider
import com.opencode.app.domain.model.ChatMessage
import com.opencode.app.domain.model.MessagePart
import com.opencode.app.domain.model.MessageRole
import com.opencode.app.domain.model.SessionRunStatus
import com.opencode.app.domain.model.SseEvent
import com.opencode.app.domain.model.EventType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val sessionStatus: SessionRunStatus = SessionRunStatus.IDLE,
    val sessionTitle: String = "Chat",
    val sessionTokens: Long = 0,
    val error: String? = null,
    val inputText: String = "",
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String? = null,
    val providers: List<Provider> = emptyList(),
    val selectedModelProvider: String? = null,
    val selectedModelId: String? = null,
    val hiddenModels: Set<String> = emptySet(),
    val voiceState: com.opencode.app.ui.components.VoiceState = com.opencode.app.ui.components.VoiceState.IDLE,
    val pendingPermissions: List<com.opencode.app.data.remote.dto.PermissionRequestDTO> = emptyList(),
) {
    val availableModels: List<ModelInfo>
        get() = providers
            .filter { it.models.isNotEmpty() }
            .flatMap { it.models }
            .filter { "${it.providerId}/${it.id}" !in hiddenModels }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val app: Application,
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
    private val settings: AppSettings,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val recorder = VoiceRecorder(app)
    private var lastRecording: File? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    val showReasoning = settings.showReasoning

    fun setShowReasoning(value: Boolean) {
        viewModelScope.launch { settings.setShowReasoning(value) }
    }

    private var sseJob: Job? = null
    private var currentServerId: String? = null
    private var currentSessionId: String? = null

    fun load(serverId: String, sessionId: String, directory: String) {
        currentServerId = serverId
        currentSessionId = sessionId
        sessionStore.setCurrentSession(sessionId)

        // Save as last session for restore on next launch
        viewModelScope.launch {
            settings.saveLastSession(serverId, directory, sessionId)
        }

        // Observe cached session state
        viewModelScope.launch {
            sessionStore.getState(serverId, sessionId).collect { state ->
                _uiState.value = _uiState.value.copy(
                    messages = state.messages,
                    isLoading = state.loading,
                    sessionStatus = state.status,
                    isSending = state.status != SessionRunStatus.IDLE,
                )
            }
        }

        viewModelScope.launch {
            // Auto-connect to server if not already connected
            if (connectionManager.getConnection(serverId) == null) {
                val server = serverRepository.getServer(serverId)
                if (server != null) {
                    connectionManager.connect(server)
                    connectionManager.updateState(serverId, com.opencode.app.domain.model.ServerStatus.CONNECTED)
                }
            }

            connectionManager.getConnection(serverId)?.setDirectory(directory)

            // If already cached, skip fetch — show instantly
            val cached = sessionStore.getState(serverId, sessionId).value
            if (cached.loaded) {
                Log.d("ChatVM", "Session $sessionId loaded from cache (${cached.messages.size} msgs)")
                sessionRepository.getSession(serverId, sessionId)
                    .onSuccess { session ->
                        _uiState.value = _uiState.value.copy(
                            sessionTitle = session.title,
                            selectedAgent = session.agent ?: _uiState.value.selectedAgent,
                            selectedModelProvider = session.modelProvider ?: _uiState.value.selectedModelProvider,
                            selectedModelId = session.modelId ?: _uiState.value.selectedModelId,
                        )
                    }
                if (_uiState.value.sessionTokens == 0L) {
                    extractLatestMessageTokens(serverId, sessionId)
                }
            } else {
                sessionStore.setLoading(serverId, sessionId, true)
                chatRepository.fetchMessages(serverId, sessionId)
                    .onSuccess { messages ->
                        sessionStore.setMessages(serverId, sessionId, messages)
                    }
                    .onFailure { e ->
                        sessionStore.setLoading(serverId, sessionId, false)
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                sessionRepository.getSession(serverId, sessionId)
                    .onSuccess { session ->
                        _uiState.value = _uiState.value.copy(
                            sessionTitle = session.title,
                            selectedAgent = session.agent ?: _uiState.value.selectedAgent,
                            selectedModelProvider = session.modelProvider ?: _uiState.value.selectedModelProvider,
                            selectedModelId = session.modelId ?: _uiState.value.selectedModelId,
                        )
                    }
                extractLatestMessageTokens(serverId, sessionId)
            }

            loadAgentsAndModels(serverId)
            checkPendingPermissions(serverId)
            startSseListener(serverId)
        }
    }

    private suspend fun loadAgentsAndModels(serverId: String) {
        val conn = connectionManager.getConnection(serverId) ?: return
        try {
            val agentResponse = conn.agentApi.listAgents()
            if (agentResponse.isSuccessful) {
                val agents = (agentResponse.body() ?: emptyList())
                    .filter { it.mode != "subagent" && !it.hidden }
                    .map { it.toDomain() }
                _uiState.value = _uiState.value.copy(
                    agents = agents,
                    selectedAgent = _uiState.value.selectedAgent ?: agents.firstOrNull()?.name,
                )
            }
        } catch (e: Exception) {
            Log.e("ChatVM", "Failed to load agents", e)
        }
        try {
            val providerResponse = conn.providerApi.listProviders()
            if (providerResponse.isSuccessful) {
                val body = providerResponse.body()
                val connectedIds = body?.connected ?: emptyList()
                val providers = (body?.all ?: emptyList())
                    .filter { it.id in connectedIds }
                    .map { it.toDomain() }
                _uiState.value = _uiState.value.copy(providers = providers)
            }
        } catch (e: Exception) {
            Log.e("ChatVM", "Failed to load providers", e)
        }
    }

    fun selectAgent(name: String) {
        _uiState.value = _uiState.value.copy(selectedAgent = name)
    }

    private suspend fun checkPendingPermissions(serverId: String) {
        val sessionId = currentSessionId ?: return
        try {
            val conn = connectionManager.getConnection(serverId) ?: return
            val response = conn.permissionApi.listPending()
            if (response.isSuccessful) {
                val pending = response.body()
                    ?.filter { it.sessionId == sessionId }
                if (pending.isNullOrEmpty().not()) {
                    _uiState.value = _uiState.value.copy(pendingPermissions = pending)
                }
            }
        } catch (_: Exception) {}
    }

    fun selectModel(providerId: String, modelId: String) {
        _uiState.value = _uiState.value.copy(
            selectedModelProvider = providerId,
            selectedModelId = modelId,
        )
    }

    fun toggleModelVisibility(key: String) {
        val current = _uiState.value.hiddenModels.toMutableSet()
        if (key in current) current.remove(key) else current.add(key)
        _uiState.value = _uiState.value.copy(hiddenModels = current)
    }

    private fun extractLatestMessageTokens(serverId: String, sessionId: String) {
        viewModelScope.launch {
            chatRepository.fetchMessages(serverId, sessionId, limit = 10)
                .onSuccess { messages ->
                    val lastTotal = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.tokenTotal ?: 0L
                    if (lastTotal > 0) {
                        _uiState.value = _uiState.value.copy(sessionTokens = lastTotal)
                    }
                }
        }
    }

    private fun startSseListener(serverId: String) {
        sseJob?.cancel()
        val conn = connectionManager.getConnection(serverId) ?: return

        sseJob = viewModelScope.launch {
            conn.events.collect { event ->
                // Route ALL events to the global session store (handles non-active sessions too)
                sessionStore.handleSseEvent(serverId, event)

                // Also handle active-session-specific logic
                handleSseEvent(event)
            }
        }
    }

    private var assistantMessageIds = mutableSetOf<String>()

    private fun handleSseEvent(event: SseEvent) {
        // On server.connected (SSE reconnect), re-fetch all data for current session
        if (event.type == "server.connected") {
            val serverId = currentServerId ?: return
            val sessionId = currentSessionId ?: return
            viewModelScope.launch {
                // Re-fetch messages
                chatRepository.fetchMessages(serverId, sessionId)
                    .onSuccess { messages -> sessionStore.setMessages(serverId, sessionId, messages) }
                // Re-fetch session metadata
                sessionRepository.getSession(serverId, sessionId)
                    .onSuccess { session ->
                        _uiState.value = _uiState.value.copy(sessionTitle = session.title)
                    }
                // Re-fetch statuses
                sessionStore.refreshStatusMap(serverId)
                val statusMap = sessionStore.getStatusMap(serverId).value
                val isWorking = sessionId in statusMap
                _uiState.value = _uiState.value.copy(
                    isSending = isWorking,
                    sessionStatus = if (isWorking) SessionRunStatus.BUSY else SessionRunStatus.IDLE,
                )
            }
            return
        }

        // On internal.reconnected (our synthetic signal), refresh status only
        if (event.type == "internal.reconnected") {
            val serverId = currentServerId ?: return
            viewModelScope.launch {
                sessionStore.refreshStatusMap(serverId)
            }
            return
        }

        val sessionId = currentSessionId ?: return
        val props = event.properties

        val eventSessionId = props.str("sessionID")
        if (eventSessionId == null) return
        if (eventSessionId != sessionId) return

        when (event.type) {
            EventType.MESSAGE_UPDATED -> {
                val info = props["info"] as? JsonObject ?: return
                val msgId = info.str("id") ?: return
                val role = info.str("role") ?: return
                if (role == "assistant") {
                    assistantMessageIds.add(msgId)
                }
                // Extract total tokens from the latest assistant message
                if (role == "assistant") {
                    val total = (info["tokens"] as? JsonObject)?.str("total")?.toLongOrNull()
                    if (total != null && total > 0) {
                        _uiState.value = _uiState.value.copy(sessionTokens = total)
                    }
                }
                // Detect session completion from finish field
                val finish = info.str("finish")
                if (role == "assistant" && finish != null && finish != "length") {
                    val serverId = currentServerId ?: return
                    sessionStore.setStatus(serverId, sessionId, SessionRunStatus.IDLE)
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        sessionStatus = SessionRunStatus.IDLE,
                    )
                }
                val serverId = currentServerId ?: return
                viewModelScope.launch {
                    chatRepository.fetchMessages(serverId, sessionId)
                        .onSuccess { messages ->
                            sessionStore.setMessages(serverId, sessionId, messages)
                        }
                }
            }

            EventType.SESSION_UPDATED -> {
                val info = props["info"] as? JsonObject ?: return
                val title = info.str("title")
                _uiState.value = _uiState.value.copy(
                    sessionTitle = title ?: _uiState.value.sessionTitle,
                )
            }

            EventType.MESSAGE_PART_UPDATED -> {
                val partObj = props["part"] as? JsonObject ?: return
                val messageId = partObj.str("messageID") ?: return
                val partType = partObj.str("type") ?: return
                assistantMessageIds.add(messageId)

                // SessionStore handles reasoning and tool part updates
                // ChatViewModel only needs to handle text completion
                if (partType == "tool") {
                    val serverId = currentServerId ?: return
                    sessionStore.updateToolPart(serverId, sessionId, messageId, partObj.toString())
                }
            }

            EventType.PERMISSION_UPDATED -> {
                val permId = props.str("id") ?: return
                val permSession = props.str("sessionID") ?: return
                if (permSession != sessionId) return
                val request = com.opencode.app.data.remote.dto.PermissionRequestDTO(
                    id = permId,
                    sessionId = permSession,
                    permission = props.str("permission") ?: "",
                    patterns = (props["patterns"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                        ?: emptyList(),
                    always = (props["always"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                        ?: emptyList(),
                )
                _uiState.value = _uiState.value.copy(
                    pendingPermissions = _uiState.value.pendingPermissions + request,
                )
            }

            EventType.PERMISSION_REPLIED -> {
                val requestId = props.str("requestID") ?: return
                _uiState.value = _uiState.value.copy(
                    pendingPermissions = _uiState.value.pendingPermissions.filterNot { it.id == requestId },
                )
            }
        }
    }

    private var lastDeltaTime = 0L
    private val pendingDelta = StringBuilder()
    private val DELTA_FLUSH_MS = 60L

    private fun appendStreamingText(messageId: String, delta: String) {
        pendingDelta.append(delta)
        val now = System.currentTimeMillis()
        if (now - lastDeltaTime < DELTA_FLUSH_MS && pendingDelta.length < 200) {
            return
        }
        lastDeltaTime = now
        val flushText = pendingDelta.toString()
        pendingDelta.clear()
        doAppendText(messageId, flushText)
    }

    private fun doAppendText(messageId: String, text: String) {
        if (text.isEmpty()) return
        val messages = _uiState.value.messages.toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == messageId }

        if (existingIndex >= 0) {
            val existing = messages[existingIndex]
            val updatedParts = existing.parts.toMutableList()
            val lastTextIndex = updatedParts.indexOfLast { it is MessagePart.Text }

            if (lastTextIndex >= 0) {
                val lastText = updatedParts[lastTextIndex] as MessagePart.Text
                updatedParts[lastTextIndex] = lastText.copy(text = lastText.text + text)
            } else {
                updatedParts.add(MessagePart.Text(text))
            }

            messages[existingIndex] = existing.copy(parts = updatedParts, isStreaming = true)
        } else {
            messages.add(ChatMessage(
                id = messageId,
                sessionId = currentSessionId ?: "",
                role = MessageRole.ASSISTANT,
                parts = listOf(MessagePart.Text(text)),
                isStreaming = true,
            ))
        }

        _uiState.value = _uiState.value.copy(messages = messages)
    }

    private fun appendStreamingReasoning(messageId: String, delta: String) {
        val messages = _uiState.value.messages.toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == messageId }

        if (existingIndex >= 0) {
            val existing = messages[existingIndex]
            val updatedParts = existing.parts.toMutableList()
            val lastReasoningIndex = updatedParts.indexOfLast { it is MessagePart.Reasoning }

            if (lastReasoningIndex >= 0) {
                val last = updatedParts[lastReasoningIndex] as MessagePart.Reasoning
                updatedParts[lastReasoningIndex] = last.copy(text = last.text + delta)
            } else {
                updatedParts.add(MessagePart.Reasoning(delta))
            }

            messages[existingIndex] = existing.copy(parts = updatedParts, isStreaming = true)
        } else {
            messages.add(ChatMessage(
                id = messageId,
                sessionId = currentSessionId ?: "",
                role = MessageRole.ASSISTANT,
                parts = listOf(MessagePart.Reasoning(delta)),
                isStreaming = true,
            ))
        }

        _uiState.value = _uiState.value.copy(messages = messages)
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage(serverId: String, sessionId: String) {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isSending) return

        _uiState.value = _uiState.value.copy(inputText = "", isSending = true)

        val userMessage = ChatMessage(
            id = "pending_${System.currentTimeMillis()}",
            sessionId = sessionId,
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text(text)),
        )
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + userMessage)

        viewModelScope.launch {
            chatRepository.sendAsyncMessage(
                serverId, sessionId, text,
                agent = _uiState.value.selectedAgent,
                modelProviderId = _uiState.value.selectedModelProvider,
                modelId = _uiState.value.selectedModelId,
            )
                .onSuccess { Log.d("ChatVM", "sendAsyncMessage success") }
                .onFailure { e ->
                    Log.e("ChatVM", "sendAsyncMessage failed", e)
                    _uiState.value = _uiState.value.copy(isSending = false, error = e.message)
                }
        }
    }

    fun abortSession(serverId: String, sessionId: String) {
        viewModelScope.launch {
            sessionRepository.abortSession(serverId, sessionId)
            _uiState.value = _uiState.value.copy(isSending = false)
        }
    }

    fun toggleVoice() {
        when (_uiState.value.voiceState) {
            com.opencode.app.ui.components.VoiceState.IDLE -> startRecording()
            com.opencode.app.ui.components.VoiceState.RECORDING -> stopAndTranscribe()
            com.opencode.app.ui.components.VoiceState.TRANSCRIBING -> cancelTranscription()
            com.opencode.app.ui.components.VoiceState.ERROR -> startRecording()
        }
    }

    private fun startRecording() {
        try {
            recorder.start()
            _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.RECORDING)
        } catch (e: Exception) {
            Log.e("ChatVM", "Failed to start recording", e)
            _uiState.value = _uiState.value.copy(error = "Microphone error: ${e.message}")
        }
    }

    private fun stopAndTranscribe() {
        val file = recorder.stop()
        if (file == null || file.length() == 0L) {
            _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.IDLE)
            return
        }
        lastRecording = file
        _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.TRANSCRIBING)
        transcribe(file)
    }

    private fun transcribe(file: File) {
        val serverId = currentServerId ?: return
        val sessionId = currentSessionId
        val promptText = _uiState.value.inputText
        viewModelScope.launch {
            try {
                val conn = connectionManager.getConnection(serverId) ?: throw Exception("Server not connected")
                val base64 = VoiceRecorder.fileToBase64(file)
                val request = TranscribeRequestDTO(
                    audio = base64,
                    mime = recorder.mime,
                    prompt = promptText.ifBlank { null },
                    sessionId = sessionId,
                )
                val response = conn.voiceApi.transcribe(request)
                if (response.isSuccessful) {
                    val text = response.body()?.text ?: ""
                    if (text.isNotBlank()) {
                        val current = _uiState.value.inputText
                        _uiState.value = _uiState.value.copy(
                            inputText = if (current.isBlank()) text else "$current $text",
                            voiceState = com.opencode.app.ui.components.VoiceState.IDLE,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.IDLE)
                    }
                    file.delete()
                    lastRecording = null
                } else {
                    val errBody = response.errorBody()?.string()
                    Log.e("ChatVM", "Transcribe failed: ${response.code()} $errBody")
                    _uiState.value = _uiState.value.copy(
                        voiceState = com.opencode.app.ui.components.VoiceState.ERROR,
                        error = "Transcription failed: ${response.code()}",
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatVM", "Transcribe exception", e)
                _uiState.value = _uiState.value.copy(
                    voiceState = com.opencode.app.ui.components.VoiceState.ERROR,
                    error = e.message,
                )
            }
        }
    }

    fun retryVoice() {
        val file = lastRecording ?: return
        _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.TRANSCRIBING)
        transcribe(file)
    }

    fun cancelVoice() {
        recorder.cancel()
        lastRecording?.delete()
        lastRecording = null
        _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.IDLE)
    }

    fun replyPermission(requestId: String, reply: String) {
        val serverId = currentServerId ?: return
        _uiState.value = _uiState.value.copy(
            pendingPermissions = _uiState.value.pendingPermissions.filterNot { it.id == requestId },
        )
        viewModelScope.launch {
            try {
                val conn = connectionManager.getConnection(serverId) ?: return@launch
                conn.permissionApi.reply(requestId, com.opencode.app.data.remote.dto.PermissionReplyDTO(reply = reply))
            } catch (e: Exception) {
                Log.e("ChatVM", "Permission reply failed", e)
            }
        }
    }

    private fun cancelTranscription() {
        _uiState.value = _uiState.value.copy(voiceState = com.opencode.app.ui.components.VoiceState.IDLE)
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        sessionStore.setCurrentSession(null)
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
