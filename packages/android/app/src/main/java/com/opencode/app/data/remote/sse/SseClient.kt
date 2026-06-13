package com.opencode.app.data.remote.sse

import android.util.Log
import com.opencode.app.domain.model.SseEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObject.Companion
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

class SseClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun connect(authHeader: String?): Flow<SseEvent> = callbackFlow {
        val cancelled = AtomicBoolean(false)
        var eventSource: EventSource? = null
        var retryDelayMs = 1000L
        val maxRetryMs = 30_000L
        val heartbeatTimeoutMs = 15_000L
        var heartbeatFuture: ScheduledFuture<*>? = null

        fun resetHeartbeat() {
            heartbeatFuture?.cancel(false)
            heartbeatFuture = scheduler.schedule({
                if (!cancelled.get()) {
                    eventSource?.cancel()
                }
            }, heartbeatTimeoutMs, TimeUnit.MILLISECONDS)
        }

        fun startConnection() {
            if (cancelled.get()) return

            val builder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/global/event")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")

            if (authHeader != null) {
                builder.header("Authorization", authHeader)
            }

            eventSource = EventSources.createFactory(client)
                .newEventSource(builder.build(), object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        resetHeartbeat()
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        resetHeartbeat()
                        runCatching {
                            val root = json.parseToJsonElement(data).let { elem ->
                                val obj = elem as? kotlinx.serialization.json.JsonObject
                                    ?: return@runCatching null
                                val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject
                                payload ?: obj
                            }
                            if (root != null) {
                                json.decodeFromString<SseEvent>(root.toString())
                            } else {
                                null
                            }
                        }.getOrNull()?.let { event ->
                            retryDelayMs = 1000L
                            trySend(event)
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        heartbeatFuture?.cancel(false)
                        scheduleReconnect()
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        heartbeatFuture?.cancel(false)
                        scheduleReconnect()
                    }

                    private fun scheduleReconnect() {
                        if (cancelled.get() || isClosedForSend) return
                        scheduler.schedule({
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryMs)
                            startConnection()
                        }, retryDelayMs, TimeUnit.MILLISECONDS)
                    }
                })
        }

        startConnection()

        awaitClose {
            cancelled.set(true)
            heartbeatFuture?.cancel(false)
            eventSource?.cancel()
        }
    }

    fun disconnect() {
        // Flow cancellation handles cleanup via awaitClose
    }

    companion object {
        fun createOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
