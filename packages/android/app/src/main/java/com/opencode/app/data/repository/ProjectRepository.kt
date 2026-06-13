package com.opencode.app.data.repository

import com.opencode.app.data.mapper.toDomain
import com.opencode.app.data.remote.ServerConnectionManager
import com.opencode.app.domain.model.AgentInfo
import com.opencode.app.domain.model.Project
import com.opencode.app.domain.model.Provider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val connectionManager: ServerConnectionManager,
) {
    private fun conn(serverId: String) =
        connectionManager.getConnection(serverId)
            ?: throw IllegalStateException("Server $serverId not connected")

    suspend fun listProjects(serverId: String): List<Project> {
        val api = conn(serverId).projectApi
        val response = api.listProjects()
        if (!response.isSuccessful) throw Exception("Failed to list projects: ${response.code()}")
        return (response.body() ?: emptyList()).map { it.toDomain() }
    }

    suspend fun listProviders(serverId: String): List<Provider> {
        val api = conn(serverId).providerApi
        val response = api.listProviders()
        if (!response.isSuccessful) throw Exception("Failed to list providers: ${response.code()}")
        val body = response.body() ?: return emptyList()
        return body.all.filter { it.id in body.connected }.map { it.toDomain() }
    }

    suspend fun listAgents(serverId: String): List<AgentInfo> {
        val api = conn(serverId).agentApi
        val response = api.listAgents()
        if (!response.isSuccessful) throw Exception("Failed to list agents: ${response.code()}")
        return (response.body() ?: emptyList()).map { it.toDomain() }
    }
}
