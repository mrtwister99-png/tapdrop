package cz.tapdrop.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow

object NavBus {
    val route = MutableStateFlow<String?>(null)
}

private data class TabDef(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabDef("home", "Domů", Icons.Rounded.Home),
    TabDef("send", "Odeslat", Icons.Rounded.Send),
    TabDef("received", "Přijato", Icons.Rounded.Download),
    TabDef("history", "Historie", Icons.Rounded.History),
    TabDef("settings", "Nastavení", Icons.Rounded.Settings),
)

private fun titleFor(route: String?) = when (route) {
    "home" -> "TapDrop"
    "send" -> "Odeslat do notebooku"
    "received" -> "Přijato z notebooku"
    "history" -> "Historie"
    "settings" -> "Nastavení"
    "pair" -> "Spárovat s notebookem"
    else -> "TapDrop"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val pending by NavBus.route.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { r ->
            nav.navigate(r) {
                launchSingleTop = true
                popUpTo("home")
            }
            NavBus.route.value = null
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val isTab = tabs.any { it.route == current }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(titleFor(current)) },
                navigationIcon = {
                    if (!isTab && current != null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zpět")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(pad),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) },
        ) {
            composable("home") { HomeScreen(nav) }
            composable("send") { SendScreen(nav) }
            composable("received") { ReceivedScreen(snack) }
            composable("history") { HistoryScreen() }
            composable("settings") { SettingsScreen(nav, snack) }
            composable("pair") { PairScreen(nav) }
        }
    }
}
