package com.opencode.app.ui.screens.servers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.app.R
import com.opencode.app.domain.model.ServerConfig
import com.opencode.app.domain.model.ServerState
import com.opencode.app.domain.model.ServerStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    vm: ServerListViewModel,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onServerClick: (String) -> Unit,
) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.servers_title)) },
                actions = {
                    IconButton(onClick = onAddServer) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_server))
                    }
                },
            )
        },
    ) { padding ->
        if (state.servers.isEmpty()) {
            EmptyServerState(onAddServer = onAddServer, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.servers, key = { it.id }) { server ->
                    val serverState = state.states[server.id]
                    ServerCard(
                        server = server,
                        state = serverState,
                        onClick = { onServerClick(server.id) },
                        onConnect = { vm.connect(server) },
                        onDisconnect = { vm.disconnect(server.id) },
                        onEdit = { onEditServer(server.id) },
                        onDelete = { vm.deleteServer(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: ServerConfig,
    state: ServerState?,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val status = state?.status ?: ServerStatus.DISCONNECTED

    if (status == ServerStatus.CONNECTED) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CardContent(server, state, status, onConnect, onDisconnect, onEdit, onDelete, showMenu) { showMenu = it }
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            CardContent(server, state, status, onConnect, onDisconnect, onEdit, onDelete, showMenu) { showMenu = it }
        }
    }
}

@Composable
private fun CardContent(
    server: ServerConfig,
    state: ServerState?,
    status: ServerStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    showMenu: Boolean,
    setShowMenu: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state?.version != null) {
                        Text(
                            text = "v${state.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                StatusIndicator(status = status)

                IconButton(onClick = { setShowMenu(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { setShowMenu(false) }) {
                    DropdownMenuItem(
                        text = { Text(if (status == ServerStatus.CONNECTED) stringResource(R.string.disconnect) else stringResource(R.string.connect)) },
                        onClick = {
                            setShowMenu(false)
                            if (status == ServerStatus.CONNECTED) onDisconnect() else onConnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = { setShowMenu(false); onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { setShowMenu(false); onDelete() },
                    )
                }
            }

            if (status == ServerStatus.ERROR && state?.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (status != ServerStatus.CONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connect))
                }
            }
    }
}

@Composable
private fun StatusIndicator(status: ServerStatus) {
    val (color, label) = when (status) {
        ServerStatus.CONNECTED -> MaterialTheme.colorScheme.primary to stringResource(R.string.status_connected)
        ServerStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.status_connecting)
        ServerStatus.ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.status_error)
        ServerStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline to stringResource(R.string.status_offline)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color)
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyServerState(onAddServer: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_servers_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_servers_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddServer) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.add_server))
        }
    }
}
