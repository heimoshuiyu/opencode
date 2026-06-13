package com.opencode.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.app.domain.model.MessagePart
import com.opencode.app.domain.model.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

private fun parseJsonOrNull(s: String?): JsonObject? = s?.let {
    runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
}

// Tools that never show expandable content (inline only, like TUI)
private val INLINE_ONLY_TOOLS = setOf("read", "glob", "grep", "webfetch", "websearch", "skill")

@Composable
fun ToolCallCard(part: MessagePart.Tool, onTaskClick: ((String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when (part.status) {
        ToolStatus.PENDING -> MaterialTheme.colorScheme.outline
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        ToolStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
        ToolStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    val statusIcon = when (part.status) {
        ToolStatus.PENDING -> "⏳"
        ToolStatus.RUNNING -> "⚙"
        ToolStatus.COMPLETED -> "✓"
        ToolStatus.ERROR -> "✕"
    }

    val isTask = part.tool == "task" && part.childSessionId != null
    val isInlineOnly = part.tool in INLINE_ONLY_TOOLS
    val toolLabel = toolDisplayName(part.tool)
    val toolIcon = toolIconName(part.tool)
    val toolSubtitle = extractToolSubtitle(part)

    // Can this tool be expanded?
    val canExpand = !isInlineOnly && !isTask && part.status != ToolStatus.PENDING && (
        hasExpandableContent(part) || part.error != null
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = isTask || canExpand,
                        onClick = {
                            if (isTask && onTaskClick != null) {
                                onTaskClick(part.childSessionId!!)
                            } else if (canExpand) {
                                expanded = !expanded
                            }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    toolIcon,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    statusIcon,
                    fontSize = 13.sp,
                    color = statusColor,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        toolLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                    )
                    if (toolSubtitle != null) {
                        Text(
                            toolSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isTask) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open sub-session",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ToolExpandedContent(part)
            }
        }
    }
}

@Composable
private fun ToolExpandedContent(part: MessagePart.Tool) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (part.error != null) {
            Text(
                part.error,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
            return
        }

        when (part.tool) {
            "edit" -> EditDiffView(part)
            "write" -> WriteContentView(part)
            "bash" -> ShellOutputView(part)
            "apply_patch" -> PatchView(part)
            else -> {
                // Generic: show output
                val output = part.output?.trim()
                if (!output.isNullOrBlank()) {
                    val displayText = if (output.length > 2000) output.take(2000) + "\n..." else output
                    CodeBlock(text = displayText)
                }
            }
        }
    }
}

@Composable
private fun EditDiffView(part: MessagePart.Tool) {
    val metadata = parseJsonOrNull(part.metadataJson)
    val diff = metadata?.get("diff")?.let { (it as? JsonPrimitive)?.contentOrNull }

    if (!diff.isNullOrBlank()) {
        DiffView(diffText = diff)
    } else if (!part.output.isNullOrBlank()) {
        CodeBlock(text = part.output!!.trim().take(2000))
    }
}

@Composable
private fun WriteContentView(part: MessagePart.Tool) {
    val input = parseJsonOrNull(part.inputJson)
    val content = input?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
    val filePath = input?.get("filePath")?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (!content.isNullOrBlank()) {
        val lines = content.lines()
        val displayLines = if (lines.size > 500) lines.take(500) else lines
        val displayText = displayLines.joinToString("\n")
        val highlighted = remember(content) { highlightCode(displayText, detectLanguage(filePath)) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            displayLines.forEachIndexed { index, _ ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        ),
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(32.dp).padding(end = 8.dp),
                    )
                }
            }
            // Render highlighted text in one block for performance
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(8.dp)) {
                    // Line numbers column
                    Column(modifier = Modifier.width(28.dp)) {
                        displayLines.forEachIndexed { index, _ ->
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                ),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Code column
                    Text(
                        text = highlighted,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (lines.size > 500) {
                Text(
                    "... (${lines.size - 500} more lines)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ShellOutputView(part: MessagePart.Tool) {
    val input = parseJsonOrNull(part.inputJson)
    val cmd = input?.get("command")?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (!cmd.isNullOrBlank()) {
        CodeBlock(text = "$ $cmd")
        Spacer(Modifier.height(4.dp))
    }
    val output = part.output?.trim()
    if (!output.isNullOrBlank()) {
        val lines = output.lines()
        val truncated = if (lines.size > 30) lines.take(30).joinToString("\n") + "\n... (${lines.size} lines total)" else output
        CodeBlock(text = truncated)
    }
}

@Composable
private fun PatchView(part: MessagePart.Tool) {
    val metadata = parseJsonOrNull(part.metadataJson)
    val files = metadata?.get("files")
    val diff = metadata?.get("diff")?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (!diff.isNullOrBlank()) {
        DiffView(diffText = diff)
    } else if (!part.output.isNullOrBlank()) {
        CodeBlock(text = part.output!!.trim().take(2000))
    }
}

@Composable
private fun DiffView(diffText: String) {
    val highlighted = remember(diffText) { highlightDiff(diffText) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = highlighted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun hasExpandableContent(part: MessagePart.Tool): Boolean {
    val metadata = parseJsonOrNull(part.metadataJson)
    val input = parseJsonOrNull(part.inputJson)
    return when (part.tool) {
        "edit" -> metadata?.get("diff") != null || !part.output.isNullOrBlank()
        "write" -> input?.get("content") != null || !part.output.isNullOrBlank()
        "bash" -> !part.output.isNullOrBlank()
        "apply_patch" -> metadata?.get("diff") != null || !part.output.isNullOrBlank()
        else -> !part.output.isNullOrBlank()
    }
}

private fun extractToolSubtitle(part: MessagePart.Tool): String? {
    val title = part.title
    val input = parseJsonOrNull(part.inputJson)
    val metadata = parseJsonOrNull(part.metadataJson)

    return when (part.tool) {
        "bash" -> {
            val desc = input?.get("description")?.let { (it as? JsonPrimitive)?.contentOrNull }
            desc ?: title ?: input?.get("command")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }
        "read" -> {
            val fp = input?.get("filePath")?.let { (it as? JsonPrimitive)?.contentOrNull }
            fp?.substringAfterLast("/") ?: fp ?: title
        }
        "write", "edit" -> {
            val fp = input?.get("filePath")?.let { (it as? JsonPrimitive)?.contentOrNull }
            fp ?: title
        }
        "apply_patch" -> title
        "list" -> {
            val p = input?.get("path")?.let { (it as? JsonPrimitive)?.contentOrNull }
            p ?: title
        }
        "glob" -> {
            val pattern = input?.get("pattern")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val p = input?.get("path")?.let { (it as? JsonPrimitive)?.contentOrNull }
            listOfNotNull(pattern, p).joinToString(" in ")
        }
        "grep" -> {
            val pattern = input?.get("pattern")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val include = input?.get("include")?.let { (it as? JsonPrimitive)?.contentOrNull }
            listOfNotNull(pattern, include?.let { "in: $it" }).joinToString(" ")
        }
        "task" -> {
            val desc = input?.get("description")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val agent = input?.get("subagent_type")?.let { (it as? JsonPrimitive)?.contentOrNull }
            listOfNotNull(agent, desc).joinToString(": ")
        }
        "webfetch" -> {
            val url = input?.get("url")?.let { (it as? JsonPrimitive)?.contentOrNull }
            url ?: title
        }
        "websearch" -> {
            val query = input?.get("query")?.let { (it as? JsonPrimitive)?.contentOrNull }
            query ?: title
        }
        "todowrite" -> title
        "skill" -> {
            val name = input?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
            name ?: title
        }
        else -> title
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
    "todowrite" -> "Todos"
    "question" -> "Question"
    "skill" -> "Skill"
    else -> tool.replaceFirstChar { it.uppercase() }
}

private fun toolIconName(tool: String): String = when (tool) {
    "bash" -> "▶"
    "read" -> "⤓"
    "write" -> "⤒"
    "edit" -> "✎"
    "apply_patch" -> "⬚"
    "glob" -> "◉"
    "grep" -> "◉"
    "list" -> "☰"
    "webfetch" -> "⌖"
    "websearch" -> "⌖"
    "task" -> "⇄"
    "todowrite" -> "☑"
    "question" -> "?"
    "skill" -> "★"
    else -> "⚙"
}

@Composable
fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                Text("💭", fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
fun CompactionDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text(
            "Compaction",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
fun AgentBadge(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            "⇄ $name",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
