package com.opencode.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SseEvent(
    val id: String,
    val type: String,
    val properties: JsonObject = JsonObject(emptyMap()),
)

object EventType {
    const val SERVER_CONNECTED = "server.connected"
    const val SERVER_HEARTBEAT = "server.heartbeat"
    const val SERVER_INSTANCE_DISPOSED = "server.instance.disposed"

    const val SESSION_CREATED = "session.created"
    const val SESSION_UPDATED = "session.updated"
    const val SESSION_DELETED = "session.deleted"
    const val SESSION_STATUS = "session.status"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_COMPACTED = "session.compacted"
    const val SESSION_DIFF = "session.diff"
    const val SESSION_ERROR = "session.error"

    const val MESSAGE_UPDATED = "message.updated"
    const val MESSAGE_REMOVED = "message.removed"
    const val MESSAGE_PART_UPDATED = "message.part.updated"
    const val MESSAGE_PART_REMOVED = "message.part.removed"
    const val MESSAGE_PART_DELTA = "message.part.delta"

    const val PERMISSION_UPDATED = "permission.asked"
    const val PERMISSION_REPLIED = "permission.replied"

    const val TODO_UPDATED = "todo.updated"
    const val FILE_EDITED = "file.edited"
    const val COMMAND_EXECUTED = "command.executed"
    const val VCS_BRANCH_UPDATED = "vcs.branch.updated"
}
