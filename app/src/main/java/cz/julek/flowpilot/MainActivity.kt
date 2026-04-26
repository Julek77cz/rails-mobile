package cz.julek.flowpilot

import android.app.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.flowpilot.ui.theme.FlowPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowPilotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FlowPilotApp()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Main App Composable
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowPilotApp() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── State ──
    var serverAddress by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Permission states
    var hasUsageStats by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var hasOverlay by remember { mutableStateOf(checkOverlayPermission(context)) }
    var hasNotifications by remember { mutableStateOf(checkNotificationPermission(context)) }

    // Refresh permissions when activity resumes
    LaunchedEffect(Unit) {
        hasUsageStats = checkUsageStatsPermission(context)
        hasOverlay = checkOverlayPermission(context)
        hasNotifications = checkNotificationPermission(context)
    }

    // Notification permission launcher (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifications = granted
        if (!granted) {
            Toast.makeText(context, "Notifikace jsou potřeba pro běh služby na pozadí", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "FlowPilot Mobile",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Status Banner ──
            ConnectionBanner(isConnected = isConnected, isConnecting = isConnecting)

            // ── Server Address Input ──
            ServerInputField(
                address = serverAddress,
                onAddressChange = { serverAddress = it },
                enabled = !isConnected && !isConnecting
            )

            // ── Connect Button ──
            Button(
                onClick = {
                    if (isConnected) {
                        // TODO: Disconnect WebSocket
                        isConnected = false
                    } else {
                        if (serverAddress.isBlank()) {
                            errorMessage = "Zadej IP adresu a port serveru"
                            return@Button
                        }
                        if (!hasUsageStats || !hasOverlay) {
                            errorMessage = "Nejprve povol všechna oprávnění"
                            return@Button
                        }
                        // Request notification permission if needed
                        if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            return@Button
                        }
                        errorMessage = null
                        isConnecting = true
                        // TODO: Start SensorService + WebSocket connection
                        // Simulated for now — will be wired in next phase
                        isConnecting = false
                        isConnected = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isConnecting) "Připojuji..."
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // ── Divider ──
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ── Permissions Section ──
            Text(
                "Opravnění",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Permission cards
            PermissionCard(
                icon = Icons.Filled.Apps,
                title = "Přístup k využití aplikací",
                subtitle = "PACKAGE_USAGE_STATS — detekce aplikace v popředí",
                granted = hasUsageStats,
                onAction = {
                    // Open Usage Access settings
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            )

            PermissionCard(
                icon = Icons.Filled.Layers,
                title = "Zobrazení přes jiné aplikace",
                subtitle = "SYSTEM_ALERT_WINDOW — blokační overlay při intervenci",
                granted = hasOverlay,
                onAction = {
                    // Open Overlay settings for this app
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            )

            PermissionCard(
                icon = Icons.Filled.Notifications,
                title = "Notifikace",
                subtitle = "Povinné pro běh foreground služby na pozadí",
                granted = hasNotifications,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Pre-Android 13 — notifications are auto-granted
                        hasNotifications = true
                    }
                }
            )

            // ── Permission Summary ──
            val allGranted = hasUsageStats && hasOverlay && hasNotifications
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (allGranted)
                        Color(0xFF1B5E20).copy(alpha = 0.15f) // Green tint
                    else
                        Color(0xFFFF6F00).copy(alpha = 0.15f) // Orange tint
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (allGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (allGranted) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Text(
                        if (allGranted)
                            "Všechna oprávnění povolena — připraveno k připojení"
                        else
                            "Povol všechna oprávnění pro plnou funkčnost",
                        color = if (allGranted) Color(0xFF1B5E20) else Color(0xFFBF360C),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }

            // ── Info Section ──
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Info,
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1. Povol všechna oprávnění výše\n" +
                        "2. Zadej IP adresu a port tvého PC (kde běží Orchestrátor)\n" +
                        "3. Stiskni 'Připojit a Spustit senzor'\n" +
                        "4. Aplikace odešle stav displeje a aktivní app na PC\n" +
                        "5. Pokud klesne Focus Score, mobil zobrazí blokační overlay",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Version footer
            Text(
                "FlowPilot Mobile v1.0.0 — Phase 1",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  UI Components
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ConnectionBanner(isConnected: Boolean, isConnecting: Boolean) {
    val bgColor = when {
        isConnecting -> Color(0xFFE3F2FD)    // Blue tint
        isConnected -> Color(0xFFE8F5E9)    // Green tint
        else -> Color(0xFFFFEBEE)            // Red tint
    }
    val textColor = when {
        isConnecting -> Color(0xFF1565C0)
        isConnected -> Color(0xFF2E7D32)
        else -> Color(0xFFC62828)
    }
    val icon = when {
        isConnecting -> Icons.Filled.Sync
        isConnected -> Icons.Filled.Wifi
        else -> Icons.Filled.WifiOff
    }
    val text = when {
        isConnecting -> "Připojuji se k serveru..."
        isConnected -> "Připojeno — senzor aktivní"
        else -> "Odpojeno — čeká na připojení"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = textColor
            )
        }
    }
}

@Composable
fun ServerInputField(
    address: String,
    onAddressChange: (String) -> Unit,
    enabled: Boolean
) {
    Column {
        Text(
            "Orchestrátor Server",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text("IP adresa a port") },
            placeholder = { Text("192.168.1.50:3000") },
            singleLine = true,
            enabled = enabled,
            leadingIcon = {
                Icon(Icons.Filled.Dns, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            "Zadej IP adresu počítače, na kterém běží FlowPilot Orchestrátor",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

@Composable
fun PermissionCard(
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = if (granted)
                            Color(0xFF2E7D32).copy(alpha = 0.12f)
                        else
                            Color(0xFFFF6F00).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF2E7D32) else Color(0xFFFF6F00),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text
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

            // Status + Action button
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFF6F00).copy(alpha = 0.15f),
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
    // Query last 1 minute — if we get results, we have permission
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
        true // Pre-M always allowed
    }
}

fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true // Pre-13 auto-granted
    }
}
