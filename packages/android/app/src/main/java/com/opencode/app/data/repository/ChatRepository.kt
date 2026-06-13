package com.opencode.app.data.repository

import android.util.Log
import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.remote.dto.ModelRefDTO
import com.opencode.app.data.remote.dto.PromptPartDTO
import com.opencode.app.data.remote.dto.PromptRequest
import com.opencode.app.domain.model.ChatMessage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val connectionManager: ServerConnectionManager,
) {
    suspend fun fetchMessages(
        serverId: String,
        sessionId: String,
        limit: Int? = null,
    ): Result<List<ChatMessage>> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.listMessages(sessionId, limit)
        if (response.isSuccessful) {
            response.body()?.map { it.toDomain() } ?: emptyList()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun sendMessage(
        serverId: String,
        sessionId: String,
        text: String,
    ): Result<ChatMessage> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val request = PromptRequest(parts = listOf(PromptPartDTO(type = "text", text = text)))
        val response = conn.sessionApi.sendMessage(sessionId, request)
        if (response.isSuccessful) {
            response.body()!!.toDomain()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun sendAsyncMessage(
        serverId: String,
        sessionId: String,
        text: String,
        agent: String? = null,
        modelProviderId: String? = null,
        modelId: String? = null,
    ): Result<Unit> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val model = if (modelProviderId != null && modelId != null) {
            ModelRefDTO(providerId = modelProviderId, modelId = modelId)
        } else null
        val request = PromptRequest(
            parts = listOf(PromptPartDTO(type = "text", text = text)),
            model = model,
            agent = agent,
        )
        Log.d("ChatRepo", "sendAsyncPrompt: sessionId=$sessionId agent=$agent model=$modelId text=$text")
        val response = conn.sessionApi.sendAsyncPrompt(sessionId, request)
        Log.d("ChatRepo", "sendAsyncPrompt response: code=${response.code()} success=${response.isSuccessful}")
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            Log.e("ChatRepo", "sendAsyncPrompt FAILED: ${response.code()} $errorBody")
            throw Exception("HTTP ${response.code()}: $errorBody")
        }
    }

    suspend fun deleteMessage(
        serverId: String,
        sessionId: String,
        messageId: String,
    ): Result<Unit> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        conn.sessionApi.deleteMessage(sessionId, messageId)
    }
}
