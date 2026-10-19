package rs.novica.dobarboiler.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.Flow
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.ui.dashboard.BoostSheet
import rs.novica.dobarboiler.ui.dashboard.DashboardScreen
import rs.novica.dobarboiler.ui.dashboard.DayOverrideSheet
import rs.novica.dobarboiler.ui.dashboard.HeatByTimeSheet
import rs.novica.dobarboiler.ui.dashboard.VacationDialog
import rs.novica.dobarboiler.ui.history.HistoryScreen
import rs.novica.dobarboiler.ui.login.LoginScreen
import rs.novica.dobarboiler.ui.overrides.OverridesScreen
import rs.novica.dobarboiler.ui.planner.PlannerScreen
import rs.novica.dobarboiler.ui.presets.PresetEditScreen
import rs.novica.dobarboiler.ui.presets.PresetsScreen
import rs.novica.dobarboiler.ui.settings.SettingsAccountScreen
import rs.novica.dobarboiler.ui.settings.SettingsAdvancedScreen
import rs.novica.dobarboiler.ui.settings.SettingsHealthScreen
import rs.novica.dobarboiler.ui.settings.SettingsRootScreen
import rs.novica.dobarboiler.ui.settings.SettingsServerScreen
import rs.novica.dobarboiler.ui.settings.SettingsWindowScreen

/** Route constants (SPEC §8 route table). */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val PLANNER = "planner"
    const val PRESETS = "presets"
    const val PRESET_EDIT = "presets/edit?id={id}"
    const val OVERRIDES = "overrides"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SETTINGS_WINDOW = "settings/window"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_HEALTH = "settings/health"
    const val SETTINGS_SERVER = "settings/server"
    const val SHEET_BOOST = "boost"
    const val DIALOG_VACATION = "vacation"
    const val SHEET_DAY_OVERRIDE = "override/day"
    const val SHEET_HBT = "override/time"

    fun presetEdit(id: Long?): String = if (id == null) "presets/edit" else "presets/edit?id=$id"
}

private data class BottomItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    /** Route prefixes for which this item shows as selected. */
    val selectedPrefixes: List<String>,
)

private val bottomItems = listOf(
    BottomItem(Routes.DASHBOARD, R.string.nav_home, Icons.Filled.Home, listOf(Routes.DASHBOARD)),
    BottomItem(Routes.PLANNER, R.string.nav_planner, Icons.Filled.CalendarMonth, listOf(Routes.PLANNER)),
    BottomItem(Routes.PRESETS, R.string.nav_presets, Icons.Filled.Tune, listOf(Routes.PRESETS)),
    BottomItem(
        Routes.SETTINGS,
        R.string.nav_more,
        Icons.Filled.MoreHoriz,
        listOf(Routes.SETTINGS, Routes.HISTORY, Routes.OVERRIDES),
    ),
)

/**
 * Root scaffold: bottom [NavigationBar] with 4 items (Početna, Planer, Preseti, Još) and the
 * [NavHost]. Sheets/dialogs are `dialog()` destinations layered over the Dashboard so every
 * override flow stays within 3 taps.
 */
@Composable
fun AppRoot(
    startDestination: String,
    openHistoryEvents: Flow<Unit>,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(openHistoryEvents) {
        openHistoryEvents.collect {
            navController.navigate(Routes.HISTORY) { launchSingleTop = true }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute != Routes.LOGIN

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        val selected = item.selectedPrefixes.any { prefix ->
                            currentRoute == prefix || currentRoute?.startsWith("$prefix/") == true ||
                                (prefix == Routes.PRESETS && currentRoute?.startsWith("presets") == true)
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onDone = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenBoost = { navController.navigate(Routes.SHEET_BOOST) },
                    onOpenVacation = { navController.navigate(Routes.DIALOG_VACATION) },
                    onOpenDayOverride = { navController.navigate(Routes.SHEET_DAY_OVERRIDE) },
                    onOpenHeatByTime = { navController.navigate(Routes.SHEET_HBT) },
                    onOpenPlanner = { navController.navigate(Routes.PLANNER) },
                    onOpenOverrides = { navController.navigate(Routes.OVERRIDES) },
                    onOpenHealth = { navController.navigate(Routes.SETTINGS_HEALTH) },
                )
            }

            dialog(
                Routes.SHEET_BOOST,
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                BoostSheet(onDismiss = { navController.popBackStack() })
            }

            dialog(Routes.DIALOG_VACATION) {
                VacationDialog(onDismiss = { navController.popBackStack() })
            }

            dialog(
                Routes.SHEET_DAY_OVERRIDE,
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DayOverrideSheet(onDismiss = { navController.popBackStack() })
            }

            dialog(
                Routes.SHEET_HBT,
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                HeatByTimeSheet(onDismiss = { navController.popBackStack() })
            }

            composable(Routes.PLANNER) {
                PlannerScreen(
                    onEditPresets = { navController.navigate(Routes.PRESETS) },
                )
            }

            composable(Routes.PRESETS) {
                PresetsScreen(
                    onEdit = { id -> navController.navigate(Routes.presetEdit(id)) },
                    onNew = { navController.navigate(Routes.presetEdit(null)) },
                )
            }

            composable(
                Routes.PRESET_EDIT,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1L
                PresetEditScreen(
                    presetId = if (id >= 0) id else null,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.OVERRIDES) {
                OverridesScreen()
            }

            composable(Routes.HISTORY) {
                HistoryScreen()
            }

            composable(Routes.SETTINGS) {
                SettingsRootScreen(
                    onOpenWindow = { navController.navigate(Routes.SETTINGS_WINDOW) },
                    onOpenAdvanced = { navController.navigate(Routes.SETTINGS_ADVANCED) },
                    onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
                    onOpenHealth = { navController.navigate(Routes.SETTINGS_HEALTH) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenOverrides = { navController.navigate(Routes.OVERRIDES) },
                    onOpenServer = { navController.navigate(Routes.SETTINGS_SERVER) },
                )
            }

            composable(Routes.SETTINGS_SERVER) { SettingsServerScreen() }
            composable(Routes.SETTINGS_WINDOW) { SettingsWindowScreen() }
            composable(Routes.SETTINGS_ADVANCED) { SettingsAdvancedScreen() }
            composable(Routes.SETTINGS_ACCOUNT) {
                SettingsAccountScreen(
                    onLoggedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.SETTINGS_HEALTH) { SettingsHealthScreen() }
        }
    }
}
