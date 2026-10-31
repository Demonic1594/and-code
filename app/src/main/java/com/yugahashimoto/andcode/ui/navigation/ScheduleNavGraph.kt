package com.yugahashimoto.andcode.ui.navigation

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.schedule.ScheduleEditorScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleListScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleRunsScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleViewModel
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel

/**
 * The schedules: the list, one schedule's runs, and the editor that also creates them.
 *
 * Each route collects what it shows from the view models and the app itself rather than taking it
 * as values: NavHost remembers this builder, so a value passed in would stay frozen at the
 * composition that built the graph (see [activityNavGraph]).
 */
fun NavGraphBuilder.scheduleNavGraph(
    app: AndCodeApplication,
    navController: NavController,
    scheduleViewModel: ScheduleViewModel,
    settingsViewModel: SettingsViewModel,
    workspaceViewModel: WorkspaceViewModel,
    openDrawer: () -> Unit,
    /** Opens a chat, switching to the runtime that owns it first. */
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
) {
    scheduleListRoute(app, navController, scheduleViewModel, openDrawer)
    scheduleRunsRoute(app, navController, scheduleViewModel, openSessionInChat)
    scheduleEditorRoute(app, navController, scheduleViewModel, settingsViewModel, workspaceViewModel)
}

private fun NavGraphBuilder.scheduleListRoute(
    app: AndCodeApplication,
    navController: NavController,
    scheduleViewModel: ScheduleViewModel,
    openDrawer: () -> Unit,
) {
    composable(ROUTE_SCHEDULES) {
        val context = LocalContext.current
        val schedules by scheduleViewModel.schedules.collectAsState()
        val runs by scheduleViewModel.runs.collectAsState()
        val runtimeTargets by app.runtimeRegistry.targets.collectAsState()
        ScheduleListScreen(
            schedules = schedules,
            runs = runs,
            runtimeTargets = runtimeTargets,
            nextFireAt = app.scheduleManager::nextFireAt,
            onOpenDrawer = openDrawer,
            onNewSchedule = { navController.navigate(ROUTE_SCHEDULE_EDIT) { launchSingleTop = true } },
            onOpenSchedule = { scheduleId ->
                navController.navigate(scheduleDetailRoute(scheduleId)) { launchSingleTop = true }
            },
            onEdit = { scheduleId ->
                navController.navigate(scheduleEditRoute(scheduleId)) { launchSingleTop = true }
            },
            onRunNow = { scheduleId ->
                scheduleViewModel.runNow(scheduleId)
                context.showRunStarted()
            },
            onDelete = scheduleViewModel::delete,
            onToggleEnabled = scheduleViewModel::setEnabled,
            exactAlarmsAllowed = app.scheduleManager::exactAlarmsAllowed,
            // Alarms armed while the permission was missing are inexact; granting it only takes
            // effect once they are re-armed.
            onExactAlarmsGranted = app.scheduleManager::rescheduleAll,
        )
    }
}

private fun NavGraphBuilder.scheduleRunsRoute(
    app: AndCodeApplication,
    navController: NavController,
    scheduleViewModel: ScheduleViewModel,
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
) {
    composable(
        route = SCHEDULE_DETAIL_ROUTE_PATTERN,
        arguments = listOf(navArgument("scheduleId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val context = LocalContext.current
        val schedules by scheduleViewModel.schedules.collectAsState()
        val runs by scheduleViewModel.runs.collectAsState()
        val allSessions by app.catalogRepository.allSessions.collectAsState()
        val scheduleId = decodeRouteArg(backStackEntry.arguments?.getString("scheduleId").orEmpty())
        val schedule = schedules.firstOrNull { it.id == scheduleId }
        if (schedule == null) {
            // Deleted while on screen; drop back to the list.
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            ScheduleRunsScreen(
                schedule = schedule,
                runs = runs.filter { it.scheduleId == scheduleId },
                titleForSession = { sessionId ->
                    allSessions
                        .firstOrNull { it.session.id == sessionId }
                        ?.session?.title?.ifBlank { null }
                },
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(scheduleEditRoute(id)) { launchSingleTop = true } },
                onRunNow = { id ->
                    scheduleViewModel.runNow(id)
                    context.showRunStarted()
                },
                onOpenSession = { run ->
                    openSessionInChat(run.sessionId, schedule.displayName, run.runtimeId)
                },
            )
        }
    }
}

private fun NavGraphBuilder.scheduleEditorRoute(
    app: AndCodeApplication,
    navController: NavController,
    scheduleViewModel: ScheduleViewModel,
    settingsViewModel: SettingsViewModel,
    workspaceViewModel: WorkspaceViewModel,
) {
    composable(
        route = SCHEDULE_EDIT_ROUTE_PATTERN,
        arguments =
            listOf(
                navArgument(SCHEDULE_EDIT_ARG_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val schedules by scheduleViewModel.schedules.collectAsState()
        val runtimeTargets by app.runtimeRegistry.targets.collectAsState()
        val selectedRuntime by app.runtimeRegistry.selected.collectAsState()
        val settingsState by settingsViewModel.state.collectAsState()
        val workspaceState by workspaceViewModel.state.collectAsState()
        val scheduleId =
            backStackEntry.arguments?.getString(SCHEDULE_EDIT_ARG_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(::decodeRouteArg)
        ScheduleEditorScreen(
            existing = scheduleId?.let { id -> schedules.firstOrNull { it.id == id } },
            runtimeTargets = runtimeTargets,
            providers = settingsState.providers,
            workspaces = workspaceState.workspaces,
            selectedRuntimeId = selectedRuntime?.id,
            favoriteModelKeys = settingsState.favoriteModelKeys,
            recentModelKeys = settingsState.recentModelKeys,
            hiddenModelKeys = settingsState.hiddenModelKeys,
            onToggleFavorite = settingsViewModel::toggleFavoriteModel,
            onSave = { built ->
                if (scheduleId != null) {
                    scheduleViewModel.update(built)
                } else {
                    scheduleViewModel.create(built)
                }
                navController.popBackStack()
            },
            onBack = { navController.popBackStack() },
        )
    }
}

private fun Context.showRunStarted() {
    Toast.makeText(this, R.string.schedule_run_now_started, Toast.LENGTH_SHORT).show()
}
