package com.opencode.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

object SyntaxColors {
    var keyword = Color(0xFFC792EA)      // purple
    var string = Color(0xFFC3E88D)       // green
    var comment = Color(0xFF546E7A)      // gray
    var number = Color(0xFFF78C6C)       // orange
    var annotation = Color(0xFFFFCB6B)   // yellow
    var function = Color(0xFF82AAFF)     // blue
    var type = Color(0xFFFFCB6B)         // yellow
    var punctuation = Color(0xFF89DDFF)  // cyan
    var plain = Color.Unspecified
}

private val KEYWORDS = mapOf(
    "kotlin" to setOf("fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "override", "private", "public", "protected", "internal", "companion", "import", "package", "return", "if", "else", "when", "for", "while", "do", "break", "continue", "null", "true", "false", "is", "in", "as", "typeof", "suspend", "inline", "operator", "infix", "tailrec", "external", "annotation", "crossinline", "noinline", "reified", "out", "in", "vararg", "lateinit", "abstract", "final", "open", "const", "init", "constructor", "by", "get", "set", "field", "it", "this", "super", "throw", "try", "catch", "finally", "where"),
    "java" to setOf("public", "private", "protected", "static", "final", "class", "interface", "enum", "abstract", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "String", "var", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "new", "this", "super", "try", "catch", "finally", "throw", "throws", "import", "package", "null", "true", "false", "instanceof", "synchronized", "volatile", "transient", "native", "default", "extends", "implements", "instanceof"),
    "python" to setOf("def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "as", "import", "from", "return", "yield", "raise", "pass", "break", "continue", "lambda", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "None", "True", "False", "self", "async", "await", "print"),
    "javascript" to setOf("var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "new", "this", "class", "extends", "super", "import", "export", "default", "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "in", "of", "void", "delete", "yield", "true", "false", "null", "undefined", "NaN"),
    "typescript" to setOf("var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "new", "this", "class", "extends", "super", "import", "export", "default", "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "in", "of", "void", "delete", "yield", "true", "false", "null", "undefined", "NaN", "type", "interface", "enum", "namespace", "declare", "public", "private", "protected", "readonly", "abstract", "as", "is", "satisfies"),
    "rust" to setOf("fn", "let", "mut", "pub", "struct", "enum", "trait", "impl", "use", "mod", "match", "if", "else", "for", "while", "loop", "return", "break", "continue", "as", "in", "ref", "move", "self", "Self", "super", "crate", "super", "where", "unsafe", "const", "static", "async", "await", "dyn", "abstract", "become", "box", "do", "final", "macro", "override", "priv", "typeof", "unsized", "virtual", "yield", "try"),
    "go" to setOf("func", "var", "const", "type", "struct", "interface", "map", "chan", "package", "import", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go", "defer", "select", "fallthrough", "goto", "nil", "true", "false"),
    "json" to setOf("true", "false", "null"),
    "yaml" to setOf("true", "false", "null", "yes", "no"),
)

fun detectLanguage(filePath: String?): String? {
    if (filePath == null) return null
    val ext = filePath.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "rs" -> "rust"
        "go" -> "go"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml", "html", "svg" -> "xml"
        "css" -> "css"
        "sh", "bash" -> "bash"
        "sql" -> "sql"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp" -> "cpp"
        "md" -> "markdown"
        "toml" -> "toml"
        "gradle" -> "groovy"
        else -> null
    }
}

fun highlightCode(code: String, language: String?): AnnotatedString {
    val lang = language ?: detectLanguage(null) ?: return AnnotatedString(code)
    val keywords = KEYWORDS[lang] ?: setOf()

    return buildAnnotatedString {
        val lines = code.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            if (lineIdx > 0) append("\n")
            highlightLine(line, lang, keywords)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightLine(
    line: String,
    lang: String,
    keywords: Set<String>,
) {
    // Comment patterns
    val commentPrefix = when (lang) {
        "python", "yaml", "toml", "bash", "sh" -> "#"
        "sql" -> "--"
        else -> "//"
    }

    // Check for full-line comment
    val trimmed = line.trimStart()
    if (trimmed.startsWith(commentPrefix) || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
        withStyle(SpanStyle(color = SyntaxColors.comment, fontStyle = FontStyle.Italic)) {
            append(line)
        }
        return
    }

    // Inline comment
    val commentIdx = line.indexOf("//")
    val codePart = if (commentIdx >= 0) line.substring(0, commentIdx) else line
    val commentPart = if (commentIdx >= 0) line.substring(commentIdx) else null

    // Tokenize the code part
    val regex = Regex("""(\/\/.*$|#.*$|--.*$|/\*.*?\*/|"[^"]*"|'[^']*'|`[^`]*`|\b\d+\.?\d*[fLdD]?\b|@\w+|\b\w+\b|[^\w\s]|\s+)""")
    var lastEnd = 0
    for (match in regex.findAll(codePart)) {
        // Append any gap
        if (match.range.first > lastEnd) {
            append(codePart.substring(lastEnd, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("//") || token.startsWith("#") || token.startsWith("--") ->
                withStyle(SpanStyle(color = SyntaxColors.comment, fontStyle = FontStyle.Italic)) { append(token) }
            token.startsWith("/*") ->
                withStyle(SpanStyle(color = SyntaxColors.comment, fontStyle = FontStyle.Italic)) { append(token) }
            token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") ->
                withStyle(SpanStyle(color = SyntaxColors.string)) { append(token) }
            token.matches(Regex("""\d+\.?\d*[fLdD]?""")) ->
                withStyle(SpanStyle(color = SyntaxColors.number)) { append(token) }
            token.startsWith("@") ->
                withStyle(SpanStyle(color = SyntaxColors.annotation)) { append(token) }
            token in keywords ->
                withStyle(SpanStyle(color = SyntaxColors.keyword, fontWeight = FontWeight.Bold)) { append(token) }
            token.matches(Regex("""[A-Z][a-zA-Z0-9]*""")) ->
                withStyle(SpanStyle(color = SyntaxColors.type)) { append(token) }
            token.matches(Regex("""[a-z]\w*\(""")) ->
                withStyle(SpanStyle(color = SyntaxColors.function)) { append(token.trimEnd('(')) ; append("(") }
            else -> append(token)
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < codePart.length) {
        append(codePart.substring(lastEnd))
    }

    // Append inline comment
    if (commentPart != null) {
        withStyle(SpanStyle(color = SyntaxColors.comment, fontStyle = FontStyle.Italic)) {
            append(commentPart)
        }
    }
}

fun highlightDiff(diffText: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = diffText.lines()
        lines.take(100).forEach { line ->
            when {
                line.startsWith("+++") -> {
                    withStyle(SpanStyle(color = SyntaxColors.type, fontWeight = FontWeight.Bold)) { append(line) }
                }
                line.startsWith("---") -> {
                    withStyle(SpanStyle(color = SyntaxColors.type, fontWeight = FontWeight.Bold)) { append(line) }
                }
                line.startsWith("+") -> {
                    withStyle(SpanStyle(color = SyntaxColors.string)) { append(line) }
                }
                line.startsWith("-") -> {
                    withStyle(SpanStyle(color = Color(0xFFEF5350))) { append(line) }
                }
                line.startsWith("@@") -> {
                    withStyle(SpanStyle(color = SyntaxColors.annotation, fontWeight = FontWeight.Bold)) { append(line) }
                }
                line.startsWith("diff ") || line.startsWith("index ") -> {
                    withStyle(SpanStyle(color = SyntaxColors.comment)) { append(line) }
                }
                else -> {
                    append(line)
                }
            }
            append("\n")
        }
        if (lines.size > 100) {
            withStyle(SpanStyle(color = SyntaxColors.comment)) {
                append("... (${lines.size - 100} more lines)")
            }
        }
    }
}
