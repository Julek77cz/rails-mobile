package cz.julek.rails

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.julek.rails.ui.screens.ChatScreen
import cz.julek.rails.ui.screens.DashboardScreen
import cz.julek.rails.ui.theme.RailsTheme
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════
//  Navigation Routes
// ═══════════════════════════════════════════════════════════════════════

@Serializable object DashboardRoute
@Serializable object ChatRoute

enum class BottomNavTab(
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard),
    CHAT("Terminal", Icons.Filled.Terminal),
}

// ═══════════════════════════════════════════════════════════════════════
//  Main Activity
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RailsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RailsAppShell()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  App Shell — TopBar + NavHost + BottomNavigation
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RailsAppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val currentTab = when {
        currentDestination?.hasRoute<ChatRoute>() == true -> BottomNavTab.CHAT
        else -> BottomNavTab.DASHBOARD
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentTab) {
                            BottomNavTab.DASHBOARD -> "Dashboard"
                            BottomNavTab.CHAT -> "Terminal"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 3.dp
            ) {
                BottomNavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.then(
                                    if (currentTab == tab) Modifier
                                    else Modifier
                                )
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        },
                        selected = currentDestination?.hierarchy?.any {
                            when (tab) {
                                BottomNavTab.DASHBOARD -> it.hasRoute<DashboardRoute>()
                                BottomNavTab.CHAT -> it.hasRoute<ChatRoute>()
                            }
                        } == true,
                        onClick = {
                            navController.navigate(
                                when (tab) {
                                    BottomNavTab.DASHBOARD -> DashboardRoute
                                    BottomNavTab.CHAT -> ChatRoute
                                }
                            ) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination
                                launchSingleTop = true
                                // Restore state when re-selecting a previously selected tab
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<DashboardRoute> {
                DashboardScreen()
            }
            composable<ChatRoute> {
                ChatScreen()
            }
        }
    }
}


