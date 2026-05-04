package cz.julek.rails.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.rails.service.AppWatcherService

/**
 * Onboarding Screen — Guided setup for all required permissions.
 *
 * Shows on first launch and guides the user through:
 *   1. Usage Stats permission (detect foreground app)
 *   2. Accessibility Service (instant app blocking)
 *   3. Overlay permission (intervention overlays)
 *   4. Notifications
 *   5. Start monitoring
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Permission states — re-checked on resume
    var usageStatsGranted by remember { mutableStateOf<Boolean>(checkUsageStatsPermissionLocal(context)) }
    var accessibilityEnabled by remember { mutableStateOf<Boolean>(AppWatcherService.isRunning) }
    var overlayGranted by remember { mutableStateOf<Boolean>(Settings.canDrawOverlays(context)) }

    // Refresh states when screen becomes visible (user returns from settings)
    LaunchedEffect(Unit) {
        usageStatsGranted = checkUsageStatsPermissionLocal(context)
        accessibilityEnabled = AppWatcherService.isRunning
        overlayGranted = Settings.canDrawOverlays(context)
    }

    // Use a key that changes when user returns from settings to trigger refresh
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                usageStatsGranted = checkUsageStatsPermissionLocal(context)
                accessibilityEnabled = AppWatcherService.isRunning
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted = usageStatsGranted && accessibilityEnabled && overlayGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ──
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Nastavení Rails",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Pro správnou funkci potřebuje Rails několik oprávnění.\n" +
                    "Všechny je nutné povolit ručně v nastavení Androidu.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Permission Cards ──
        PermissionCard(
            icon = Icons.Outlined.DataUsage,
            title = "Využití aplikací",
            subtitle = "Detekce aktuální aplikace v popředí",
            isGranted = usageStatsGranted,
            onGrant = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        )

        PermissionCard(
            icon = Icons.Outlined.Visibility,
            title = "Služba přístupnosti",
            subtitle = "Okamžité blokování aplikací — detekce otevření",
            isGranted = accessibilityEnabled,
            isCritical = true,
            onGrant = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        )

        PermissionCard(
            icon = Icons.Outlined.PictureInPicture,
            title = "Zobrazení přes aplikace",
            subtitle = "Notifikace a overlay při intervenci",
            isGranted = overlayGranted,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        )

        PermissionCard(
            icon = Icons.Outlined.NotificationsActive,
            title = "Notifikace",
            subtitle = "Upozornění na intervenci a zprávy od AI",
            isGranted = true,  // Will request at runtime if needed
            onGrant = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Start Button ──
        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            ),
            enabled = true
        ) {
            Icon(
                if (allGranted) Icons.Outlined.CheckCircle else Icons.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (allGranted) "Spustit monitoring" else "Pokračovat (nedoporučeno)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        if (!allGranted) {
            Text(
                "⚠️ Bez všech oprávnění nebude blokování aplikací fungovat správně.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    isCritical: Boolean = false,
    onGrant: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else if (isCritical) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(400)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) Color(0xFF4CAF50)
                            else if (isCritical) Color(0xFFE53935)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isGranted) Icons.Outlined.CheckCircle else icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        lineHeight = 16.sp
                    )
                }
            }

            if (!isGranted) {
                FilledTonalButton(
                    onClick = onGrant,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isCritical) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isCritical) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text("Povolit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun checkUsageStatsPermissionLocal(context: Context): Boolean {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    val now = System.currentTimeMillis()
    // Check if we can query usage stats — if it returns empty, permission is not granted
    val stats = usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000, now)
    return stats != null && stats.isNotEmpty()
}
