package com.opencode.app.data.session

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.domain.model.ChatMessage
import com.opencode.app.domain.model.MessagePart
import com.opencode.app.domain.model.MessageRole
import com.opencode.app.domain.model.SessionRunStatus
import com.opencode.app.domain.model.SseEvent
import com.opencode.app.domain.model.EventType
import com.opencode.app.notification.AppNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SessionState(
    val messages: List<ChatMessage> = emptyList(),
    val status: SessionRunStatus = SessionRunStatus.IDLE,
    val loaded: Boolean = false,
    val loading: Boolean = false,
)

@Singleton
class SessionStore @Inject constructor(
    private val app: Application,
    private val connectionManager: ServerConnectionManager,
    private val notifier: AppNotifier,
) {
    companion object {
        private const val MAX_CACHED_SESSIONS = 20
        private const val MAX_MESSAGES_PER_SESSION = 200
    }

    private val stores = object : LinkedHashMap<String, MutableStateFlow<SessionState>>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableStateFlow<SessionState>>): Boolean {
            if (size > MAX_CACHED_SESSIONS) {
                Log.d("SessionStore", "Evicting cached session: ${eldest.key}")
                return true
            }
            return false
        }
    }

    private val globalStatus = mutableMapOf<String, MutableStateFlow<Map<String, Boolean>>>()
    private val sessionDirectories = mutableMapOf<String, MutableStateFlow<Map<String, String>>>()

    // Currently viewing session (null = not in any chat)
    private var currentSessionId: String? = null

    // Opened project directories (directories the user has opened)
    private val _openedDirectories = MutableStateFlow<Set<String>>(emptySet())
    val openedDirectories: StateFlow<Set<String>> = _openedDirectories.asStateFlow()

    // App foreground state
    private var appInForeground = true

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                appInForeground = false
                Log.d("SessionStore", "App backgrounded")
            }

            override fun onStart(owner: LifecycleOwner) {
                appInForeground = true
                Log.d("SessionStore", "App foregrounded")
            }
        })
    }

    fun setCurrentSession(sessionId: String?) {
        currentSessionId = sessionId
    }

    fun addOpenedDirectory(directory: String) {
        _openedDirectories.value = _openedDirectories.value + directory
    }

    fun removeOpenedDirectory(directory: String) {
        _openedDirectories.value = _openedDirectories.value - directory
    }

    @Synchronized
    fun getState(serverId: String, sessionId: String): StateFlow<SessionState> {
        val key = "$serverId:$sessionId"
        return stores.getOrPut(key) { MutableStateFlow(SessionState()) }.asStateFlow()
    }

    @Synchronized
    fun setLoading(serverId: String, sessionId: String, loading: Boolean) {
        val key = "$serverId:$sessionId"
        stores.getOrPut(key) { MutableStateFlow(SessionState()) }.let { flow ->
            flow.value = flow.value.copy(loading = loading)
        }
    }

    @Synchronized
    fun setMessages(serverId: String, sessionId: String, messages: List<ChatMessage>) {
        val key = "$serverId:$sessionId"
        val capped = if (messages.size > MAX_MESSAGES_PER_SESSION) {
            Log.d("SessionStore", "Capping messages for $key: ${messages.size} -> $MAX_MESSAGES_PER_SESSION")
            messages.takeLast(MAX_MESSAGES_PER_SESSION)
        } else {
            messages
        }
        stores.getOrPut(key) { MutableStateFlow(SessionState()) }.let { flow ->
            flow.value = flow.value.copy(messages = capped, loaded = true, loading = false)
        }
    }

    @Synchronized
    fun appendStreamingText(serverId: String, sessionId: String, messageId: String, text: String) {
        val key = "$serverId:$sessionId"
        val flow = stores[key] ?: return
        val messages = flow.value.messages.toMutableList()
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val msg = messages[idx]
            val parts = msg.parts.toMutableList()
            val textIdx = parts.indexOfLast { it is MessagePart.Text }
            if (textIdx >= 0) {
                val t = parts[textIdx] as MessagePart.Text
                parts[textIdx] = t.copy(text = t.text + text)
            } else {
                parts.add(MessagePart.Text(text))
            }
            messages[idx] = msg.copy(parts = parts, isStreaming = true)
        } else if (messages.size < MAX_MESSAGES_PER_SESSION) {
            messages.add(ChatMessage(
                id = messageId, sessionId = sessionId, role = MessageRole.ASSISTANT,
                parts = listOf(MessagePart.Text(text)), isStreaming = true,
            ))
        }
        flow.value = flow.value.copy(messages = messages)
    }

    @Synchronized
    fun appendStreamingReasoning(serverId: String, sessionId: String, messageId: String, delta: String) {
        val key = "$serverId:$sessionId"
        val flow = stores.getOrPut(key) { MutableStateFlow(SessionState()) }
        val messages = flow.value.messages.toMutableList()
        val idx = messages.indexOfFirst { it.id == messageId }

        if (idx >= 0) {
            val msg = messages[idx]
            val parts = msg.parts.toMutableList()
            val reasoningIdx = parts.indexOfLast { it is MessagePart.Reasoning }
            if (reasoningIdx >= 0) {
                val r = parts[reasoningIdx] as MessagePart.Reasoning
                parts[reasoningIdx] = r.copy(text = r.text + delta)
            } else {
                parts.add(MessagePart.Reasoning(delta))
            }
            messages[idx] = msg.copy(parts = parts, isStreaming = true)
        } else {
            messages.add(ChatMessage(
                id = messageId, sessionId = sessionId, role = MessageRole.ASSISTANT,
                parts = listOf(MessagePart.Reasoning(delta)), isStreaming = true,
            ))
        }
        flow.value = flow.value.copy(messages = messages)
    }

    @Synchronized
    fun updateToolPart(serverId: String, sessionId: String, messageId: String, partJson: String) {
        val key = "$serverId:$sessionId"
        val flow = stores.getOrPut(key) { MutableStateFlow(SessionState()) }
        val messages = flow.value.messages.toMutableList()
        val msgIdx = messages.indexOfFirst { it.id == messageId }

        // Parse the part JSON
        val part = runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString<com.opencode.app.data.remote.dto.PartDTO>(partJson)
        }.getOrNull() ?: return
        val toolName = part.tool ?: return
        val newToolPart = com.opencode.app.data.mapper.PartDTOtoDomain(part) as MessagePart.Tool

        if (msgIdx < 0) {
            // Message not in cache yet — create a placeholder with this tool part
            // so the tool card shows up immediately
            val newMsg = ChatMessage(
                id = messageId,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                parts = listOf(newToolPart),
                isStreaming = true,
            )
            messages.add(newMsg)
            flow.value = flow.value.copy(messages = messages)
            return
        }

        val msg = messages[msgIdx]
        val parts = msg.parts.toMutableList()

        // Match by callId (unique per tool call), fallback to tool name
        val callId = part.callId
        val toolIdx = if (callId != null) {
            parts.indexOfFirst { it is MessagePart.Tool && it.callId == callId }
        } else {
            parts.indexOfFirst { it is MessagePart.Tool && it.tool == toolName }
        }

        if (toolIdx >= 0) {
            parts[toolIdx] = newToolPart
        } else {
            parts.add(newToolPart)
        }

        messages[msgIdx] = msg.copy(parts = parts, isStreaming = true)
        flow.value = flow.value.copy(messages = messages)
    }

    @Synchronized
    fun setStatus(serverId: String, sessionId: String, status: SessionRunStatus) {
        val key = "$serverId:$sessionId"
        val flow = stores[key] ?: return
        flow.value = flow.value.copy(status = status)
    }

    @Synchronized
    fun getStatusMap(serverId: String): StateFlow<Map<String, Boolean>> {
        return globalStatus.getOrPut(serverId) { MutableStateFlow(emptyMap()) }.asStateFlow()
    }

    @Synchronized
    fun updateStatusMap(serverId: String, sessionId: String, isWorking: Boolean) {
        val flow = globalStatus.getOrPut(serverId) { MutableStateFlow(emptyMap()) }
        val current = flow.value.toMutableMap()
        if (isWorking) {
            current[sessionId] = true
        } else {
            current.remove(sessionId)
        }
        flow.value = current
    }

    @Synchronized
    fun setAllStatuses(serverId: String, statuses: Map<String, Boolean>) {
        val flow = globalStatus.getOrPut(serverId) { MutableStateFlow(emptyMap()) }
        flow.value = statuses
    }

    @Synchronized
    fun getWorkingDirectories(serverId: String, workingSessionIds: Set<String>): Set<String> {
        val dirMap = sessionDirectories[serverId]?.value ?: return emptySet()
        return workingSessionIds.mapNotNull { dirMap[it] }.toSet()
    }

    suspend fun refreshSessionDirectories(serverId: String) {
        val conn = connectionManager.getConnection(serverId) ?: return
        try {
            val response = conn.sessionApi.listSessions(directory = null)
            if (response.isSuccessful) {
                val dirMap = mutableMapOf<String, String>()
                response.body()?.forEach { dto ->
                    if (dto.directory.isNotBlank()) {
                        dirMap[dto.id] = dto.directory
                    }
                }
                val flow = sessionDirectories.getOrPut(serverId) { MutableStateFlow(emptyMap()) }
                flow.value = dirMap
                Log.d("SessionStore", "Loaded ${dirMap.size} session→directory mappings")
            }
        } catch (e: Exception) {
            Log.e("SessionStore", "Failed to fetch session directories", e)
        }
    }

    suspend fun refreshStatusMap(serverId: String, directory: String? = null) {
        val conn = connectionManager.getConnection(serverId) ?: return
        try {
            val response = conn.sessionApi.getSessionStatuses(directory)
            if (response.isSuccessful) {
                val statuses = response.body() ?: emptyMap()
                val working = statuses.mapValues { (_, status) ->
                    status.type != "idle"
                }.filterValues { it }
                setAllStatuses(serverId, working)
                Log.d("SessionStore", "Status snapshot: ${working.size} working sessions")
            }
        } catch (e: Exception) {
            Log.e("SessionStore", "Failed to fetch status snapshot", e)
        }
    }

    @Synchronized
    fun handleSseEvent(serverId: String, event: SseEvent) {
        val props = event.properties
        val sessionId = props.str("sessionID") ?: return

        // Only process events for sessions in opened directories
        val dirMap = sessionDirectories[serverId]?.value
        val sessionDir = dirMap?.get(sessionId) ?: ""
        val opened = _openedDirectories.value
        val isOpened = opened.isEmpty() || sessionDir.isEmpty() || sessionDir in opened
        if (!isOpened && dirMap != null && sessionId !in dirMap.values.flatMap { listOf(it) }) {
            return
        }

        when (event.type) {
            EventType.SERVER_CONNECTED -> {
                // SSE reconnected — refresh async
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    refreshStatusMap(serverId)
                    refreshSessionDirectories(serverId)
                }
            }

            EventType.SESSION_STATUS -> {
                val statusType = (props["status"] as? JsonObject)?.str("type") ?: "idle"
                val status = when (statusType) {
                    "busy" -> SessionRunStatus.BUSY
                    "retry" -> SessionRunStatus.RETRY
                    else -> SessionRunStatus.IDLE
                }
                setStatus(serverId, sessionId, status)

                // Only track opened directory sessions
                if (isOpened) {
                    val wasWorking = getStatusMap(serverId).value[sessionId] == true
                    updateStatusMap(serverId, sessionId, status != SessionRunStatus.IDLE)

                    // Notify on session completion
                    if (wasWorking && status == SessionRunStatus.IDLE) {
                        maybeNotifyCompletion(serverId, sessionId)
                    }
                }
            }

            EventType.MESSAGE_PART_DELTA -> {
                if (!isOpened) return
                val messageId = props.str("messageID") ?: return
                val delta = props.str("delta") ?: return
                if (delta.isEmpty()) return
                val field = props.str("field") ?: "text"
                if (field == "reasoning") {
                    appendStreamingReasoning(serverId, sessionId, messageId, delta)
                } else {
                    appendStreamingText(serverId, sessionId, messageId, delta)
                }
            }

            EventType.MESSAGE_PART_UPDATED -> {
                if (!isOpened) return
                val partObj = props["part"] as? JsonObject ?: return
                val messageId = partObj.str("messageID") ?: return
                val partType = partObj.str("type") ?: return
                if (partType == "reasoning" || partType == "tool") {
                    updateToolPart(serverId, sessionId, messageId, partObj.toString())
                }
            }

            EventType.SESSION_IDLE -> {
                setStatus(serverId, sessionId, SessionRunStatus.IDLE)
                if (isOpened) {
                    val wasWorking = getStatusMap(serverId).value[sessionId] == true
                    updateStatusMap(serverId, sessionId, false)
                    if (wasWorking) {
                        maybeNotifyCompletion(serverId, sessionId)
                    }
                }
            }

            EventType.PERMISSION_UPDATED,
            EventType.PERMISSION_REPLIED -> {
                // Permission handled in ChatViewModel for active session
            }
        }
    }

    private fun maybeNotifyCompletion(serverId: String, sessionId: String) {
        // Only notify if: app is in background OR user is not viewing this session
        if (appInForeground && sessionId == currentSessionId) {
            return // User is viewing this session, no notification needed
        }

        // Get session title from cached state or use a default
        val title = stores["$serverId:$sessionId"]?.value?.messages
            ?.lastOrNull { it.role == MessageRole.USER }?.textContent?.take(50)
            ?: "Session"

        Log.d("SessionStore", "Notifying completion: $sessionId '$title'")
        notifier.notifySessionComplete(title, sessionId)
    }

    @Synchronized
    fun clearSession(serverId: String, sessionId: String) {
        stores.remove("$serverId:$sessionId")
    }

    @Synchronized
    fun clearServer(serverId: String) {
        stores.keys.filter { it.startsWith("$serverId:") }.forEach { stores.remove(it) }
        globalStatus.remove(serverId)
        sessionDirectories.remove(serverId)
    }

    @Synchronized
    fun getCacheSize(): Int = stores.size

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
