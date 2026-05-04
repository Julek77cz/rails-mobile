package cz.julek.rails.ui.screens

import android.app.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.rails.network.ConnectionState
import cz.julek.rails.network.FirebaseManager
import cz.julek.rails.service.SensorService
import kotlin.math.roundToInt

/**
 * Dashboard Screen — Modern status & configuration hub.
 *
 * Firebase Edition — no IP address needed, fully automatic connection.
 * Contains: focus score widget, connect/disconnect, permission cards, info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── Connection State (observed from FirebaseManager) ──
    val connectionState by FirebaseManager.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING

    // ── Local State ──
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Permission states
    var hasUsageStats by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var hasOverlay by remember { mutableStateOf(checkOverlayPermission(context)) }
    var hasNotifications by remember { mutableStateOf(checkNotificationPermission(context)) }
    var hasBatteryOptimization by remember { mutableStateOf(checkBatteryOptimization(context)) }

    // Refresh permissions on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageStats = checkUsageStatsPermission(context)
                hasOverlay = checkOverlayPermission(context)
                hasNotifications = checkNotificationPermission(context)
                hasBatteryOptimization = checkBatteryOptimization(context)
            }
        })
    }
    LaunchedEffect(Unit) {
        hasUsageStats = checkUsageStatsPermission(context)
        hasOverlay = checkOverlayPermission(context)
        hasNotifications = checkNotificationPermission(context)
        hasBatteryOptimization = checkBatteryOptimization(context)
    }

    // Notification permission launcher
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifications = granted
        if (!granted) {
            Toast.makeText(context, "Notifikace jsou potřeba pro běh služby na pozadí", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Status Hero Card ──
        StatusHeroCard(isConnected = isConnected, isConnecting = isConnecting)

        // ── Connect Button ──
        Button(
            onClick = {
                if (isConnected) {
                    val serviceIntent = Intent(context, SensorService::class.java).apply {
                        action = SensorService.ACTION_STOP
                    }
                    context.startService(serviceIntent)
                } else {
                    if (!hasUsageStats || !hasOverlay) {
                        errorMessage = "Nejprve povol všechna oprávnění"
                        return@Button
                    }
                    if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@Button
                    }
                    errorMessage = null
                    val serviceIntent = Intent(context, SensorService::class.java).apply {
                        action = SensorService.ACTION_START
                    }
                    context.startForegroundService(serviceIntent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Filled.LinkOff else Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isConnecting) "Připojuji se k Firebase..."
                else if (isConnected) "Odpojit"
                else "Připojit a Spustit senzor",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        // ── Error message ──
        errorMessage?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                }
            }
        }

        // ── Divider ──
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        // ── Permissions Section ──
        Text(
            "Oprávnění",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Permission cards
        ModernPermissionCard(
            icon = Icons.Outlined.Apps,
            title = "Přístup k využití aplikací",
            subtitle = "Detekce aplikace v popředí",
            granted = hasUsageStats,
            onAction = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )

        ModernPermissionCard(
            icon = Icons.Outlined.Layers,
            title = "Zobrazení přes jiné aplikace",
            subtitle = "Blokační overlay při intervenci",
            granted = hasOverlay,
            onAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )

        ModernPermissionCard(
            icon = Icons.Outlined.Notifications,
            title = "Notifikace",
            subtitle = "Zvuk a vibrace při AI odpovědi",
            granted = hasNotifications,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    hasNotifications = true
                }
            }
        )

        ModernPermissionCard(
            icon = Icons.Outlined.BatteryAlert,
            title = "Neomezená baterie",
            subtitle = "Zabrání systému zabíjet službu na pozadí",
            granted = hasBatteryOptimization,
            onAction = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: open battery optimization settings directly
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallback)
                    } catch (e2: Exception) {
                        Toast.makeText(context, "Otevři nastavení baterie ručně", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        // ── Permission Summary ──
        val allGranted = hasUsageStats && hasOverlay && hasNotifications && hasBatteryOptimization
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allGranted)
                    Color(0xFF1B5E20).copy(alpha = 0.08f)
                else
                    Color(0xFFFF6F00).copy(alpha = 0.08f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (allGranted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (allGranted) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
                Text(
                    if (allGranted)
                        "Všechna oprávnění povolena — připraveno"
                    else
                        "Povol všechna oprávnění pro plnou funkčnost",
                    color = if (allGranted) Color(0xFF1B5E20) else Color(0xFFBF360C),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }

        // ── Divider ──
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        // ── Info Card ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Jak to funguje",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "1. Povol všechna oprávnění výše\n" +
                    "2. Stiskni „Připojit a Spustit senzor"\n" +
                    "3. Aplikace se připojí přes Firebase cloud\n" +
                    "4. Stav displeje a aktivní app se odesílají na PC\n" +
                    "5. AI hlídá tvou produktivitu a zasahuje při prokrastinaci",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Version footer
        Text(
            "Rails Mobile v2.2.0 — Firebase Edition",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Status Hero Card — gradient background, large icon
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun StatusHeroCard(isConnected: Boolean, isConnecting: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isConnecting -> Color(0xFFE3F2FD)
            isConnected -> Color(0xFFE8F5E9)
            else -> Color(0xFFFFEBEE)
        },
        animationSpec = tween(400),
        label = "bgColor"
    )
    val textColor = when {
        isConnecting -> Color(0xFF1565C0)
        isConnected -> Color(0xFF2E7D32)
        else -> Color(0xFFC62828)
    }
    val icon = when {
        isConnecting -> Icons.Filled.Sync
        isConnected -> Icons.Filled.CloudDone
        else -> Icons.Filled.CloudOff
    }
    val text = when {
        isConnecting -> "Připojuji se..."
        isConnected -> "Připojeno — senzor aktivní"
        else -> "Odpojeno — čeká na připojení"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gradient icon circle
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = when {
                                isConnecting -> listOf(Color(0xFF1565C0), Color(0xFF42A5F5))
                                isConnected -> listOf(Color(0xFF2E7D32), Color(0xFF66BB6A))
                                else -> listOf(Color(0xFFC62828), Color(0xFFEF5350))
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column {
                Text(
                    text = text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isConnected) "Firebase cloud připojen" else if (isConnecting) "Navazuji spojení..." else "Klepněte na tlačítko níže",
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Modern Permission Card — rounded icon, clean layout
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ModernPermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon with gradient background
            val bgColors = if (granted)
                listOf(Color(0xFF2E7D32).copy(alpha = 0.15f), Color(0xFF4CAF50).copy(alpha = 0.08f))
            else
                listOf(Color(0xFFFF6F00).copy(alpha = 0.15f), Color(0xFFFFB74D).copy(alpha = 0.08f))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(colors = bgColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF2E7D32) else Color(0xFFFF6F00),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    lineHeight = 14.sp
                )
            }

            if (granted) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Povoleno",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                FilledTonalButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFF6F00).copy(alpha = 0.12f),
                        contentColor = Color(0xFFE65100)
                    )
                ) {
                    Text("Povolit", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Permission Check Helpers
// ═══════════════════════════════════════════════════════════════════════

fun checkUsageStatsPermission(context: Context): Boolean {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    val now = System.currentTimeMillis()
    val stats = usageStatsManager?.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        now - 60_000,
        now
    )
    return stats != null && stats.isNotEmpty()
}

fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun checkBatteryOptimization(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } else {
        true
    }
}
