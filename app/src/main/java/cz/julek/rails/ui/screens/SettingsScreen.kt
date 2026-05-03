package cz.julek.rails.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.rails.network.ConnectionState
import cz.julek.rails.network.FirebaseManager

/**
 * Settings Screen — App configuration, connection status, and info.
 *
 * Modern minimalist design with gradient accents and clean card layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scrollState = rememberScrollState()
    val connectionState by FirebaseManager.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Connection ──
        SectionHeader("Připojení")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else
                    MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Gradient icon
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = if (isConnected)
                                        listOf(Color(0xFF2E7D32), Color(0xFF66BB6A))
                                    else
                                        listOf(Color(0xFF9E9E9E), Color(0xFFBDBDBD))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            if (isConnected) "Připojeno" else "Odpojeno",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            if (isConnected) "Firebase cloud aktivní" else "Klepněte pro připojení",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color(0xFF4CAF50)
                            else Color(0xFFBDBDBD)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // ── Features ──
        SectionHeader("Funkce")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column {
                ModernSettingsRow(
                    icon = Icons.Outlined.Psychology,
                    title = "AI Hlídání",
                    subtitle = "Inteligentní detekce prokrastinace"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                ModernSettingsRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "Notifikace",
                    subtitle = "Zvuk a vibrace při intervenci"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                ModernSettingsRow(
                    icon = Icons.Outlined.Block,
                    title = "Blokování aplikací",
                    subtitle = "Automatické blokování rozptylovačů"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                ModernSettingsRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "AI Chat",
                    subtitle = "Asistent vždy po ruce"
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // ── About ──
        SectionHeader("O aplikaci")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
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
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            "Rails — AI Focus Monitor",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            "v2.2.0 — Firebase Edition",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Monitoruje tvoji produktivitu pomocí AI a pomáhá ti zůstat soustředěný. " +
                    "Sleduje aktivitu na PC i mobilu a reaguje na prokrastinaci.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Privacy & Links ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column {
                ModernSettingsRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = "Soukromí",
                    subtitle = "Všechna data zůstávají na tvém PC a Firebase"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                ModernSettingsRow(
                    icon = Icons.Outlined.Code,
                    title = "Open Source",
                    subtitle = "github.com/Julek77cz/rails"
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Reusable Components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ModernSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}
