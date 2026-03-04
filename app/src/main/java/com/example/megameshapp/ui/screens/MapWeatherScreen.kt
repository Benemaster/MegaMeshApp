package com.example.megameshapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.megameshapp.data.MeshStation
import com.example.megameshapp.data.WeatherData
import com.example.megameshapp.ui.theme.*
import com.example.megameshapp.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapWeatherScreen(viewModel: MeshViewModel) {
    val weatherData by viewModel.weatherData.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val settings by viewModel.deviceSettings.collectAsState()

    val isConnected = connectionState is com.example.megameshapp.ble.MeshBleManager.ConnectionState.Connected

    var selectedTab by remember { mutableIntStateOf(0) }

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
                    Icons.Default.Explore,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Karte & Wetter",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Wetter-Modus: ${if (settings.weatherMode) "AN" else "AUS"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.weatherMode) GreenOnline else TextMuted
                    )
                }
            }
        }

        // Tab selector
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = BluePrimary,
            indicator = { @Composable {} }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Wetterstationen", color = if (selectedTab == 0) BluePrimary else TextSecondary) },
                icon = { Icon(Icons.Default.Thermostat, null, tint = if (selectedTab == 0) BluePrimary else TextSecondary) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Node-Positionen", color = if (selectedTab == 1) BluePrimary else TextSecondary) },
                icon = { Icon(Icons.Default.LocationOn, null, tint = if (selectedTab == 1) BluePrimary else TextSecondary) }
            )
        }

        when (selectedTab) {
            0 -> WeatherStationsTab(viewModel, weatherData, isConnected)
            1 -> NodePositionsTab(weatherData, stations)
        }
    }
}

@Composable
fun WeatherStationsTab(
    viewModel: MeshViewModel,
    weatherData: List<WeatherData>,
    isConnected: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.requestWeather("all") },
                enabled = isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BluePrimary,
                    disabledContainerColor = TextMuted.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Wetter anfragen")
            }
        }

        if (weatherData.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Air,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Noch keine Wetterdaten",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Tippe auf \"Wetter anfragen\" um\nWetterstationen im Mesh abzufragen",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(weatherData) { wx ->
                    WeatherCard(wx)
                }
            }
        }
    }
}

@Composable
fun WeatherCard(wx: WeatherData) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = timeFormat.format(Date(wx.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Station ${wx.nodeId}",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(timeStr, color = TextMuted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherMetric(
                    icon = Icons.Default.Thermostat,
                    label = "Temp.",
                    value = wx.temperature?.let { "%.1f°C".format(it) } ?: "--",
                    color = OrangeAccent
                )
                WeatherMetric(
                    icon = Icons.Default.Water,
                    label = "Feuchte",
                    value = wx.humidity?.let { "%.0f%%".format(it) } ?: "--",
                    color = BluePrimaryLight
                )
                WeatherMetric(
                    icon = Icons.Default.Compress,
                    label = "Druck",
                    value = wx.pressure?.let { "%.1f hPa".format(it) } ?: "--",
                    color = GreenOnline
                )
            }

            if (wx.latitude != null && wx.longitude != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = RedOffline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "%.4f, %.4f".format(wx.latitude, wx.longitude),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherMetric(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
fun NodePositionsTab(
    weatherData: List<WeatherData>,
    stations: List<MeshStation>
) {
    val nodesWithLocation = weatherData.filter { it.latitude != null && it.longitude != null }

    if (nodesWithLocation.isEmpty() && stations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Keine Positionen verfügbar",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Fordere Wetterdaten von Stationen\nmit GPS an, um ihre Positionen zu sehen",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Nodes with GPS coordinates from weather data
            items(nodesWithLocation) { wx ->
                NodePositionCard(
                    nodeId = wx.nodeId,
                    latitude = wx.latitude!!,
                    longitude = wx.longitude!!,
                    hasWeather = true,
                    temperature = wx.temperature
                )
            }

            // Stations without GPS (from scan)
            val stationsWithoutWeatherGps = stations.filter { station ->
                nodesWithLocation.none { it.nodeId == station.nodeId }
            }
            items(stationsWithoutWeatherGps) { station ->
                StationInfoCard(station)
            }
        }
    }
}

@Composable
fun NodePositionCard(
    nodeId: String,
    latitude: Double,
    longitude: Double,
    hasWeather: Boolean,
    temperature: Float?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BluePrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Station $nodeId",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "%.4f, %.4f".format(latitude, longitude),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            if (temperature != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "%.1f°C".format(temperature),
                        color = OrangeAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StationInfoCard(station: MeshStation) {
    val timeSince = (System.currentTimeMillis() - station.lastSeen) / 1000
    val timeStr = when {
        timeSince < 60 -> "vor ${timeSince}s"
        timeSince < 3600 -> "vor ${timeSince / 60}m"
        else -> "vor ${timeSince / 3600}h"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GreenOnline.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = GreenOnline,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    station.nodeId,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "RSSI: ${station.rssi}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        "SNR: ${station.snr}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        "Hops: ${station.hops}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Text(timeStr, color = TextMuted, fontSize = 11.sp)
        }
    }
}

