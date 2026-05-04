package cz.julek.rails

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
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
import cz.julek.rails.ui.screens.SettingsScreen
import cz.julek.rails.ui.theme.RailsTheme
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════
//  Navigation Routes
// ═══════════════════════════════════════════════════════════════════════

@Serializable object ChatRoute
@Serializable object StatusRoute
@Serializable object SettingsRoute

enum class BottomNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CHAT("Chat", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    STATUS("Status", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    SETTINGS("Nastavení", Icons.Filled.Settings, Icons.Outlined.Settings),
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
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RailsAppShell()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  App Shell — Clean navigation with chat as primary screen
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun RailsAppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val currentTab = when {
        currentDestination?.hasRoute<SettingsRoute>() == true -> BottomNavTab.SETTINGS
        currentDestination?.hasRoute<StatusRoute>() == true -> BottomNavTab.STATUS
        else -> BottomNavTab.CHAT
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 3.dp
            ) {
                BottomNavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontWeight = if (currentTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        selected = currentDestination?.hierarchy?.any {
                            when (tab) {
                                BottomNavTab.CHAT -> it.hasRoute<ChatRoute>()
                                BottomNavTab.STATUS -> it.hasRoute<StatusRoute>()
                                BottomNavTab.SETTINGS -> it.hasRoute<SettingsRoute>()
                            }
                        } == true,
                        onClick = {
                            navController.navigate(
                                when (tab) {
                                    BottomNavTab.CHAT -> ChatRoute
                                    BottomNavTab.STATUS -> StatusRoute
                                    BottomNavTab.SETTINGS -> SettingsRoute
                                }
                            ) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ChatRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<ChatRoute> {
                ChatScreen()
            }
            composable<StatusRoute> {
                DashboardScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen()
            }
        }
    }
}
