package com.opencode.app.data.remote

import com.opencode.app.data.remote.api.AgentApi
import com.opencode.app.data.remote.api.ConfigApi
import com.opencode.app.data.remote.api.HealthApi
import com.opencode.app.data.remote.api.ProjectApi
import com.opencode.app.data.remote.api.ProviderApi
import com.opencode.app.data.remote.api.SessionApi
import com.opencode.app.data.remote.api.VoiceApi
import com.opencode.app.data.remote.api.FileApi
import com.opencode.app.data.remote.api.PermissionApi
import com.opencode.app.data.remote.interceptor.AuthInterceptor
import com.opencode.app.data.remote.interceptor.DirectoryInterceptor
import com.opencode.app.data.remote.sse.SseClient
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.SseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class ServerConnection(
    val config: ServerConfig,
    private val password: String?,
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val authCredential: String?
        get() = if (config.hasPassword && !password.isNullOrEmpty())
            Credentials.basic(config.username, password)
        else null

    private var currentDirectory: String? = null

    private val baseClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (config.hasPassword && !password.isNullOrEmpty()) {
            builder.addInterceptor(AuthInterceptor(config.username, password))
        }

        builder.addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })

        builder.build()
    }

    val retrofit: Retrofit by lazy {
        val client = baseClient.newBuilder()
            .addInterceptor(DirectoryInterceptor { currentDirectory })
            .build()

        Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(config.url))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val sessionApi: SessionApi by lazy { retrofit.create(SessionApi::class.java) }
    val projectApi: ProjectApi by lazy { retrofit.create(ProjectApi::class.java) }
    val providerApi: ProviderApi by lazy { retrofit.create(ProviderApi::class.java) }
    val agentApi: AgentApi by lazy { retrofit.create(AgentApi::class.java) }
    val configApi: ConfigApi by lazy { retrofit.create(ConfigApi::class.java) }
    val healthApi: HealthApi by lazy { retrofit.create(HealthApi::class.java) }
    val voiceApi: VoiceApi by lazy { retrofit.create(VoiceApi::class.java) }
    val fileApi: FileApi by lazy { retrofit.create(FileApi::class.java) }
    val permissionApi: PermissionApi by lazy { retrofit.create(PermissionApi::class.java) }

    private val sseClient by lazy { SseClient(config.url, SseClient.createOkHttpClient()) }

    private var sseFlow: Flow<SseEvent>? = null

    val events: Flow<SseEvent>
        get() {
            if (sseFlow == null) {
                sseFlow = sseClient.connect(authCredential)
            }
            return sseFlow!!
        }

    fun setDirectory(directory: String?) {
        currentDirectory = directory
    }

    fun disconnect() {
        sseClient.disconnect()
        sseFlow = null
    }

    companion object {
        fun ensureTrailingSlash(url: String): String =
            if (url.endsWith("/")) url else "$url/"
    }
}
