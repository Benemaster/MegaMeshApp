package com.example.megameshapp.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.megameshapp.data.MeshMessage
import com.example.megameshapp.data.MeshStation
import com.example.megameshapp.ui.theme.*
import com.example.megameshapp.viewmodel.MeshViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MeshViewModel) {
    val messages by viewModel.messages.collectAsState()
    val conversationMessages by viewModel.conversationMessages.collectAsState()
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val knownNodes by viewModel.knownNodes.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val settings by viewModel.deviceSettings.collectAsState()

    val isConnected = connectionState is com.example.megameshapp.ble.MeshBleManager.ConnectionState.Connected

    // null means we're on the chat list, non-null means we're viewing a conversation
    var showConversation by remember { mutableStateOf(false) }
    var showEncryptionMenu by remember { mutableStateOf(false) }
    var showQrGenerator by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    // When user selects from the list we switch to conversation view
    fun openConversation(conversationId: String) {
        viewModel.selectConversation(conversationId)
        showConversation = true
    }

    // Back handler to go back to list
    BackHandler(enabled = showConversation) {
        showConversation = false
    }

    AnimatedContent(
        targetState = showConversation,
        transitionSpec = {
            if (targetState) {
                // Opening a conversation - slide in from right
                slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) togetherWith
                        slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(150))
            } else {
                // Going back to list - slide in from left
                slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300)) togetherWith
                        slideOutHorizontally(tween(300)) { it } + fadeOut(tween(150))
            }
        },
        label = "chat_transition"
    ) { inConversation ->
        if (!inConversation) {
            ChatListScreen(
                viewModel = viewModel,
                messages = messages,
                knownNodes = knownNodes,
                stations = stations.map { it.nodeId }.toSet(),
                localNodeId = localNodeId,
                isConnected = isConnected,
                onConversationSelected = { openConversation(it) }
            )
        } else {
            ConversationScreen(
                viewModel = viewModel,
                conversationMessages = conversationMessages,
                selectedConversation = selectedConversation,
                localNodeId = localNodeId,
                isConnected = isConnected,
                settings = settings,
                onBack = { showConversation = false },
                onShowEncryption = { showEncryptionMenu = true }
            )
        }
    }

    // Encryption menu dialog
    if (showEncryptionMenu) {
        EncryptionMenuDialog(
            viewModel = viewModel,
            selectedConversation = selectedConversation,
            settings = settings,
            onDismiss = { showEncryptionMenu = false },
            onShowQrGenerator = {
                showEncryptionMenu = false
                showQrGenerator = true
            },
            onShowQrScanner = {
                showEncryptionMenu = false
                showQrScanner = true
            }
        )
    }

    // QR Code Generator
    if (showQrGenerator) {
        QrGeneratorDialog(
            keyHex = settings.personalKey ?: "",
            nodeId = localNodeId ?: "",
            onDismiss = { showQrGenerator = false }
        )
    }

    // QR Code Scanner
    if (showQrScanner) {
        QrScannerDialog(
            onResult = { scannedData ->
                showQrScanner = false
                if (scannedData.startsWith("MEGAMESH_KEY:")) {
                    val parts = scannedData.removePrefix("MEGAMESH_KEY:").split(":")
                    if (parts.size == 2) {
                        viewModel.setPeerKey(parts[0], parts[1])
                    }
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

// ── Full-Screen Chat List ────────────────────────────────────────────

@Composable
fun ChatListScreen(
    viewModel: MeshViewModel,
    messages: List<MeshMessage>,
    knownNodes: Set<String>,
    stations: Set<String>,
    localNodeId: String?,
    isConnected: Boolean,
    onConversationSelected: (String) -> Unit
) {
    val view = LocalView.current
    val stationList by viewModel.stations.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Surface(color = DarkSurface, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nachrichten",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (isConnected) "Verbunden • ${localNodeId ?: ""}" else "Nicht verbunden",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) GreenOnline else TextMuted
                    )
                }

                // Scan Stations button
                FilledTonalButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.scanStations()
                    },
                    enabled = isConnected,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BluePrimary.copy(alpha = 0.15f),
                        contentColor = BluePrimary,
                        disabledContainerColor = TextMuted.copy(alpha = 0.1f),
                        disabledContentColor = TextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Suchen", fontSize = 13.sp)
                }
            }
        }

        // Chat list
        val allConversations = buildConversationList(messages, knownNodes, stations, localNodeId, stationList)

        if (allConversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Noch keine Unterhaltungen",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Verbinde dein Gerät und starte einen Chat\noder suche nach Stationen",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(allConversations, key = { it.id }) { conv ->
                    ChatListItem(
                        conversation = conv,
                        onClick = { onConversationSelected(conv.id) }
                    )
                    HorizontalDivider(
                        color = TextMuted.copy(alpha = 0.08f),
                        modifier = Modifier.padding(start = 72.dp)
                    )
                }
            }
        }
    }
}

data class ConversationPreview(
    val id: String,
    val displayName: String,
    val lastMessage: String?,
    val lastMessageTime: Long?,
    val isStation: Boolean,
    val isBroadcast: Boolean,
    val unread: Int = 0,
    val rssi: Float? = null,    // station RSSI for color coding
    val hops: Int? = null
)

fun rssiColor(rssi: Float): androidx.compose.ui.graphics.Color = when {
    rssi >= -70f -> GreenOnline
    rssi >= -90f -> OrangeAccent
    else -> RedOffline
}

fun buildConversationList(
    messages: List<MeshMessage>,
    knownNodes: Set<String>,
    stations: Set<String>,
    localNodeId: String?,
    stationList: List<com.example.megameshapp.data.MeshStation> = emptyList()
): List<ConversationPreview> {
    val conversations = mutableMapOf<String, ConversationPreview>()
    val stationMap = stationList.associateBy { it.nodeId }

    // Always add broadcast
    val broadcastMsgs = messages.filter {
        it.destination == "broadcast" || it.destination == "0xFFFF"
    }
    val lastBroadcast = broadcastMsgs.maxByOrNull { it.timestamp }
    conversations["broadcast"] = ConversationPreview(
        id = "broadcast",
        displayName = "📢 Broadcast",
        lastMessage = lastBroadcast?.let {
            if (it.isOutgoing) "Du: ${it.text}" else "${it.origin}: ${it.text}"
        },
        lastMessageTime = lastBroadcast?.timestamp,
        isStation = false,
        isBroadcast = true
    )

    // All known nodes
    val nodeIds = (knownNodes + stations).filter { it != localNodeId && it.isNotBlank() }
    for (nodeId in nodeIds) {
        val nodeMsgs = messages.filter { msg ->
            (msg.isOutgoing && msg.destination == nodeId) ||
                    (!msg.isOutgoing && msg.origin == nodeId && msg.destination != "broadcast" && msg.destination != "0xFFFF")
        }
        val lastMsg = nodeMsgs.maxByOrNull { it.timestamp }
        val stationInfo = stationMap[nodeId]
        val isStationNode = stations.contains(nodeId) || stationInfo != null
        // Show station status as last message if no real messages
        val lastMsgText = lastMsg?.let {
            if (it.isOutgoing) "Du: ${it.text}" else it.text
        } ?: if (isStationNode) {
            stationInfo?.let { s ->
                "Station • RSSI ${s.rssi.toInt()} dBm • ${s.hops} Hops"
            } ?: "Station gefunden"
        } else null

        conversations[nodeId] = ConversationPreview(
            id = nodeId,
            displayName = nodeId,
            lastMessage = lastMsgText,
            lastMessageTime = lastMsg?.timestamp ?: stationInfo?.lastSeen,
            isStation = isStationNode,
            isBroadcast = false,
            rssi = stationInfo?.rssi,
            hops = stationInfo?.hops
        )
    }

    // Sort: broadcast first, then stations with recent scan, then by last message time
    return conversations.values.sortedWith(
        compareByDescending<ConversationPreview> { it.isBroadcast }
            .thenByDescending { it.lastMessageTime ?: 0L }
            .thenBy { it.displayName }
    )
}

@Composable
fun ChatListItem(
    conversation: ConversationPreview,
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("dd.MM", Locale.getDefault()) }

    val timeText = conversation.lastMessageTime?.let { ts ->
        val now = System.currentTimeMillis()
        val diff = now - ts
        if (diff < 24 * 60 * 60 * 1000) {
            timeFormat.format(Date(ts))
        } else {
            dateFormat.format(Date(ts))
        }
    }

    // RSSI color coding for station entries
    val rssiColor = conversation.rssi?.let { rssiColor(it) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (conversation.isBroadcast) BluePrimary.copy(alpha = 0.2f)
                        else if (conversation.isStation) (rssiColor ?: GreenOnline).copy(alpha = 0.2f)
                        else DarkSurfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (conversation.isBroadcast) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (conversation.isStation) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = rssiColor ?: GreenOnline,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        conversation.displayName.takeLast(2).uppercase(),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        conversation.displayName,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // RSSI badge for stations
                        if (conversation.rssi != null) {
                            Surface(
                                color = (rssiColor ?: GreenOnline).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "${conversation.rssi.toInt()} dBm",
                                    color = rssiColor ?: GreenOnline,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (timeText != null) {
                            Text(
                                timeText,
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isStation) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(rssiColor ?: GreenOnline)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        conversation.lastMessage ?: if (conversation.isStation) "Station online" else "Noch keine Nachrichten",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Full-Screen Conversation View ────────────────────────────────────

@Composable
fun ConversationScreen(
    viewModel: MeshViewModel,
    conversationMessages: List<MeshMessage>,
    selectedConversation: String,
    localNodeId: String?,
    isConnected: Boolean,
    settings: com.example.megameshapp.data.DeviceSettings,
    onBack: () -> Unit,
    onShowEncryption: () -> Unit
) {
    val view = LocalView.current
    var messageText by remember { mutableStateOf("") }
    var useEncryption by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(conversationMessages.size) {
        if (conversationMessages.isNotEmpty()) {
            listState.animateScrollToItem(conversationMessages.size - 1)
        }
    }

    // Swipe right to go back
    var totalDrag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .imePadding()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 150f) {
                            onBack()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                )
            }
    ) {
        // Header
        Surface(color = DarkSurface, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Zurück",
                        tint = BluePrimary
                    )
                }

                // Avatar
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selectedConversation == "broadcast") BluePrimary.copy(alpha = 0.3f)
                            else DarkSurfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedConversation == "broadcast") {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = BluePrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            selectedConversation.takeLast(2).uppercase(),
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (selectedConversation == "broadcast") "Broadcast Chat"
                        else selectedConversation,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isConnected) "Verbunden" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) GreenOnline else TextMuted
                    )
                }

                // Traceroute button (only for direct chats, not broadcast)
                if (selectedConversation != "broadcast") {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            viewModel.sendTraceroute(selectedConversation)
                        },
                        enabled = isConnected
                    ) {
                        Icon(
                            Icons.Default.Route,
                            "Traceroute",
                            tint = if (isConnected) BluePrimary else TextMuted
                        )
                    }
                }

                // Encryption menu
                IconButton(onClick = { onShowEncryption() }) {
                    Icon(
                        Icons.Default.Lock,
                        "Verschlüsselung",
                        tint = if (useEncryption) GreenOnline else TextSecondary
                    )
                }

                // Delete chat (with confirmation)
                IconButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    showDeleteConfirmation = true
                }) {
                    Icon(Icons.Default.Delete, "Löschen", tint = TextSecondary)
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (conversationMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.MailOutline,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Noch keine Nachrichten",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (selectedConversation == "broadcast")
                                    "Nachrichten an alle Nodes erscheinen hier"
                                else
                                    "Direktnachrichten mit $selectedConversation erscheinen hier",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            items(conversationMessages) { msg ->
                MessageBubble(msg)
            }
        }

        // Input bar
        Surface(color = DarkSurface, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Encryption indicator
                if (selectedConversation != "broadcast") {
                    IconButton(
                        onClick = { useEncryption = !useEncryption },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (useEncryption) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Toggle encryption",
                            tint = if (useEncryption) GreenOnline else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (selectedConversation == "broadcast") "Broadcast Nachricht..."
                            else "Nachricht an $selectedConversation...",
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    enabled = isConnected,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = TextMuted,
                        cursorColor = BluePrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledBorderColor = TextMuted.copy(alpha = 0.3f),
                        disabledTextColor = TextMuted
                    ),
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            // Haptic feedback on send
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            when {
                                selectedConversation == "broadcast" -> {
                                    if (useEncryption) {
                                        viewModel.sendPublicEncrypted(messageText)
                                        viewModel.postFeedback("📢 Verschlüsselte Broadcast-Nachricht gesendet")
                                    } else {
                                        viewModel.sendBroadcastMessage(messageText)
                                        viewModel.postFeedback("📢 Broadcast gesendet")
                                    }
                                }
                                useEncryption -> {
                                    viewModel.sendEncryptedMessage(selectedConversation, messageText)
                                    viewModel.postFeedback("🔒 Verschlüsselte Nachricht gesendet")
                                }
                                else -> {
                                    viewModel.sendDirectMessage(selectedConversation, messageText)
                                    viewModel.postFeedback("✉️ Nachricht gesendet")
                                }
                            }
                            messageText = ""
                        }
                    },
                    enabled = isConnected && messageText.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (useEncryption && selectedConversation != "broadcast") GreenOnline else BluePrimary,
                        contentColor = TextPrimary,
                        disabledContainerColor = TextMuted.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }

    // Delete confirmation dialog (red warning)
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = DarkCard,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = RedOffline,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    "Chat löschen?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Alle Nachrichten in dieser Unterhaltung werden dauerhaft gelöscht. Diese Aktion kann nicht rückgängig gemacht werden.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        viewModel.clearConversation(selectedConversation)
                        showDeleteConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RedOffline
                    )
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Löschen", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

// ── Message Bubble ───────────────────────────────────────────────────

@Composable
fun MessageBubble(msg: MeshMessage) {
    val isOutgoing = msg.isOutgoing
    val dateFormat = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date(msg.timestamp))
    val timeStr = timeFormat.format(Date(msg.timestamp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        if (!isOutgoing) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(BluePrimaryDark),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    msg.origin.takeLast(2).uppercase(),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isOutgoing) BluePrimaryDark else DarkCard
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!isOutgoing) {
                    Text(
                        msg.origin,
                        color = BluePrimaryLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    msg.text,
                    color = TextPrimary,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Always show encryption status
                    Icon(
                        if (msg.encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (msg.encrypted) "Verschlüsselt" else "Unverschlüsselt",
                        tint = if (msg.encrypted) GreenOnline else TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    if (msg.hops != null) {
                        Text(
                            "${msg.hops}/${msg.maxHops ?: "?"}h",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (msg.rssi != null && msg.rssi != 0f) {
                        Text(
                            "${msg.rssi}dBm",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // Date + time stamp
                    Text(
                        "$dateStr $timeStr",
                        color = TextMuted,
                        fontSize = 10.sp
                    )

                    if (isOutgoing && msg.acked) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Done,
                            contentDescription = "ACK",
                            tint = GreenOnline,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        if (isOutgoing) {
            Spacer(Modifier.width(8.dp))
        }
    }
}

// ── Encryption Menu Dialog ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionMenuDialog(
    viewModel: MeshViewModel,
    selectedConversation: String,
    settings: com.example.megameshapp.data.DeviceSettings,
    onDismiss: () -> Unit,
    onShowQrGenerator: () -> Unit,
    onShowQrScanner: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var peerNodeId by remember { mutableStateOf(if (selectedConversation != "broadcast") selectedConversation else "") }
    var peerKeyHex by remember { mutableStateOf("") }
    var showDeleteKeyConfirmation by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = BluePrimary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Verschlüsselung", color = TextPrimary)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // My Key section
                item {
                    Text("Eigener Key", color = BluePrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (settings.personalKeyValid) "Key aktiv ✓" else "Kein Key gesetzt",
                                    color = if (settings.personalKeyValid) GreenOnline else YellowWarning,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        viewModel.generateKey()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Generieren", fontSize = 12.sp)
                                }
                            }

                            if (settings.personalKeyValid && settings.personalKey != null) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = DarkBackground,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        settings.personalKey,
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("MegaMesh Key", settings.personalKey))
                                            Toast.makeText(context, "Key kopiert!", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Kopieren", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = onShowQrGenerator,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.QrCode, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("QR Code", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Peer Key section
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Peer Key setzen", color = BluePrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = peerNodeId,
                                onValueChange = { peerNodeId = it },
                                label = { Text("Node ID", color = TextMuted, fontSize = 12.sp) },
                                placeholder = { Text("0x1A2B", color = TextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BluePrimary,
                                    unfocusedBorderColor = TextMuted,
                                    cursorColor = BluePrimary,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = BluePrimary
                                ),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = peerKeyHex,
                                onValueChange = { peerKeyHex = it },
                                label = { Text("Hex Key (32 chars)", color = TextMuted, fontSize = 12.sp) },
                                placeholder = { Text("0123456789ABCDEF...", color = TextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BluePrimary,
                                    unfocusedBorderColor = TextMuted,
                                    cursorColor = BluePrimary,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = BluePrimary
                                ),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (peerNodeId.isNotBlank() && peerKeyHex.length == 32) {
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            viewModel.setPeerKey(peerNodeId, peerKeyHex)
                                            Toast.makeText(context, "Key gesetzt für $peerNodeId", Toast.LENGTH_SHORT).show()
                                            peerKeyHex = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    enabled = peerNodeId.isNotBlank() && peerKeyHex.length == 32
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Key setzen", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (peerNodeId.isNotBlank()) {
                                            showDeleteKeyConfirmation = true
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedOffline),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    enabled = peerNodeId.isNotBlank()
                                ) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Löschen", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onShowQrScanner,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Scannen", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen", color = BluePrimary)
            }
        }
    )

    // Delete key confirmation (red)
    if (showDeleteKeyConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteKeyConfirmation = false },
            containerColor = DarkCard,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = RedOffline,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("Peer Key löschen?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Der Encryption Key für $peerNodeId wird gelöscht. Nachrichten von dieser Node können dann nicht mehr entschlüsselt werden.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        viewModel.deletePeerKey(peerNodeId)
                        Toast.makeText(context, "Key gelöscht für $peerNodeId", Toast.LENGTH_SHORT).show()
                        showDeleteKeyConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedOffline)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Löschen", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteKeyConfirmation = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

// ── QR Code Generator Dialog ─────────────────────────────────────────

@Composable
fun QrGeneratorDialog(
    keyHex: String,
    nodeId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrData = "MEGAMESH_KEY:$nodeId:$keyHex"
    val qrBitmap = remember(qrData) { generateQrCode(qrData) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Key teilen",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Andere Nodes können diesen QR-Code scannen, um deinen Key zu setzen",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(250.dp)
                                .padding(16.dp)
                        )
                    }
                } else {
                    Text("Kein Key vorhanden für QR-Code", color = TextMuted)
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Node: $nodeId",
                    color = BluePrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (keyHex.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            keyHex,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MegaMesh Key", keyHex))
                            Toast.makeText(context, "Key kopiert!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Key kopieren")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Schließen")
                }
            }
        }
    }
}

fun generateQrCode(data: String): Bitmap? {
    if (data.isBlank()) return null
    return try {
        val writer = QRCodeWriter()
        val size = 512
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

// ── QR Code Scanner Dialog ───────────────────────────────────────────

@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "QR-Code scannen",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    "Kamera auf einen MegaMesh Key QR-Code richten",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(16.dp))

                if (hasCameraPermission) {
                    if (scannedText == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            CameraPreviewWithScanning(
                                onBarcodeDetected = { code ->
                                    scannedText = code
                                }
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GreenOnline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Gescannt!", color = GreenOnline, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                scannedText ?: "",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onResult(scannedText ?: "") },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenOnline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Key übernehmen")
                        }
                    }
                } else {
                    Text(
                        "Kamera-Berechtigung erforderlich",
                        color = TextMuted,
                        modifier = Modifier.padding(32.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

@Composable
fun CameraPreviewWithScanning(
    onBarcodeDetected: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var detected by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val scanner = BarcodeScanning.getClient()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !detected) {
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    barcode.rawValue?.let { value ->
                                        if (!detected) {
                                            detected = true
                                            onBarcodeDetected(value)
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    // Camera binding failed
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

enum class SendMode {
    BROADCAST, DIRECT, ENCRYPTED, PUBLIC_ENC
}

