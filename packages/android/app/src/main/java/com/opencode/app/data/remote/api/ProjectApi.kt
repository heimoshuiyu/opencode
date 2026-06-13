package com.opencode.app.data.remote.api

import com.opencode.app.data.remote.dto.AgentDTO
import com.opencode.app.data.remote.dto.ConfigDTO
import com.opencode.app.data.remote.dto.TranscribeRequestDTO
import com.opencode.app.data.remote.dto.TranscribeResponseDTO
import com.opencode.app.data.remote.dto.FileEntryDTO
import com.opencode.app.data.remote.dto.HealthDTO
import com.opencode.app.data.remote.dto.PermissionReplyDTO
import com.opencode.app.data.remote.dto.PermissionRequestDTO
import com.opencode.app.data.remote.dto.PathResponseDTO
import com.opencode.app.data.remote.dto.ProjectDTO
import com.opencode.app.data.remote.dto.ProviderListResponseDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ProjectApi {

    @GET("project")
    suspend fun listProjects(): Response<List<ProjectDTO>>

    @GET("project/current")
    suspend fun getCurrentProject(@Query("path") path: String? = null): Response<ProjectDTO>

    @GET("project/{projectId}/directories")
    suspend fun getProjectDirectories(@Path("projectId") projectId: String): Response<List<String>>
}

interface ProviderApi {

    @GET("provider")
    suspend fun listProviders(): Response<ProviderListResponseDTO>

    @GET("provider/auth")
    suspend fun getProviderAuth(): Response<Map<String, Boolean>>
}

interface AgentApi {

    @GET("agent")
    suspend fun listAgents(): Response<List<AgentDTO>>
}

interface ConfigApi {

    @GET("config")
    suspend fun getConfig(): Response<ConfigDTO>
}

interface HealthApi {

    @GET("global/health")
    suspend fun checkHealth(): Response<HealthDTO>
}

interface VoiceApi {

    @POST("voice/transcribe")
    suspend fun transcribe(@Body body: TranscribeRequestDTO): Response<TranscribeResponseDTO>
}

interface FileApi {

    @GET("file")
    suspend fun listFiles(
        @Query("directory") directory: String? = null,
        @Query("path") path: String = "",
    ): Response<List<FileEntryDTO>>
}

interface PermissionApi {

    @GET("permission")
    suspend fun listPending(@Query("directory") directory: String? = null): Response<List<PermissionRequestDTO>>

    @POST("permission/{requestId}/reply")
    suspend fun reply(
        @Path("requestId") requestId: String,
        @Body body: PermissionReplyDTO,
    ): Response<Boolean>
}

interface PathApi {

    @GET("path")
    suspend fun getPath(): Response<PathResponseDTO>
}
