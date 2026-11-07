package com.awaken.app.ui.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.StartupBehavior
import com.awaken.app.di.AppContainer
import com.awaken.app.ui.about.AboutScreen
import com.awaken.app.ui.alarm.AlarmSettingsScreen
import com.awaken.app.ui.history.HistoryScreen
import com.awaken.app.ui.home.HomeScreen
import com.awaken.app.ui.library.LibraryScreen
import com.awaken.app.ui.onboarding.OnboardingScreen
import com.awaken.app.ui.player.PlayerScreen
import com.awaken.app.ui.schedules.ScheduleEditScreen
import com.awaken.app.ui.schedules.SchedulesScreen
import com.awaken.app.ui.settings.SettingsScreen
import com.awaken.app.ui.splash.SplashScreen
import com.awaken.app.ui.theme.AwakenPalette
import com.awaken.app.ui.theme.AwakenTheme
import com.awaken.app.ui.theme.Palettes
import com.awaken.app.ui.update.MandatoryUpdateScreen
import com.awaken.app.ui.update.UpdateScreen
import com.awaken.core.remote.RemoteConfig
import com.awaken.core.update.UpdateDecision
import kotlinx.coroutines.launch

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val SCHEDULES = "schedules"
    const val SCHEDULE_EDIT = "schedule/{id}"
    const val ALARM_SETTINGS = "alarm_settings"
    const val UPDATE = "update"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun scheduleEdit(id: Long) = "schedule/$id"
}

private data class BarItem(val route: String, val label: String, val icon: ImageVector)

private val barItems = listOf(
    BarItem(Routes.HOME, "Home", Icons.Rounded.Home),
    BarItem(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    BarItem(Routes.PLAYER, "Player", Icons.Rounded.PlayCircle),
    BarItem(Routes.SCHEDULES, "Schedules", Icons.Rounded.Alarm),
    BarItem(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

/** Built-in palettes, then remote theme presets; remote branding colours only recolour the default theme. */
fun resolvePalette(settings: AppSettings, remote: RemoteConfig): AwakenPalette {
    val remotePalettes = remote.themes.map { AwakenPalette(it.id, it.name, androidx.compose.ui.graphics.Color(it.primary), androidx.compose.ui.graphics.Color(it.secondary), androidx.compose.ui.graphics.Color(it.background)) }
    val chosen = (Palettes.builtIn + remotePalettes).firstOrNull { it.id == settings.themeId } ?: Palettes.Gold
    val b = remote.branding
    return if (chosen.id == Palettes.Gold.id && b != null) {
        chosen.copy(
            primary = b.primary?.let { androidx.compose.ui.graphics.Color(it) } ?: chosen.primary,
            secondary = b.secondary?.let { androidx.compose.ui.graphics.Color(it) } ?: chosen.secondary,
        )
    } else chosen
}

@Composable
fun AwakenRoot(container: AppContainer, openPlayerRequested: Boolean, onOpenPlayerHandled: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val remote by container.remote.config.collectAsStateWithLifecycle()
    val ready by container.ready.collectAsStateWithLifecycle()
    val palette = resolvePalette(settings, remote)
    val scope = rememberCoroutineScope()

    var installed by remember { mutableLongStateOf(container.updates.installedVersionCode) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = container.updates.installedVersionCode
        scope.launch {
            container.updates.recheckInstalled()
            installed = container.updates.installedVersionCode
        }
    }

    // Periodic automatic update check (at most every 6 hours); the mandatory lock itself never depends on it.
    LaunchedEffect(ready, settings.autoCheckUpdates) {
        if (ready && settings.autoCheckUpdates && System.currentTimeMillis() - settings.lastUpdateCheck > 6 * 3600_000L) {
            container.updates.check()
        }
    }

    AwakenTheme(palette) {
        CompositionLocalProvider(
            LocalContainer provides container,
            LocalRemote provides remote,
            LocalHapticsEnabled provides settings.hapticsEnabled,
        ) {
            Box(Modifier.fillMaxSize().background(palette.background)) {
                if (UpdateDecision.isLocked(installed, settings.requiredVersionCode)) {
                    // Mandatory update: nothing else in the app is reachable until Android reports the new version.
                    MandatoryUpdateScreen(settings.requiredVersionCode)
                } else {
                    AppNavigation(container, settings, openPlayerRequested, onOpenPlayerHandled, ready)
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(
    container: AppContainer,
    settings: AppSettings,
    openPlayerRequested: Boolean,
    onOpenPlayerHandled: () -> Unit,
    ready: Boolean,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in barItems.map { it.route }
    var splashDone by remember { mutableStateOf(false) }

    fun go(r: String) = nav.navigate(r) { launchSingleTop = true }

    LaunchedEffect(openPlayerRequested, splashDone) {
        if (openPlayerRequested && splashDone) {
            nav.navigate(Routes.PLAYER) { launchSingleTop = true }
            onOpenPlayerHandled()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Color(0xFF0B0907), contentColor = Color.White) {
                    barItems.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1A0E00),
                                selectedTextColor = com.awaken.app.ui.theme.LocalPalette.current.primary,
                                indicatorColor = com.awaken.app.ui.theme.LocalPalette.current.primary,
                                unselectedIconColor = Color(0xFF9A8F80),
                                unselectedTextColor = Color(0xFF9A8F80),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(260)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(260)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(ready = ready, fullSplash = settings.showSplash) {
                    splashDone = true
                    val dest = when {
                        !settings.onboardingDone -> Routes.ONBOARDING
                        settings.startup == StartupBehavior.LIBRARY -> Routes.LIBRARY
                        settings.startup == StartupBehavior.SCHEDULES -> Routes.SCHEDULES
                        else -> Routes.HOME
                    }
                    nav.navigate(dest) { popUpTo(Routes.SPLASH) { inclusive = true } }
                }
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Routes.HOME) { HomeScreen(onNavigate = ::go) }
            composable(Routes.LIBRARY) { LibraryScreen() }
            composable(Routes.PLAYER) { PlayerScreen() }
            composable(Routes.SCHEDULES) { SchedulesScreen(onEdit = { go(Routes.scheduleEdit(it)) }) }
            composable(Routes.SCHEDULE_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ScheduleEditScreen(id = it.arguments?.getLong("id") ?: 0L, onClose = { nav.popBackStack() })
            }
            composable(Routes.ALARM_SETTINGS) {
                AlarmSettingsScreen(onBack = { nav.popBackStack() }, onNewAlarm = { go(Routes.scheduleEdit(-1)) })
            }
            composable(Routes.UPDATE) { UpdateScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onNavigate = ::go) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
