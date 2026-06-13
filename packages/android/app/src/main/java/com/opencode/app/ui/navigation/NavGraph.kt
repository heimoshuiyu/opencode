package com.opencode.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.opencode.app.data.repository.ServerRepository
import com.opencode.app.data.settings.AppSettings
import com.opencode.app.ui.screens.chat.ChatScreen
import com.opencode.app.ui.screens.chat.ChatViewModel
import com.opencode.app.ui.screens.projects.ProjectListScreen
import com.opencode.app.ui.screens.projects.ProjectListViewModel
import com.opencode.app.ui.screens.servers.AddServerScreen
import com.opencode.app.ui.screens.servers.AddServerViewModel
import com.opencode.app.ui.screens.servers.ServerListScreen
import com.opencode.app.ui.screens.servers.ServerListViewModel
import com.opencode.app.ui.screens.sessions.SessionListScreen
import com.opencode.app.ui.screens.sessions.SessionListViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RestoreState(
    val serverId: String,
    val directory: String,
    val sessionId: String,
)

@HiltViewModel
class NavGraphViewModel @Inject constructor(
    private val settings: AppSettings,
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val _restoreState = MutableStateFlow<RestoreState?>(null)
    val restoreState: StateFlow<RestoreState?> = _restoreState

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            val last = settings.lastSession
            last.collect { session ->
                if (session != null) {
                    val server = serverRepository.getServer(session.serverId)
                    if (server != null) {
                        _restoreState.value = RestoreState(
                            serverId = session.serverId,
                            directory = session.directory,
                            sessionId = session.sessionId,
                        )
                    } else {
                        settings.clearLastSession()
                        _restoreState.value = null
                    }
                } else {
                    _restoreState.value = null
                }
                _ready.value = true
                return@collect
            }
        }
    }
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val navVm: NavGraphViewModel = hiltViewModel()
    val ready by navVm.ready.collectAsState()
    val restore by navVm.restoreState.collectAsState()

    if (!ready) return // Wait until route is determined

    // Restore full back stack: servers → projects → sessions → chat
    LaunchedEffect(restore) {
        val r = restore ?: return@LaunchedEffect
        // Navigate through each layer so back button works correctly
        navController.navigate("projects/${r.serverId}")
        navController.navigate("sessions/${r.serverId}/restored/${Uri.encode(r.directory)}")
        navController.navigate("chat/${r.serverId}/${r.sessionId}/${Uri.encode(r.directory)}")
    }

    NavHost(navController = navController, startDestination = "servers") {

        composable("servers") {
            val vm: ServerListViewModel = hiltViewModel()
            ServerListScreen(
                vm = vm,
                onAddServer = { navController.navigate("addServer") },
                onEditServer = { id -> navController.navigate("addServer?serverId=$id") },
                onServerClick = { id -> navController.navigate("projects/$id") },
            )
        }

        composable(
            route = "addServer?serverId={serverId}",
            arguments = listOf(navArgument("serverId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId")
            val vm: AddServerViewModel = hiltViewModel()
            AddServerScreen(
                vm = vm,
                serverId = serverId,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable("projects/{serverId}") { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            val vm: ProjectListViewModel = hiltViewModel()
            LaunchedEffect(serverId) { vm.load(serverId) }
            ProjectListScreen(
                vm = vm,
                serverId = serverId,
                onProjectClick = { projectId, directory ->
                    vm.openDirectory(directory)
                    navController.navigate("sessions/$serverId/${Uri.encode(projectId)}/${Uri.encode(directory)}")
                },
                onOpenDirectory = { directory ->
                    vm.openDirectory(directory)
                    navController.navigate("sessions/$serverId/manual/${Uri.encode(directory)}")
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "sessions/{serverId}/{projectId}/{directory}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("projectId") { type = NavType.StringType },
                navArgument("directory") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            val directory = backStackEntry.arguments?.getString("directory") ?: return@composable
            val vm: SessionListViewModel = hiltViewModel()
            SessionListScreen(
                vm = vm,
                serverId = serverId,
                directory = directory,
                onSessionClick = { sessionId ->
                    navController.navigate("chat/$serverId/$sessionId/${Uri.encode(directory)}")
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "chat/{serverId}/{sessionId}/{directory}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("directory") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val directory = backStackEntry.arguments?.getString("directory") ?: return@composable
            val vm: ChatViewModel = hiltViewModel()
            ChatScreen(
                vm = vm,
                serverId = serverId,
                sessionId = sessionId,
                directory = directory,
                onBack = { navController.popBackStack() },
                onOpenSubSession = { subSessionId ->
                    navController.navigate("subsession/$serverId/$subSessionId/${Uri.encode(directory)}")
                },
            )
        }

        composable(
            route = "subsession/{serverId}/{sessionId}/{directory}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("directory") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val directory = backStackEntry.arguments?.getString("directory") ?: return@composable
            val vm: ChatViewModel = hiltViewModel()
            ChatScreen(
                vm = vm,
                serverId = serverId,
                sessionId = sessionId,
                directory = directory,
                readOnly = true,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
