package com.opencode.app.data.repository

import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.data.remote.dto.CreateSessionRequest
import com.opencode.app.data.remote.dto.PatchSessionRequest
import com.opencode.app.data.remote.dto.PromptPartDTO
import com.opencode.app.data.remote.dto.PromptRequest
import com.opencode.app.domain.model.Session
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val connectionManager: ServerConnectionManager,
) {
    suspend fun fetchSessions(
        serverId: String,
        directory: String? = null,
        search: String? = null,
    ): Result<List<Session>> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        conn.setDirectory(directory)
        val response = conn.sessionApi.listSessions(directory = directory, search = search)
        if (response.isSuccessful) {
            response.body()?.map { it.toDomain() } ?: emptyList()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun getSession(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.getSession(sessionId)
        if (response.isSuccessful) {
            response.body()!!.toDomain()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun createSession(
        serverId: String,
        directory: String? = null,
        agent: String? = null,
    ): Result<Session> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        conn.setDirectory(directory)
        val response = conn.sessionApi.createSession(
            CreateSessionRequest(directory = directory, agent = agent)
        )
        if (response.isSuccessful) {
            response.body()!!.toDomain()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun archiveSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.updateSession(sessionId, PatchSessionRequest(archived = System.currentTimeMillis()))
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.deleteSession(sessionId)
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        }
    }

    suspend fun abortSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        conn.sessionApi.abortSession(sessionId)
    }

    suspend fun renameSession(
        serverId: String,
        sessionId: String,
        title: String,
    ): Result<Session> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.updateSession(sessionId, PatchSessionRequest(title = title))
        if (response.isSuccessful) response.body()!!.toDomain()
        else throw Exception("HTTP ${response.code()}: ${response.message()}")
    }

    suspend fun shareSession(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.shareSession(sessionId)
        if (response.isSuccessful) response.body()!!.toDomain()
        else throw Exception("HTTP ${response.code()}: ${response.message()}")
    }

    suspend fun forkSession(
        serverId: String,
        sessionId: String,
        messageId: String,
    ): Result<Session> = runCatching {
        val conn = connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server not connected")
        val response = conn.sessionApi.forkSession(sessionId, mapOf("messageID" to messageId))
        if (response.isSuccessful) response.body()!!.toDomain()
        else throw Exception("HTTP ${response.code()}: ${response.message()}")
    }
}
