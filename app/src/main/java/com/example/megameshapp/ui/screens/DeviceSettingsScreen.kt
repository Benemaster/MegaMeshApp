package com.example.megameshapp.ui.screens

import android.Manifest
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.megameshapp.ble.MeshBleManager
import com.example.megameshapp.ui.theme.*
import com.example.megameshapp.viewmodel.MeshViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(viewModel: MeshViewModel) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val devices by viewModel.bleManager.discoveredDevices.collectAsState()
    val settings by viewModel.deviceSettings.collectAsState()
    val connectedName by viewModel.bleManager.connectedDeviceName.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val backgroundServiceEnabled by viewModel.backgroundServiceEnabled.collectAsState()

    val isConnected = connectionState is MeshBleManager.ConnectionState.Connected
    val isScanning = connectionState is MeshBleManager.ConnectionState.Scanning
    val isConnecting = connectionState is MeshBleManager.ConnectionState.Connecting

    var showBleScanner by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }

    val view = LocalView.current
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.bleManager.startScan()
            showBleScanner = true
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setBackgroundServiceEnabled(true)
        }
    }

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
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Geräte-Einstellungen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (isConnected) "Verbunden mit ${connectedName ?: "Gerät"}" else "Kein Gerät verbunden",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) GreenOnline else TextMuted
                    )
                }

                if (isConnected) {
                    IconButton(onClick = { viewModel.requestSettings() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = BluePrimary)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connection section
            item {
                SectionHeader("Verbindung")
            }

            item {
                ConnectionCard(
                    isConnected = isConnected,
                    isScanning = isScanning,
                    isConnecting = isConnecting,
                    connectedName = connectedName,
                    onConnect = {
                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permissionLauncher.launch(permissions)
                    },
                    onDisconnect = { viewModel.bleManager.disconnect() }
                )
            }

            // BLE Scanner
            if (showBleScanner && !isConnected) {
                item {
                    BleScannerCard(
                        devices = devices,
                        isScanning = isScanning,
                        onDeviceSelected = { address ->
                            viewModel.bleManager.connect(address)
                            showBleScanner = false
                        },
                        onStopScan = {
                            viewModel.bleManager.stopScan()
                            showBleScanner = false
                        }
                    )
                }
            }

            if (isConnected) {
                // Device Info
                item { SectionHeader("Geräte-Info") }
                item { DeviceInfoCard(settings) }

                // LoRa Settings
                item { SectionHeader("LoRa-Konfiguration") }
                item { LoraSettingsCard(settings, onTxPowerChanged = { viewModel.setTxPower(it) }) }

                // Mesh Settings
                item { SectionHeader("Mesh-Einstellungen") }
                item {
                    MeshSettingsCard(
                        settings = settings,
                        onMaxHopsChanged = { viewModel.setMaxHops(it) },
                        onWeatherModeChanged = { viewModel.setWeatherMode(it) },
                        onReliableSendChanged = { viewModel.setReliableSend(it) },
                        onSleepModeChanged = { viewModel.setSleepMode(it) }
                    )
                }

                // Encryption
                item { SectionHeader("Verschlüsselung") }
                item {
                    EncryptionCard(
                        settings = settings,
                        onGenerateKey = { viewModel.generateKey() },
                        onShowKeyDialog = { showKeyDialog = true }
                    )
                }

                // Background Service
                item { SectionHeader("Hintergrund-Service") }
                item {
                    BackgroundServiceCard(
                        isEnabled = backgroundServiceEnabled,
                        isConnected = isConnected,
                        onToggle = { enabled ->
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setBackgroundServiceEnabled(enabled)
                            }
                        }
                    )
                }

                // Raw Log
                item { SectionHeader("Geräte-Log") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Geräte-Log", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { showLog = !showLog }) {
                                    Text(
                                        if (showLog) "Ausblenden" else "Anzeigen (${logLines.size} Zeilen)",
                                        color = BluePrimary
                                    )
                                }
                            }

                            if (showLog) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkBackground)
                                        .padding(8.dp)
                                ) {
                                    LazyColumn {
                                        items(logLines.takeLast(100)) { line ->
                                            Text(
                                                line,
                                                color = TextSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Peer key dialog
    if (showKeyDialog) {
        PeerKeyDialog(
            onDismiss = { showKeyDialog = false },
            onSetKey = { nodeId, key -> viewModel.setPeerKey(nodeId, key) },
            onDeleteKey = { nodeId -> viewModel.deletePeerKey(nodeId) }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        color = BluePrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun ConnectionCard(
    isConnected: Boolean,
    isScanning: Boolean,
    isConnecting: Boolean,
    connectedName: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val view = LocalView.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) GreenOnline.copy(alpha = 0.15f) else RedOffline.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isConnected) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isConnected) GreenOnline else RedOffline,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isConnected) "Verbunden" else if (isConnecting) "Verbinde..." else "Getrennt",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isConnected && connectedName != null) {
                        Text(connectedName, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (isConnected) onDisconnect() else onConnect()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) RedOffline else BluePrimary
                    ),
                    enabled = !isConnecting
                ) {
                    if (isConnecting || isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isConnected) "Trennen" else if (isScanning) "Suche..." else "Verbinden")
                }
            }
        }
    }
}

@Composable
fun BleScannerCard(
    devices: List<MeshBleManager.BleDeviceInfo>,
    isScanning: Boolean,
    onDeviceSelected: (String) -> Unit,
    onStopScan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BluePrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "Gefundene Geräte (${devices.size})",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onStopScan) {
                    Text("Abbrechen", color = RedOffline)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    "Suche nach MegaMesh Geräten...",
                    color = TextMuted,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            devices.forEach { device ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onDeviceSelected(device.address) },
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = BluePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(device.address, color = TextMuted, fontSize = 12.sp)
                        }
                        Text(
                            "${device.rssi} dBm",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceInfoCard(settings: com.example.megameshapp.data.DeviceSettings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsRow("Node ID", settings.nodeId)
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))

            // Battery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            settings.batteryPercent > 80 -> Icons.Default.Favorite
                            settings.batteryPercent > 40 -> Icons.Default.FavoriteBorder
                            settings.batteryPercent > 15 -> Icons.Default.FavoriteBorder
                            else -> Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = when {
                            settings.batteryPercent > 40 -> GreenOnline
                            settings.batteryPercent > 15 -> YellowWarning
                            else -> RedOffline
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Batterie", color = TextSecondary, fontSize = 14.sp)
                }
                Text(
                    "${settings.batteryPercent}% (${settings.batteryVoltage}V)",
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Gefundene Stationen", "${settings.stations}")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Peer Keys", "${settings.peerKeys}")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Ausgangs-Buffer", "${settings.outboundBuffered}")
        }
    }
}

@Composable
fun LoraSettingsCard(settings: com.example.megameshapp.data.DeviceSettings, onTxPowerChanged: (Int) -> Unit) {
    // Use independent local state - only sync from settings on first composition
    var txPowerSlider by remember { mutableFloatStateOf(settings.loraPower.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    // Sync from device only when NOT dragging (i.e. device confirmed the new value)
    LaunchedEffect(settings.loraPower) {
        if (!isDragging) {
            txPowerSlider = settings.loraPower.toFloat()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsRow("Frequenz", "${settings.loraFreq} MHz")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Bandbreite", "${settings.loraBW} kHz")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Spreading Factor", "SF${settings.loraSF}")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))
            SettingsRow("Coding Rate", "4/${settings.loraCR}")
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 6.dp))

            // TX Power Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TX Power", color = TextSecondary, fontSize = 14.sp)
                Text(
                    "${txPowerSlider.toInt()} dBm",
                    color = BluePrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = txPowerSlider,
                onValueChange = {
                    isDragging = true
                    txPowerSlider = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    onTxPowerChanged(txPowerSlider.toInt())
                },
                valueRange = 2f..22f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = BluePrimary,
                    activeTrackColor = BluePrimary,
                    inactiveTrackColor = TextMuted.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
fun MeshSettingsCard(
    settings: com.example.megameshapp.data.DeviceSettings,
    onMaxHopsChanged: (Int) -> Unit,
    onWeatherModeChanged: (Boolean) -> Unit,
    onReliableSendChanged: (Boolean) -> Unit,
    onSleepModeChanged: (Boolean) -> Unit
) {
    var hopsSlider by remember { mutableFloatStateOf(settings.maxHops.toFloat()) }
    var isHopsDragging by remember { mutableStateOf(false) }
    // Local toggle states to prevent jump-back while command is in-flight
    var weatherModeLocal by remember { mutableStateOf(settings.weatherMode) }
    var reliableSendLocal by remember { mutableStateOf(settings.reliableSend) }
    var sleepModeLocal by remember { mutableStateOf(settings.sleepMode) }

    // Sync from device only when slider not dragging
    LaunchedEffect(settings.maxHops) {
        if (!isHopsDragging) hopsSlider = settings.maxHops.toFloat()
    }
    LaunchedEffect(settings.weatherMode) { weatherModeLocal = settings.weatherMode }
    LaunchedEffect(settings.reliableSend) { reliableSendLocal = settings.reliableSend }
    LaunchedEffect(settings.sleepMode) { sleepModeLocal = settings.sleepMode }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Max Hops
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Max Hops (TTL)", color = TextSecondary, fontSize = 14.sp)
                Text(
                    "${hopsSlider.toInt()}",
                    color = BluePrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = hopsSlider,
                onValueChange = {
                    isHopsDragging = true
                    hopsSlider = it
                },
                onValueChangeFinished = {
                    isHopsDragging = false
                    onMaxHopsChanged(hopsSlider.toInt())
                },
                valueRange = 1f..15f,
                steps = 13,
                colors = SliderDefaults.colors(
                    thumbColor = BluePrimary,
                    activeTrackColor = BluePrimary,
                    inactiveTrackColor = TextMuted.copy(alpha = 0.3f)
                )
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))

            // Weather Mode
            SettingsToggleRow(
                label = "Wetter-Modus",
                description = "Auf Wetterdaten-Anfragen antworten",
                checked = weatherModeLocal,
                onChanged = {
                    weatherModeLocal = it
                    onWeatherModeChanged(it)
                }
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))

            // Reliable Send
            SettingsToggleRow(
                label = "Reliable Send",
                description = "Nachrichten bei fehlendem ACK erneut senden",
                checked = reliableSendLocal,
                onChanged = {
                    reliableSendLocal = it
                    onReliableSendChanged(it)
                }
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))

            // Sleep Mode
            SettingsToggleRow(
                label = "Sleep Mode",
                description = "Light Sleep mit DIO1 Wakeup",
                checked = sleepModeLocal,
                onChanged = {
                    sleepModeLocal = it
                    onSleepModeChanged(it)
                }
            )
        }
    }
}

@Composable
fun EncryptionCard(
    settings: com.example.megameshapp.data.DeviceSettings,
    onGenerateKey: () -> Unit,
    onShowKeyDialog: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Eigener Key", color = TextSecondary, fontSize = 14.sp)
                    Text(
                        if (settings.personalKeyValid) "Aktiv" else "Nicht gesetzt",
                        color = if (settings.personalKeyValid) GreenOnline else YellowWarning,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onGenerateKey,
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Generieren", fontSize = 13.sp)
                }
            }

            if (settings.personalKeyValid && settings.personalKey != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        settings.personalKey,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onShowKeyDialog,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary)
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Keys verwalten")
            }
        }
    }
}

@Composable
fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            Text(description, color = TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BluePrimary,
                checkedTrackColor = BluePrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = TextMuted.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun BackgroundServiceCard(
    isEnabled: Boolean,
    isConnected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsToggleRow(
                label = "Hintergrund-Verbindung",
                description = "BLE-Verbindung im Hintergrund halten, Push-Benachrichtigungen bei neuen Nachrichten",
                checked = isEnabled,
                onChanged = onToggle
            )

            if (isEnabled) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (isConnected) GreenOnline else YellowWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) "Service aktiv • Benachrichtigungen aktiv"
                        else "Warte auf Verbindung...",
                        color = if (isConnected) GreenOnline else YellowWarning,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PeerKeyDialog(
    onDismiss: () -> Unit,
    onSetKey: (String, String) -> Unit,
    onDeleteKey: (String) -> Unit
) {
    var nodeIdInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = { Text("Keys verwalten", color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = nodeIdInput,
                    onValueChange = { nodeIdInput = it },
                    label = { Text("Node ID (z.b. 0x1A2B)", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = TextMuted,
                        cursorColor = BluePrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = BluePrimary
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Hex Key (32 chars)", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = TextMuted,
                        cursorColor = BluePrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = BluePrimary
                    )
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        if (nodeIdInput.isNotBlank()) {
                            showDeleteConfirmation = true
                        }
                    }
                ) {
                    Text("Löschen", color = RedOffline)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (nodeIdInput.isNotBlank() && keyInput.length == 32) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onSetKey(nodeIdInput, keyInput)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                ) {
                    Text("Key setzen")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = TextSecondary)
            }
        }
    )

    // Red delete confirmation
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
                Text("Peer Key löschen?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Der Encryption Key für $nodeIdInput wird dauerhaft gelöscht.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onDeleteKey(nodeIdInput)
                        showDeleteConfirmation = false
                        onDismiss()
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
                    onClick = { showDeleteConfirmation = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

