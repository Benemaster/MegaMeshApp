package com.example.megameshapp.ui.screens

import android.graphics.Color
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.megameshapp.data.MeshStation
import com.example.megameshapp.data.WeatherData
import com.example.megameshapp.ui.theme.*
import com.example.megameshapp.viewmodel.MeshViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

// ─── Dark CartoDB tile source ────────────────────────────────────────────────

private val CARTO_DARK: OnlineTileSourceBase = object : XYTileSource(
    "CartoDark",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val server = baseUrl[(x + y + zoom).toInt() % baseUrl.size]
        return "${server}${zoom}/${x}/${y}.png"
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapWeatherScreen(viewModel: MeshViewModel) {
    val weatherData by viewModel.weatherData.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val settings by viewModel.deviceSettings.collectAsState()
    val view = LocalView.current

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
                    tint = GreenPrimary,
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
            contentColor = GreenPrimary,
            indicator = { @Composable {} }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Wetterstationen", color = if (selectedTab == 0) GreenPrimary else TextSecondary) },
                icon = { Icon(Icons.Default.Thermostat, null, tint = if (selectedTab == 0) GreenPrimary else TextSecondary) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Karte", color = if (selectedTab == 1) GreenPrimary else TextSecondary) },
                icon = { Icon(Icons.Default.Map, null, tint = if (selectedTab == 1) GreenPrimary else TextSecondary) }
            )
        }

        when (selectedTab) {
            0 -> WeatherStationsTab(viewModel, weatherData, isConnected, view)
            1 -> MeshMapTab(weatherData, stations)
        }
    }
}

// ─── Weather stations tab ─────────────────────────────────────────────────────

@Composable
fun WeatherStationsTab(
    viewModel: MeshViewModel,
    weatherData: List<WeatherData>,
    isConnected: Boolean,
    view: android.view.View
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.requestWeather("all")
                    viewModel.postFeedback("🌡 Wetterdaten werden abgefragt…")
                },
                enabled = isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenPrimary,
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
                        tint = GreenPrimary,
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
                    color = GreenPrimaryLight
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
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

// ─── OSM Map tab ──────────────────────────────────────────────────────────────

@Composable
fun MeshMapTab(
    weatherData: List<WeatherData>,
    stations: List<MeshStation>
) {
    val nodesWithGps = weatherData.filter { it.latitude != null && it.longitude != null }

    Box(modifier = Modifier.fillMaxSize()) {
        if (nodesWithGps.isEmpty()) {
            // Show text-based station list when no GPS data available
            if (stations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Keine GPS-Positionen verfügbar",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Wetterstationen mit GPS-Koordinaten\nwerden auf der Karte angezeigt",
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
                    items(stations) { station ->
                        StationInfoCard(station)
                    }
                }
            }
        } else {
            OsmMapView(nodesWithGps = nodesWithGps)

            // Legend overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                color = DarkSurface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, null, tint = GreenPrimary, modifier = Modifier.size(16.dp))
                    Text("${nodesWithGps.size} Knoten", color = TextPrimary, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("OSM © CartoDB", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun OsmMapView(nodesWithGps: List<WeatherData>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(CARTO_DARK)
                setMultiTouchControls(true)
                controller.setZoom(12.0)

                // Centre on first node
                val first = nodesWithGps.first()
                controller.setCenter(GeoPoint(first.latitude!!, first.longitude!!))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            val geoPoints = nodesWithGps.map { wx ->
                Pair(wx, GeoPoint(wx.latitude!!, wx.longitude!!))
            }

            // Draw connection lines between all nodes
            if (geoPoints.size >= 2) {
                for (i in 0 until geoPoints.size - 1) {
                    for (j in i + 1 until geoPoints.size) {
                        val line = Polyline(mapView).apply {
                            setPoints(listOf(geoPoints[i].second, geoPoints[j].second))
                            outlinePaint.color = Color.parseColor("#4D2E7D32") // semi-transparent dark green
                            outlinePaint.strokeWidth = 3f
                            outlinePaint.isAntiAlias = true
                        }
                        mapView.overlays.add(line)
                    }
                }
            }

            // Draw node markers
            geoPoints.forEach { (wx, geoPoint) ->
                val marker = Marker(mapView).apply {
                    position = geoPoint
                    title = "Station ${wx.nodeId}"
                    snippet = buildString {
                        wx.temperature?.let { append("%.1f°C".format(it)) }
                        wx.humidity?.let { if (isNotEmpty()) append(" · "); append("%.0f%%".format(it)) }
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(marker)
            }

            mapView.invalidate()
        }
    )
}

// ─── Station info card (used when no GPS) ─────────────────────────────────────

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
                    Text("RSSI: ${station.rssi}", color = TextSecondary, fontSize = 12.sp)
                    Text("SNR: ${station.snr}", color = TextSecondary, fontSize = 12.sp)
                    Text("Hops: ${station.hops}", color = TextSecondary, fontSize = 12.sp)
                }
            }

            Text(timeStr, color = TextMuted, fontSize = 11.sp)
        }
    }
}
