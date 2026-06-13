package com.opencode.app.data.mapper

import com.opencode.app.data.local.ServerConfigEntity
import com.opencode.app.data.remote.dto.AgentDTO
import com.opencode.app.data.remote.dto.MessageDTO
import com.opencode.app.data.remote.dto.ModelDTO
import com.opencode.app.data.remote.dto.PartDTO
import com.opencode.app.data.remote.dto.ProjectDTO
import com.opencode.app.data.remote.dto.ProviderDTO
import com.opencode.app.data.remote.dto.SessionDTO
import com.opencode.app.data.remote.dto.SessionStatusDTO
import com.opencode.app.data.remote.dto.TodoDTO
import com.opencode.app.domain.model.AgentInfo
import com.opencode.app.domain.model.ChatMessage
import com.opencode.app.domain.model.MessagePart
import com.opencode.app.domain.model.MessageRole
import com.opencode.app.domain.model.ModelInfo
import com.opencode.app.domain.model.Project
import com.opencode.app.domain.model.Provider
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.Session
import com.opencode.app.domain.model.SessionRunStatus
import com.opencode.app.domain.model.SessionStatusEntry
import com.opencode.app.domain.model.ToolStatus
import com.opencode.app.domain.model.Todo

fun ServerConfigEntity.toDomain() = ServerConfig(
    id = id,
    name = name,
    url = url,
    username = username,
    hasPassword = hasPassword,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
)

fun ServerConfig.toEntity() = ServerConfigEntity(
    id = id,
    name = name,
    url = url,
    username = username,
    hasPassword = hasPassword,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
)

fun SessionDTO.toDomain(): Session = Session(
    id = id,
    title = title.ifBlank { "Untitled Session" },
    projectId = projectId,
    directory = directory,
    agent = agent,
    modelId = model?.id,
    modelProvider = model?.providerId,
    cost = cost,
    inputTokens = tokens?.input ?: 0,
    outputTokens = tokens?.output ?: 0,
    createdAt = time?.created ?: 0,
    updatedAt = time?.updated ?: 0,
    shareUrl = share?.url,
    parentSessionId = parentId,
)

fun ProjectDTO.toDomain(): Project = Project(
    id = id,
    worktree = worktree,
    name = name ?: "",
    vcs = vcs,
    iconUrl = icon?.url,
    iconColor = icon?.color,
    createdAt = time?.created ?: 0,
    updatedAt = time?.updated ?: 0,
)

fun ProviderDTO.toDomain(): Provider = Provider(
    id = id,
    name = name,
    models = models.values.map { it.toDomain(id) },
)

fun ModelDTO.toDomain(providerId: String): ModelInfo = ModelInfo(
    id = id.ifBlank { name },
    name = name,
    providerId = providerId,
    attachmentSupported = attachment,
    reasoningSupported = reasoning,
)

fun AgentDTO.toDomain(): AgentInfo = AgentInfo(
    name = name,
    description = description,
    mode = mode,
    isBuiltIn = native,
)

fun TodoDTO.toDomain(): Todo = Todo(id = id, content = content, status = status, priority = priority)

fun MessageDTO.toDomain(): ChatMessage {
    val i = info ?: return ChatMessage(id = "", sessionId = "", role = MessageRole.ASSISTANT)
    val role = when (i.role.lowercase()) {
        "user" -> MessageRole.USER
        else -> MessageRole.ASSISTANT
    }
    return ChatMessage(
        id = i.id,
        sessionId = i.sessionId,
        role = role,
        createdAt = i.time?.created ?: 0,
        completedAt = i.time?.completed ?: 0,
        parts = parts.map { it.toDomain() },
        modelId = i.modelId ?: i.model?.id,
        providerId = i.providerId ?: i.model?.providerId,
        agent = i.agent,
        mode = i.mode,
        cost = i.cost,
        tokenTotal = i.tokens?.total ?: 0L,
        parentId = i.parentId,
        error = i.error?.message,
    )
}

fun PartDTOtoDomain(part: PartDTO): MessagePart = part.toDomain()

fun PartDTO.toDomain(): MessagePart {
    return when (type) {
        "text" -> MessagePart.Text(text = text ?: "")
        "reasoning" -> MessagePart.Reasoning(
            text = text ?: "",
            startMs = time?.start ?: 0,
            endMs = time?.end ?: 0,
        )
        "tool" -> MessagePart.Tool(
            tool = tool ?: "unknown",
            status = when (state?.status) {
                "pending" -> ToolStatus.PENDING
                "running" -> ToolStatus.RUNNING
                "completed" -> ToolStatus.COMPLETED
                "error" -> ToolStatus.ERROR
                else -> ToolStatus.PENDING
            },
            title = state?.title,
            input = state?.input?.toString(),
            output = state?.output?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: state?.output?.toString(),
            error = state?.error,
            childSessionId = state?.metadata?.let { meta ->
                (meta["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (meta["sessionID"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            },
            inputJson = state?.input?.toString(),
            metadataJson = state?.metadata?.toString(),
            callId = this.callId,
        )
        "file" -> MessagePart.File(mime = mime ?: "", url = url ?: "", filename = null)
        "step-start" -> MessagePart.StepStart(agent = agent)
        "step-finish" -> MessagePart.StepFinish(
            cost = cost ?: 0.0,
            inputTokens = tokens?.input ?: 0,
            outputTokens = tokens?.output ?: 0,
            reason = null,
        )
        "agent" -> MessagePart.Agent(name = name ?: agent ?: "")
        "subtask" -> MessagePart.Subtask(
            agent = agent ?: "",
            description = description ?: "",
            prompt = prompt ?: "",
        )
        "compaction" -> MessagePart.Compaction(auto = false)
        "snapshot" -> MessagePart.Snapshot
        "patch" -> MessagePart.Patch(files = files ?: emptyList())
        "retry" -> MessagePart.Retry(attempt = 0, error = text ?: reason ?: "")
        else -> MessagePart.Text(text = text ?: "")
    }
}

fun SessionStatusDTO.toDomain(sessionId: String): SessionStatusEntry = SessionStatusEntry(
    sessionId = sessionId,
    status = when (type) {
        "busy" -> SessionRunStatus.BUSY
        "retry" -> SessionRunStatus.RETRY
        else -> SessionRunStatus.IDLE
    },
    retryAttempt = attempt ?: 0,
    retryMessage = message,
)
