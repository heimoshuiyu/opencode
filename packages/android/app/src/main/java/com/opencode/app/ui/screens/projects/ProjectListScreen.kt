package com.opencode.app.ui.screens.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opencode.app.R
import com.opencode.app.domain.model.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    vm: ProjectListViewModel,
    serverId: String,
    onProjectClick: (projectId: String, directory: String) -> Unit,
    onOpenDirectory: (directory: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.server?.name ?: stringResource(R.string.projects_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Open directory by path with autocomplete
            com.opencode.app.ui.components.DirectoryInputField(
                currentPath = state.directoryInput,
                homeDirectory = state.homeDirectory,
                onPathChange = vm::updateDirectoryInput,
                onOpen = { path ->
                    val resolved = vm.resolveUserInput(path.trim())
                    if (resolved.isNotBlank()) onOpenDirectory(resolved)
                },
                listFiles = { dir -> vm.listSubdirectories(dir) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    val errorMsg = state.error
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.load(serverId) }) { Text(stringResource(R.string.retry)) }
                    }
                }
                else -> {
                    if (state.projects.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.type_path_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Opened projects section
                            if (state.openedProjects.isNotEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.opened),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                items(state.openedProjects, key = { "opened_${it.project.id}" }) { item ->
                                    ProjectCard(
                                        project = item.project,
                                        isWorking = item.isWorking,
                                        onClick = {
                                            onProjectClick(item.project.id, item.project.worktree)
                                        },
                                        onLongClick = { vm.closeDirectory(item.project.worktree) },
                                    )
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                            }

                            // Recent projects section
                            item {
                                Text(
                                    stringResource(R.string.recent_projects),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(state.projects, key = { "recent_${it.project.id}" }) { item ->
                                ProjectCard(
                                    project = item.project,
                                    isWorking = item.isWorking,
                                    onClick = {
                                        onProjectClick(item.project.id, item.project.worktree)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    isWorking: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (onLongClick != null) showMenu = true },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    project.worktree,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isWorking) {
                Text(
                    stringResource(R.string.running),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        if (onLongClick != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                onClick = { showMenu = false; onLongClick() },
            )
        }
    }
}
