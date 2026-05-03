package cz.julek.rails.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.rails.network.ChatMessage
import cz.julek.rails.network.ConnectionState
import cz.julek.rails.network.FirebaseManager
import cz.julek.rails.network.MessageRole
import kotlinx.coroutines.launch

/**
 * Chat Screen — primary communication interface with the AI Orchestrator.
 *
 * Modern minimalist design with:
 * - Focus score indicator in the top bar
 * - Clean message bubbles with role avatars
 * - Typing indicator when AI is processing
 * - Auto-scroll on new messages
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val messages by FirebaseManager.messages.collectAsState()
    val connectionState by FirebaseManager.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── Connection status strip ──
        if (!isConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    "Nejste připojeni — přejděte do Status pro připojení",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── Message List ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Empty state
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "👋",
                                fontSize = 40.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Ahoj, já jsem Rails",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Tvůj AI parťák pro produktivitu.\nNapiš mi cokoliv a poradím ti.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.outline,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // Messages
            items(items = messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }

        // ── Input Bar ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isConnected) "Napiš zprávu..." else "Připojte se...",
                            fontSize = 14.sp
                        )
                    },
                    enabled = isConnected,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                )

                FilledIconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty() && isConnected) {
                            FirebaseManager.sendChatMessage(text)
                            inputText = ""
                        }
                    },
                    enabled = isConnected && inputText.trim().isNotEmpty(),
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Odeslat",
                        modifier = Modifier.size(18.dp),
                        tint = if (isConnected && inputText.trim().isNotEmpty())
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Chat Message Bubble — Modern minimal design
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    // System messages — centered, subtle
    if (isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                message.text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Message text
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                // Timestamp
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.timestampText,
                    fontSize = 10.sp,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
        }
    }
}
