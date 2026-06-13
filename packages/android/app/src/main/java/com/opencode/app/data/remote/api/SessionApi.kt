package com.opencode.app.data.remote.api

import com.opencode.app.data.remote.dto.CreateSessionRequest
import com.opencode.app.data.remote.dto.MessageDTO
import com.opencode.app.data.remote.dto.PatchSessionRequest
import com.opencode.app.data.remote.dto.PromptRequest
import com.opencode.app.data.remote.dto.SessionDTO
import com.opencode.app.data.remote.dto.SessionStatusDTO
import com.opencode.app.data.remote.dto.TodoDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SessionApi {

    @GET("session")
    suspend fun listSessions(
        @Query("directory") directory: String? = null,
        @Query("search") search: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("start") start: Int? = null,
    ): Response<List<SessionDTO>>

    @GET("session/status")
    suspend fun getSessionStatuses(@Query("directory") directory: String? = null): Response<Map<String, SessionStatusDTO>>

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionRequest = CreateSessionRequest()): Response<SessionDTO>

    @GET("session/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): Response<SessionDTO>

    @PATCH("session/{sessionId}")
    suspend fun updateSession(
        @Path("sessionId") sessionId: String,
        @Body body: PatchSessionRequest,
    ): Response<SessionDTO>

    @DELETE("session/{sessionId}")
    suspend fun deleteSession(@Path("sessionId") sessionId: String): Response<Unit>

    @POST("session/{sessionId}/abort")
    suspend fun abortSession(@Path("sessionId") sessionId: String): Response<Unit>

    @POST("session/{sessionId}/fork")
    suspend fun forkSession(
        @Path("sessionId") sessionId: String,
        @Body body: Map<String, String> = emptyMap(),
    ): Response<SessionDTO>

    @POST("session/{sessionId}/share")
    suspend fun shareSession(@Path("sessionId") sessionId: String): Response<SessionDTO>

    @DELETE("session/{sessionId}/share")
    suspend fun unshareSession(@Path("sessionId") sessionId: String): Response<SessionDTO>

    @POST("session/{sessionId}/summarize")
    suspend fun summarizeSession(@Path("sessionId") sessionId: String): Response<Unit>

    @GET("session/{sessionId}/message")
    suspend fun listMessages(
        @Path("sessionId") sessionId: String,
        @Query("limit") limit: Int? = null,
    ): Response<List<MessageDTO>>

    @POST("session/{sessionId}/message")
    suspend fun sendMessage(
        @Path("sessionId") sessionId: String,
        @Body body: PromptRequest,
    ): Response<MessageDTO>

    @POST("session/{sessionId}/prompt_async")
    suspend fun sendAsyncPrompt(
        @Path("sessionId") sessionId: String,
        @Body body: PromptRequest,
    ): Response<Unit>

    @DELETE("session/{sessionId}/message/{messageId}")
    suspend fun deleteMessage(
        @Path("sessionId") sessionId: String,
        @Path("messageId") messageId: String,
    ): Response<Unit>

    @GET("session/{sessionId}/todo")
    suspend fun getTodos(@Path("sessionId") sessionId: String): Response<List<TodoDTO>>

    @GET("session/{sessionId}/children")
    suspend fun getChildSessions(@Path("sessionId") sessionId: String): Response<List<SessionDTO>>
}
