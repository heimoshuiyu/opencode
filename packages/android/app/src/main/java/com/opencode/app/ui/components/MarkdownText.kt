package com.opencode.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.Heading -> Text(
                    text = renderInline(block.content, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp, end = 0.dp, bottom = 2.dp, start = 0.dp),
                )
                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.content, color),
                    style = style,
                    color = color,
                    modifier = Modifier.padding(top = 2.dp, end = 0.dp, bottom = 2.dp, start = 0.dp),
                )
                is MdBlock.ListItem -> Text(
                    text = renderInline("${block.bullet} ${block.content}", color),
                    style = style,
                    color = color,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 0.dp, bottom = 2.dp),
                )
                is MdBlock.Table -> TableView(block)
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 0.dp, bottom = 2.dp, start = 0.dp),
                ) {
                    Text(
                        text = renderInline(block.content, color.copy(alpha = 0.8f)),
                        style = style.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TableView(block: MdBlock.Table) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(modifier = Modifier.fillMaxWidth()) {
                block.headers.forEachIndexed { index, header ->
                    Text(
                        text = renderInline(header, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(block.weights.getOrElse(index) { 1f }).padding(end = 8.dp),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // Data rows
            block.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 0.dp, bottom = 2.dp, start = 0.dp)) {
                    row.forEachIndexed { index, cell ->
                        Text(
                            text = renderInline(cell, MaterialTheme.colorScheme.onSurface),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(block.weights.getOrElse(index) { 1f }).padding(end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Inline rendering ──

private fun renderInline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("***", i) -> {
                val end = text.indexOf("***", i + 3)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(text.substring(i + 3, end)) }
                    i = end + 3
                } else { append(text[i++]); }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i++]); }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Gray.copy(alpha = 0.15f))) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i++]); }
            }
            (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end > 0 && text.substring(i + 1, end).isNotBlank()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i++]); }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i++]); }
            }
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, closeBracket)) }
                        i = closeParen + 1
                    } else { append(text[i++]); }
                } else { append(text[i++]); }
            }
            else -> append(text[i++])
        }
    }
}

// ── Block parser ──

private sealed class MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock()
    data class Paragraph(val content: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String) : MdBlock()
    data class ListItem(val bullet: String, val content: String) : MdBlock()
    data class Quote(val content: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val weights: List<Float>) : MdBlock()
    data object Divider : MdBlock()
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        // Detect table: line with | and next line is separator (---|---)
        if (line.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val header = parseTableRow(line)
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                rows.add(parseTableRow(lines[i]))
                i++
            }
            val weights = calculateColumnWeights(header, rows)
            blocks.add(MdBlock.Table(header, rows, weights))
            continue
        }
        when {
            line.startsWith("```") -> {
                val language = line.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                i++
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), language))
            }
            line.startsWith("# ") -> blocks.add(MdBlock.Heading(1, line.removePrefix("# ").trim()))
            line.startsWith("## ") -> blocks.add(MdBlock.Heading(2, line.removePrefix("## ").trim()))
            line.startsWith("### ") -> blocks.add(MdBlock.Heading(3, line.removePrefix("### ").trim()))
            line.startsWith("#### ") -> blocks.add(MdBlock.Heading(4, line.removePrefix("#### ").trim()))
            line == "---" || line == "***" -> blocks.add(MdBlock.Divider)
            line.startsWith("> ") -> blocks.add(MdBlock.Quote(line.removePrefix("> ").trim()))
            line.startsWith("- ") || line.startsWith("* ") -> blocks.add(MdBlock.ListItem("•", line.substring(2).trim()))
            line.matches(Regex("^\\d+\\.\\s+.*")) -> {
                val num = line.substringBefore(".")
                blocks.add(MdBlock.ListItem("$num.", line.substringAfter(". ").trim()))
            }
            line.isBlank() -> { /* skip */ }
            else -> {
                val paraLines = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()
                    && !lines[i].startsWith("#") && !lines[i].startsWith("- ")
                    && !lines[i].startsWith("* ") && !lines[i].startsWith("```")
                    && !lines[i].startsWith("> ") && !lines[i].matches(Regex("^\\d+\\.\\s+.*"))
                    && !(lines[i].contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
                ) {
                    paraLines.add(lines[i].trim())
                    i++
                }
                blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
                continue
            }
        }
        i++
    }
    return blocks
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("-")) return false
    val parts = trimmed.split("|").filter { it.isNotBlank() }
    return parts.all { it.matches(Regex(":?-{3,}:?")) }
}

private fun parseTableRow(line: String): List<String> {
    return line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }
}

private fun calculateColumnWeights(headers: List<String>, rows: List<List<String>>): List<Float> {
    val maxCols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 1)
    return (0 until maxCols).map { col ->
        val maxLen = maxOf(
            headers.getOrNull(col)?.length ?: 0,
            rows.maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0,
        )
        (maxLen.coerceAtLeast(3)).toFloat()
    }
}
