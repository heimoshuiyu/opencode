package com.opencode.app.ui.screens.chat

import com.opencode.app.ui.components.ToolCallCard
import com.opencode.app.ui.components.ReasoningBlock
import com.opencode.app.ui.components.CompactionDivider
import com.opencode.app.ui.components.AgentBadge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opencode.app.R
import com.opencode.app.domain.model.ChatMessage
import com.opencode.app.domain.model.MessagePart
import com.opencode.app.domain.model.MessageRole
import com.opencode.app.domain.model.ToolStatus
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    serverId: String,
    sessionId: String,
    directory: String,
    onBack: () -> Unit,
    readOnly: Boolean = false,
    onOpenSubSession: (String) -> Unit = {},
) {
    LaunchedEffect(serverId, sessionId, directory) {
        vm.load(serverId, sessionId, directory)
    }

    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    val showReasoning by vm.showReasoning.collectAsState(initial = false)

    // Track whether user is at the bottom (for auto-scroll)
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Initial scroll to bottom when messages first load
    LaunchedEffect(state.messages.isNotEmpty()) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.size - 1)
            autoScrollEnabled = true
        }
    }

    // Detect when user scrolls up manually → disable auto-scroll
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 2
        }.distinctUntilChanged().collect { isAtBottom ->
            if (isAtBottom) autoScrollEnabled = true
            else autoScrollEnabled = false
        }
    }

    // Auto-scroll when new content arrives (message count or last message content changes)
    val lastMessageKey = state.messages.lastOrNull()?.let { "${it.id}_${it.parts.size}_${it.isStreaming}" }
    LaunchedEffect(lastMessageKey) {
        if (state.messages.isNotEmpty() && autoScrollEnabled) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                state.sessionTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (state.isSending) {
                                Text(
                                    stringResource(R.string.generating),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        if (state.sessionTokens > 0) {
                            Text(
                                formatTokens(state.sessionTokens),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        if (state.isSending) {
                            IconButton(onClick = { vm.abortSession(serverId, sessionId) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
                            }
                        }
                        IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.chat_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {},
                actions = {},
            )
        },
        bottomBar = {
            if (!readOnly) {
                ChatInputBar(
                text = state.inputText,
                onTextChange = vm::updateInputText,
                onSend = {
                    keyboardController?.hide()
                    vm.sendMessage(serverId, sessionId)
                },
                isSending = state.isSending,
                agents = state.agents,
                selectedAgent = state.selectedAgent,
                onSelectAgent = vm::selectAgent,
                providers = state.providers,
                selectedModelProvider = state.selectedModelProvider,
                selectedModelId = state.selectedModelId,
                hiddenModels = state.hiddenModels,
                onSelectModel = vm::selectModel,
                onToggleModelVisibility = vm::toggleModelVisibility,
                voiceState = state.voiceState,
                onVoiceToggle = vm::toggleVoice,
                onVoiceRetry = vm::retryVoice,
                onVoiceCancel = vm::cancelVoice,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, message ->
                        // Compute turn duration: lastAssistantCompleted - parentUserCreated
                        val turnDurationMs = if (message.role == MessageRole.ASSISTANT) {
                            val parentId = message.parentId
                            val parentUser = state.messages.find { it.id == parentId }
                            val startMs = parentUser?.createdAt ?: 0L
                            val endMs = state.messages
                                .filter { it.role == MessageRole.ASSISTANT && it.parentId == parentId }
                                .maxOfOrNull { it.completedAt } ?: 0L
                            if (startMs > 0 && endMs > 0) endMs - startMs else 0L
                        } else 0L
                        val isLastInTurn = message.role == MessageRole.ASSISTANT && (
                            index == state.messages.lastIndex ||
                            state.messages[index + 1].role == MessageRole.USER
                        )
                        MessageBubble(
                            message = message,
                            showReasoning = showReasoning,
                            onOpenSubSession = onOpenSubSession,
                            showFooter = isLastInTurn,
                            turnDurationMs = turnDurationMs,
                        )
                    }
                    if (state.error != null) {
                        item {
                            val errorMsg = state.error
                            Text(
                                errorMsg!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text(stringResource(R.string.done)) } },
                title = { Text(stringResource(R.string.chat_settings)) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.show_reasoning), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.show_reasoning_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = showReasoning,
                            onCheckedChange = { vm.setShowReasoning(it) },
                        )
                    }
                },
            )
        }

        state.pendingPermissions.firstOrNull()?.let { perm ->
            com.opencode.app.ui.components.PermissionDialog(
                request = perm,
                onAllowOnce = { vm.replyPermission(perm.id, "once") },
                onAllowAlways = { vm.replyPermission(perm.id, "always") },
                onDeny = { vm.replyPermission(perm.id, "reject") },
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showReasoning: Boolean, onOpenSubSession: (String) -> Unit = {}, showFooter: Boolean = false, turnDurationMs: Long = 0L) {
    val isUser = message.role == MessageRole.USER

    if (isUser) {
        // User message: keep bubble, right-aligned
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Text(
                    text = message.textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    } else {
        val parts = message.visibleParts  // Keep all parts including reasoning

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Render each part in order, no merging
            for (part in parts) {
                when {
                    part is MessagePart.Reasoning && part.text.isNotBlank() -> {
                        if (showReasoning) {
                            ReasoningBlock(text = part.text)
                        } else {
                            CollapsedReasoning(
                                text = part.text, isStreaming = message.isStreaming,
                                startMs = part.startMs, endMs = part.endMs,
                            )
                        }
                    }
                    part is MessagePart.Tool -> {
                        ToolCallCard(part, onTaskClick = onOpenSubSession)
                    }
                    else -> {
                        MessagePartContent(part, message.isStreaming)
                    }
                }
            }
            if (message.hasError) {
                Text(
                    message.error ?: stringResource(R.string.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Footer: agent · model · turn duration (only for the last completed assistant message in a turn)
            if (showFooter && !message.isStreaming && message.completedAt > 0) {
                val durationStr = formatDuration(turnDurationMs)
                val footerParts = mutableListOf<String>()
                if (message.mode != null || message.agent != null) {
                    footerParts.add((message.mode ?: message.agent ?: "").replaceFirstChar { it.uppercase() })
                }
                if (message.modelId != null) {
                    footerParts.add(message.modelId!!)
                }
                if (durationStr != null) {
                    footerParts.add(durationStr)
                }
                if (footerParts.isNotEmpty()) {
                    Text(
                        footerParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 0.dp, top = 4.dp, end = 0.dp, bottom = 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedReasoning(text: String, isStreaming: Boolean, startMs: Long = 0, endMs: Long = 0) {
    var expanded by remember { mutableStateOf(false) }

    // Extract title from **Bold Title** pattern (like OpenAI reasoning summaries)
    val title = remember(text) {
        val match = Regex("""^\*\*([^*\n]+)\*\*(?:\r?\n\r?\n|$)""").find(text.trim())
        match?.groupValues?.get(1)?.trim()
    }
    // Body = text without the title prefix
    val body = remember(text, title) {
        if (title != null) text.trim().substringAfter("**$title**").trim() else text.trim()
    }
    // Duration
    val durationStr = if (endMs > 0 && startMs > 0) formatDuration(endMs - startMs) else null

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                    val label = if (title != null) "Thinking: $title" else "Thinking"
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        if (expanded) "−" else "+",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    val label = buildString {
                        append("Thought")
                        if (title != null) append(": $title")
                        if (durationStr != null) append(" · $durationStr")
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && !isStreaming,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                com.opencode.app.ui.components.MarkdownText(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun toolDisplayName(tool: String): String = when (tool) {
    "bash" -> "Shell"
    "read" -> "Read"
    "write" -> "Write"
    "edit" -> "Edit"
    "apply_patch" -> "Patch"
    "glob" -> "Glob"
    "grep" -> "Grep"
    "list" -> "List"
    "task" -> "Task"
    "webfetch" -> "WebFetch"
    "websearch" -> "Web Search"
    else -> tool.replaceFirstChar { it.uppercase() }
}

@Composable
private fun MessagePartContent(part: MessagePart, isStreaming: Boolean) {
    when (part) {
        is MessagePart.Text -> {
            com.opencode.app.ui.components.MarkdownText(
                text = part.text + if (isStreaming) "▌" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is MessagePart.Reasoning -> {
            ReasoningBlock(text = part.text)
        }
        is MessagePart.Tool -> {
            ToolCallCard(part = part)
        }
        is MessagePart.File -> {
            Text(
                "📎 ${part.filename ?: part.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is MessagePart.StepStart -> {
            // Steps are implicitly grouped, no divider needed
        }
        is MessagePart.StepFinish -> {
            // Token count shown in title bar instead
        }
        is MessagePart.Agent -> {
            AgentBadge(name = part.name)
        }
        is MessagePart.Compaction -> {
            CompactionDivider()
        }
        is MessagePart.Subtask -> {
            AgentBadge(name = "${part.agent}: ${part.description}")
        }
        is MessagePart.Snapshot -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            )
        }
        is MessagePart.Patch -> {
            if (part.files.isNotEmpty()) {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "📝 ${part.files.size} file${if (part.files.size > 1) "s" else ""} changed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    part.files.take(5).forEach { file ->
                        Text(
                            "  $file",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (part.files.size > 5) {
                        Text(
                            "  +${part.files.size - 5} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is MessagePart.Retry -> {
            Text(
                "↻ Retry (attempt ${part.attempt}): ${part.error}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    agents: List<com.opencode.app.domain.model.AgentInfo>,
    selectedAgent: String?,
    onSelectAgent: (String) -> Unit,
    providers: List<com.opencode.app.domain.model.Provider>,
    selectedModelProvider: String?,
    selectedModelId: String?,
    hiddenModels: Set<String>,
    onSelectModel: (String, String) -> Unit,
    onToggleModelVisibility: (String) -> Unit,
    voiceState: com.opencode.app.ui.components.VoiceState,
    onVoiceToggle: () -> Unit,
    onVoiceRetry: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    var showModelSheet by remember { mutableStateOf(false) }
    var showManageModels by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Agent + Model (left) | Mic (right)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.opencode.app.ui.components.AgentSelectorDropdown(
                    agents = agents,
                    selected = selectedAgent,
                    onSelect = onSelectAgent,
                )
                val defaultModel = stringResource(R.string.default_model)
                val modelName = providers
                    .flatMap { it.models }
                    .find { it.providerId == selectedModelProvider && it.id == selectedModelId }
                    ?.name ?: defaultModel
                TextButton(onClick = { showModelSheet = true }) {
                    Text(
                        modelName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                com.opencode.app.ui.components.VoiceButton(
                    state = voiceState,
                    onToggle = onVoiceToggle,
                    onRetry = onVoiceRetry,
                    onCancel = onVoiceCancel,
                )
            }

            // Text input + send
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                var textFieldValue by remember { mutableStateOf(TextFieldValue(text)) }
                // Sync external changes (voice input, etc.) — only when text differs from what user typed
                LaunchedEffect(text) {
                    if (textFieldValue.text != text) {
                        textFieldValue = TextFieldValue(text, selection = TextRange(text.length))
                    }
                }
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        onTextChange(it.text)
                    },
                    modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_placeholder)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onSend,
                modifier = Modifier.padding(bottom = 0.dp),
            ) {
                Icon(
                    if (isSending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isSending) stringResource(R.string.stop) else stringResource(R.string.send),
                )
            }
        }

        if (showModelSheet) {
            com.opencode.app.ui.components.ModelSelectorSheet(
                providers = providers,
                selectedProvider = selectedModelProvider,
                selectedModel = selectedModelId,
                hiddenModels = hiddenModels,
                onSelect = onSelectModel,
                onToggleVisibility = onToggleModelVisibility,
                onDismiss = { showModelSheet = false },
                onManageModels = { showModelSheet = false; showManageModels = true },
            )
        }
        if (showManageModels) {
            com.opencode.app.ui.components.ManageModelsDialog(
                providers = providers,
                hiddenModels = hiddenModels,
                onToggleVisibility = onToggleModelVisibility,
                onDismiss = { showManageModels = false },
            )
        }
    }
}
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
