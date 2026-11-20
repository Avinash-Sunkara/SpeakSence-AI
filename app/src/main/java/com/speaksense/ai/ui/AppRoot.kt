package com.speaksense.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.speaksense.ai.ui.screens.DashboardScreen
import com.speaksense.ai.ui.screens.HistoryScreen
import com.speaksense.ai.ui.screens.SettingsScreen
import com.speaksense.ai.ui.screens.SetupWizardScreen

private data class TopLevelDestination(val route: String, val label: String, val icon: ImageVector)

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SETUP = "setup"

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val destinations = listOf(
        TopLevelDestination(ROUTE_DASHBOARD, "Dashboard", Icons.Default.Home),
        TopLevelDestination(ROUTE_HISTORY, "History", Icons.Default.DateRange),
        TopLevelDestination(ROUTE_SETTINGS, "Settings", Icons.Default.Settings),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (destinations.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    destinations.forEach { d ->
                        NavigationBarItem(
                            selected = route == d.route,
                            onClick = {
                                navController.navigate(d.route) {
                                    popUpTo(ROUTE_DASHBOARD) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_DASHBOARD) { DashboardScreen(onOpenSetup = { navController.navigate(ROUTE_SETUP) }) }
            composable(ROUTE_HISTORY) { HistoryScreen() }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
            composable(ROUTE_SETUP) { SetupWizardScreen(onFinished = { navController.popBackStack() }) }
        }
    }
}
