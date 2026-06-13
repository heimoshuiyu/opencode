package com.opencode.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class LastSession(
    val serverId: String,
    val directory: String,
    val sessionId: String,
)

private val Context.dataStore by preferencesDataStore("opencode_settings")

@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val LAST_SERVER_ID = stringPreferencesKey("last_server_id")
        val LAST_DIRECTORY = stringPreferencesKey("last_directory")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
    }

    val showReasoning: Flow<Boolean> = context.dataStore.data.map { it[SHOW_REASONING] ?: false }

    val lastSession: Flow<LastSession?> = context.dataStore.data.map { prefs ->
        val serverId = prefs[LAST_SERVER_ID] ?: return@map null
        val directory = prefs[LAST_DIRECTORY] ?: return@map null
        val sessionId = prefs[LAST_SESSION_ID] ?: return@map null
        if (serverId.isNotBlank() && directory.isNotBlank() && sessionId.isNotBlank()) {
            LastSession(serverId, directory, sessionId)
        } else null
    }

    suspend fun setShowReasoning(value: Boolean) {
        context.dataStore.edit { it[SHOW_REASONING] = value }
    }

    suspend fun saveLastSession(serverId: String, directory: String, sessionId: String) {
        context.dataStore.edit {
            it[LAST_SERVER_ID] = serverId
            it[LAST_DIRECTORY] = directory
            it[LAST_SESSION_ID] = sessionId
        }
    }

    suspend fun clearLastSession() {
        context.dataStore.edit {
            it.remove(LAST_SERVER_ID)
            it.remove(LAST_DIRECTORY)
            it.remove(LAST_SESSION_ID)
        }
    }
}
