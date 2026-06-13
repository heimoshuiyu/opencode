package com.opencode.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opencode.app.R
import com.opencode.app.data.remote.dto.PermissionRequestDTO

@Composable
fun PermissionDialog(
    request: PermissionRequestDTO,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
        title = {
            Text(
                stringResource(R.string.permission_required),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    permissionDescriptionText(request.permission),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request.patterns.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            request.patterns.take(5).forEach { pattern ->
                                Text(
                                    pattern,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                            if (request.patterns.size > 5) {
                                Text(
                                    "+${request.patterns.size - 5} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAllowOnce,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.permission_allow_once), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDeny) { Text(stringResource(R.string.permission_deny)) }
                if (request.always.isNotEmpty()) {
                    TextButton(onClick = onAllowAlways) { Text(stringResource(R.string.permission_always)) }
                }
            }
        },
    )
}

@Composable
private fun permissionDescriptionText(permission: String): String = when (permission) {
    "edit" -> stringResource(R.string.permission_edit)
    "bash" -> stringResource(R.string.permission_bash)
    "write" -> stringResource(R.string.permission_write)
    "read" -> stringResource(R.string.permission_read)
    "webfetch" -> stringResource(R.string.permission_webfetch)
    "websearch" -> stringResource(R.string.permission_websearch)
    "task" -> stringResource(R.string.permission_task)
    else -> "AI requests permission: $permission"
}
