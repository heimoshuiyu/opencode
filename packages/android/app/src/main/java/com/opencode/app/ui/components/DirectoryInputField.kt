package com.opencode.app.ui.components

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun resolveInput(input: String, home: String): String {
    val trimmed = input.trim()
    val h = home.trimEnd('/')
    return when {
        trimmed.startsWith("~") -> {
            val rest = trimmed.removePrefix("~").trimStart('/')
            if (rest.isEmpty()) h else "$h/$rest"
        }
        trimmed.startsWith("/") -> trimmed
        trimmed.isBlank() -> h
        else -> "$h/$trimmed"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryInputField(
    label: String = "Open directory",
    placeholder: String = "~/project or /path/to/project",
    currentPath: String,
    homeDirectory: String = "",
    onPathChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    listFiles: suspend (dir: String) -> List<String>,
    modifier: Modifier = Modifier,
) {
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Debounced autocomplete
    LaunchedEffect(currentPath) {
        Log.d("DirAutoComplete", "currentPath changed: '$currentPath'")
        val trimmed = currentPath.trim()
        if (trimmed.isEmpty()) {
            showSuggestions = false
            return@LaunchedEffect
        }
        delay(200)
        // Resolve the input to an absolute path for parent/partial splitting
        val resolved = resolveInput(trimmed, homeDirectory)
        // If path ends with /, list contents of the full path
        // Otherwise, list contents of parent and filter by partial
        val (parent, partial) = if (resolved.endsWith("/")) {
            resolved.removeSuffix("/") to ""
        } else {
            val p = resolved.substringBeforeLast("/", "/").ifBlank { "/" }
            p to resolved.substringAfterLast("/", "")
        }
        Log.d("DirAutoComplete", "parent='$parent' partial='$partial'")
        try {
            val dirs = listFiles(parent)
            Log.d("DirAutoComplete", "listFiles('$parent') returned ${dirs.size} items: $dirs")
            suggestions = dirs
                .filter { it.startsWith(partial, ignoreCase = true) }
                .sortedWith(
                    compareBy<String> { it.equals(partial, ignoreCase = true) }
                        .thenBy { it.length }
                        .thenBy { it.lowercase() }
                )
                .take(20)
                .map { if (parent == "/") "/$it" else "$parent/$it" }
            // Convert back to user's input format for display (~/ for home-relative)
            val displaySuggestions = suggestions.map { s ->
                if (homeDirectory.isNotBlank() && s.startsWith("$homeDirectory/")) {
                    "~/" + s.removePrefix("$homeDirectory/")
                } else {
                    s
                }
            }
            suggestions = displaySuggestions
            showSuggestions = suggestions.isNotEmpty()
            Log.d("DirAutoComplete", "suggestions=$suggestions show=$showSuggestions")
        } catch (e: Exception) {
            Log.e("DirAutoComplete", "listFiles failed", e)
            suggestions = emptyList()
            showSuggestions = false
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = currentPath,
                onValueChange = {
                    onPathChange(it)
                    showSuggestions = true
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onOpen(currentPath) }),
            )
            IconButton(onClick = { onOpen(currentPath) }) {
                Icon(Icons.Default.Add, contentDescription = label)
            }
        }

        if (showSuggestions && suggestions.isNotEmpty()) {
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .zIndex(1f),
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(suggestions) { path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPathChange(path)
                                    showSuggestions = false
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
