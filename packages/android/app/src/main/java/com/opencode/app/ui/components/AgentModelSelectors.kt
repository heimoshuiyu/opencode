package com.opencode.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opencode.app.R
import com.opencode.app.domain.model.AgentInfo
import com.opencode.app.domain.model.ModelInfo
import com.opencode.app.domain.model.Provider

@Composable
fun AgentSelectorDropdown(
    agents: List<AgentInfo>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = agents.find { it.name == selected }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.name?.replaceFirstChar { it.uppercase() } ?: "Agent",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                agent.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (agent.name == selected) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (agent.description.isNotBlank()) {
                                Text(
                                    agent.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (agent.name == selected) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    providers: List<Provider>,
    selectedProvider: String?,
    selectedModel: String?,
    hiddenModels: Set<String>,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onDismiss: () -> Unit,
    onManageModels: () -> Unit,
) {
    var search by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.search_models)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = onManageModels) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.manage_models))
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                providers.forEach { provider ->
                    val models = provider.models.filter { model ->
                        val key = "${provider.id}/${model.id}"
                        key !in hiddenModels &&
                        (search.isBlank() ||
                         model.name.contains(search, ignoreCase = true) ||
                         model.id.contains(search, ignoreCase = true) ||
                         provider.name.contains(search, ignoreCase = true))
                    }
                    if (models.isNotEmpty()) {
                        item(key = "header_${provider.id}") {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(models, key = { "${provider.id}/${it.id}" }) { model ->
                            val isSelected = provider.id == selectedProvider && model.id == selectedModel
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                onClick = { onSelect(provider.id, model.id); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            model.name.ifBlank { model.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (model.reasoningSupported) {
                                            Text(
                                                stringResource(R.string.reasoning),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManageModelsDialog(
    providers: List<Provider>,
    hiddenModels: Set<String>,
    onToggleVisibility: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.manage_models)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.search_models)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                ) {
                    providers.forEach { provider ->
                        val models = provider.models.filter {
                            search.isBlank() ||
                            it.name.contains(search, ignoreCase = true) ||
                            it.id.contains(search, ignoreCase = true)
                        }
                        if (models.isNotEmpty()) {
                            item(key = "mgr_header_${provider.id}") {
                                val providerKeys = models.map { "${provider.id}/${it.id}" }
                                val providerAllVisible = providerKeys.all { it !in hiddenModels }
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 0.dp, top = 6.dp, end = 0.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = providerAllVisible,
                                        onCheckedChange = { turnOn ->
                                            providerKeys.forEach { key ->
                                                val isHidden = key in hiddenModels
                                                if (turnOn && isHidden) onToggleVisibility(key)
                                                else if (!turnOn && !isHidden) onToggleVisibility(key)
                                            }
                                        },
                                        modifier = Modifier.scale(0.8f),
                                    )
                                }
                            }
                            items(models, key = { "mgr_${provider.id}/${it.id}" }) { model ->
                                val key = "${provider.id}/${model.id}"
                                val visible = key !in hiddenModels
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        model.name.ifBlank { model.id },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = visible,
                                        onCheckedChange = { onToggleVisibility(key) },
                                        modifier = Modifier.scale(0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
