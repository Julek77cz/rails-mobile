package cz.julek.rails.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.julek.rails.network.ChatMessage
import cz.julek.rails.network.ConnectionState
import cz.julek.rails.network.FirebaseManager
import cz.julek.rails.network.MessageRole
import kotlinx.coroutines.launch

/**
 * Terminal / Chat Screen — primary communication interface with the Orchestrator AI.
 *
 * Displays a scrolling message log (LazyColumn) and an input bar
 * for sending text messages to the Orchestrator via Firebase.
 * The Orchestrator's responses appear in real-time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    // ── Observe messages from FirebaseManager ──
    val messages by FirebaseManager.messages.collectAsState()
    val connectionState by FirebaseManager.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED

    // ── Local state ──
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
        // ── Connection status bar ──
        if (!isConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    "Nejste připojeni — nejprve se připojte z záložky Dashboard",
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Empty state
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Rails Terminal",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Zadej zprávu pro Orchestrátor",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.outline
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
            shadowElevation = 8.dp,
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
                            if (isConnected) "Napiš zprávu..." else "Připojte se pro chat...",
                            fontSize = 14.sp
                        )
                    },
                    enabled = isConnected,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
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
                    shape = RoundedCornerShape(22.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Odeslat",
                        modifier = Modifier.size(20.dp),
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
//  Chat Message Bubble
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Role label
                Text(
                    text = if (isUser) "TY" else "RAILS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
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
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
