package com.example.megameshapp.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.megameshapp.ble.MeshBleManager
import com.example.megameshapp.data.ChatPersistence
import com.example.megameshapp.data.DeviceSettings
import com.example.megameshapp.data.MeshMessage
import com.example.megameshapp.data.MeshStation
import com.example.megameshapp.data.WeatherData
import com.example.megameshapp.service.MeshBleService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MeshVM"
    }

    val bleManager = MeshBleManager(application.applicationContext)
    val chatPersistence = ChatPersistence(application.applicationContext)

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _stations = MutableStateFlow<List<MeshStation>>(emptyList())
    val stations: StateFlow<List<MeshStation>> = _stations.asStateFlow()

    private val _weatherData = MutableStateFlow<List<WeatherData>>(emptyList())
    val weatherData: StateFlow<List<WeatherData>> = _weatherData.asStateFlow()

    private val _deviceSettings = MutableStateFlow(DeviceSettings())
    val deviceSettings: StateFlow<DeviceSettings> = _deviceSettings.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** One-shot user feedback messages (Snackbar / Toast text) */
    private val _userFeedback = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userFeedback: SharedFlow<String> = _userFeedback.asSharedFlow()

    private val _localNodeId = MutableStateFlow<String?>(null)
    val localNodeId: StateFlow<String?> = _localNodeId.asStateFlow()

    // Background service
    private val appPrefs = application.getSharedPreferences("megamesh_app_prefs", Context.MODE_PRIVATE)
    private val _backgroundServiceEnabled = MutableStateFlow(
        appPrefs.getBoolean("background_service_enabled", false)
    )
    val backgroundServiceEnabled: StateFlow<Boolean> = _backgroundServiceEnabled.asStateFlow()

    /**
     * Currently selected conversation: "broadcast" or a node ID like "0x1A2B"
     */
    private val _selectedConversation = MutableStateFlow("broadcast")
    val selectedConversation: StateFlow<String> = _selectedConversation.asStateFlow()

    /**
     * Set of all known node IDs seen from messages or discovered stations
     */
    private val _knownNodes = MutableStateFlow<Set<String>>(emptySet())
    val knownNodes: StateFlow<Set<String>> = _knownNodes.asStateFlow()

    /**
     * Messages filtered for the currently selected conversation
     */
    val conversationMessages: StateFlow<List<MeshMessage>> = combine(
        _messages,
        _selectedConversation,
        _localNodeId
    ) { allMessages, conversation, localId ->
        if (conversation == "broadcast") {
            allMessages.filter {
                it.destination == "broadcast" || it.destination == "0xFFFF"
            }
        } else {
            allMessages.filter { msg ->
                val otherNode = conversation
                (msg.isOutgoing && msg.destination == otherNode) ||
                (!msg.isOutgoing && msg.origin == otherNode && msg.destination != "broadcast" && msg.destination != "0xFFFF") ||
                (!msg.isOutgoing && msg.origin == otherNode && (msg.destination == localId || msg.destination == "broadcast"))
            }.filter { msg ->
                // For DM conversations, only show DMs (not broadcast)
                if (msg.destination == "broadcast" || msg.destination == "0xFFFF") false
                else true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Load persisted messages
        _messages.value = chatPersistence.loadAllMessages()
        rebuildKnownNodes()

        viewModelScope.launch {
            bleManager.receivedData.collect { line ->
                if (line.isNotEmpty()) {
                    parseLine(line)
                }
            }
        }

        // Auto-request settings when connected
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state is MeshBleManager.ConnectionState.Connected) {
                    val name = bleManager.connectedDeviceName.value ?: "Gerät"
                    postFeedback("✅ Verbunden mit $name")
                    kotlinx.coroutines.delay(500)
                    bleManager.sendCommand("/settings")
                    kotlinx.coroutines.delay(300)
                    bleManager.sendCommand("/id")
                } else if (state is MeshBleManager.ConnectionState.Disconnected) {
                    postFeedback("🔌 Verbindung getrennt")
                }
            }
        }
    }

    private fun rebuildKnownNodes() {
        val nodes = mutableSetOf<String>()
        for (msg in _messages.value) {
            if (!msg.isOutgoing && msg.origin.isNotBlank()) {
                nodes.add(msg.origin)
            }
            if (msg.destination != "broadcast" && msg.destination != "0xFFFF" && msg.destination.isNotBlank()) {
                val localId = _localNodeId.value
                if (msg.destination != localId) {
                    nodes.add(msg.destination)
                }
            }
        }
        for (station in _stations.value) {
            nodes.add(station.nodeId)
        }
        _knownNodes.value = nodes
    }

    private fun addKnownNode(nodeId: String) {
        if (nodeId.isNotBlank() && nodeId != "broadcast" && nodeId != "0xFFFF" && nodeId != _localNodeId.value) {
            _knownNodes.value = _knownNodes.value + nodeId
        }
    }

    fun selectConversation(conversationId: String) {
        _selectedConversation.value = conversationId
    }

    private fun addLog(line: String) {
        val current = _logLines.value.toMutableList()
        current.add(line)
        if (current.size > 500) current.removeAt(0)
        _logLines.value = current
    }

    fun postFeedback(message: String) {
        viewModelScope.launch { _userFeedback.emit(message) }
    }

    private fun parseLine(line: String) {
        addLog(line)
        Log.d(TAG, "RX: $line")

        when {
            line.startsWith("{") && line.contains("\"nodeId\"") -> parseSettings(line)
            line.startsWith("Node ID: ") -> parseNodeId(line)
            line.startsWith("RX ") -> parseRxMessage(line)
            line.startsWith("TX ") -> parseTxMessage(line)
            line.startsWith("ETX ") -> parseTxMessage(line)
            line.startsWith("WEATHER ") -> parseWeatherResponse(line)
            line.startsWith("DISCOVERED ") -> parseDiscoveredStation(line)
            line.startsWith("ACK received") -> parseAck(line)
            line.startsWith("TRACEROUTE ") -> parseTraceroute(line)
        }
    }

    private fun parseSettings(line: String) {
        try {
            val json = JSONObject(line)
            _deviceSettings.value = DeviceSettings(
                nodeId = json.optString("nodeId", "Unknown"),
                maxHops = json.optInt("maxHops", 7),
                weatherMode = json.optBoolean("weatherMode", false),
                personalKeyValid = json.optBoolean("personalKeyValid", false),
                personalKey = if (json.has("personalKey")) json.getString("personalKey") else null,
                loraFreq = json.optDouble("loraFreq", 868.0).toFloat(),
                loraBW = json.optDouble("loraBW", 125.0).toFloat(),
                loraSF = json.optInt("loraSF", 9),
                loraCR = json.optInt("loraCR", 7),
                loraPower = json.optInt("loraPower", 17),
                bleConnected = json.optBoolean("bleConnected", false),
                peerKeys = json.optInt("peerKeys", 0),
                stations = json.optInt("stations", 0),
                reliableSend = json.optBoolean("reliableSend", true),
                outboundBuffered = json.optInt("outboundBuffered", 0),
                sleepMode = json.optBoolean("sleepMode", false),
                batteryVoltage = json.optDouble("batteryV", 0.0).toFloat(),
                batteryPercent = json.optInt("batteryPct", 0)
            )
            _localNodeId.value = _deviceSettings.value.nodeId
        } catch (e: Exception) {
            Log.e(TAG, "Settings parse error", e)
        }
    }

    private fun parseNodeId(line: String) {
        val id = line.removePrefix("Node ID: ").trim()
        _localNodeId.value = id
    }

    private fun parseRxMessage(line: String) {
        try {
            val parts = line.split(" ")
            var origin = ""
            var dest = ""
            var msgId = 0
            var hops = 0
            var maxHops = 7
            var rssi = 0f
            var snr = 0f
            var encrypted = false
            var text = ""

            for (part in parts) {
                when {
                    part.startsWith("origin=") -> origin = "0x${part.removePrefix("origin=")}"
                    part.startsWith("dest=") -> dest = part.removePrefix("dest=")
                    part.startsWith("msgId=") -> msgId = part.removePrefix("msgId=").toIntOrNull() ?: 0
                    part.startsWith("hops=") -> {
                        val hopParts = part.removePrefix("hops=").split("/")
                        hops = hopParts.getOrNull(0)?.toIntOrNull() ?: 0
                        maxHops = hopParts.getOrNull(1)?.toIntOrNull() ?: 7
                    }
                    part.startsWith("rssi=") -> rssi = part.removePrefix("rssi=").toFloatOrNull() ?: 0f
                    part.startsWith("snr=") -> snr = part.removePrefix("snr=").toFloatOrNull() ?: 0f
                    part.startsWith("enc=") -> encrypted = part.removePrefix("enc=") == "1"
                    part.startsWith("text=") -> {
                        val textIndex = line.indexOf("text=")
                        if (textIndex >= 0) text = line.substring(textIndex + 5)
                    }
                }
            }

            if (text.startsWith("#MESH_")) {
                if (text.startsWith("#MESH_WX_DATA:")) {
                    parseWeatherPayload(origin, text)
                }
                return
            }

            if (text.isNotEmpty()) {
                val msg = MeshMessage(
                    origin = origin,
                    destination = if (dest == "broadcast") "broadcast" else "0x$dest",
                    text = text,
                    rssi = rssi,
                    snr = snr,
                    hops = hops,
                    maxHops = maxHops,
                    encrypted = encrypted,
                    isOutgoing = false,
                    msgId = msgId
                )
                _messages.value = _messages.value + msg
                chatPersistence.saveMessage(msg)
                addKnownNode(origin)
                if (!msg.isOutgoing) {
                    postFeedback("📨 Nachricht von $origin${if (encrypted) " 🔒" else ""}")
                    notifyNewMessage(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RX parse error", e)
        }
    }

    private fun parseTxMessage(line: String) {
        try {
            var dest = "broadcast"
            var text = ""
            var msgId = 0

            val textIndex = line.indexOf("text=")
            if (textIndex >= 0) text = line.substring(textIndex + 5)

            val parts = line.split(" ")
            for (part in parts) {
                when {
                    part.startsWith("to=") -> dest = part.removePrefix("to=")
                    part.startsWith("msgId=") -> msgId = part.removePrefix("msgId=").toIntOrNull() ?: 0
                }
            }

            if (text.isNotEmpty() && !text.startsWith("#MESH_")) {
                val msg = MeshMessage(
                    origin = _localNodeId.value ?: "me",
                    destination = dest,
                    text = text,
                    isOutgoing = true,
                    msgId = msgId,
                    encrypted = line.startsWith("ETX")
                )
                _messages.value = _messages.value + msg
                chatPersistence.saveMessage(msg)
                if (dest != "broadcast") addKnownNode(dest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TX parse error", e)
        }
    }

    private fun parseWeatherResponse(line: String) {
        try {
            val dataIndex = line.indexOf("data=")
            if (dataIndex >= 0) {
                val data = line.substring(dataIndex + 5)
                val fromIndex = line.indexOf("from=")
                val fromEnd = line.indexOf(" ", fromIndex)
                val nodeId = if (fromIndex >= 0 && fromEnd > fromIndex) line.substring(fromIndex + 5, fromEnd) else "unknown"
                parseWeatherPayload(nodeId, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather parse error", e)
        }
    }

    private fun parseWeatherPayload(nodeId: String, payload: String) {
        try {
            val dataStr = payload.removePrefix("#MESH_WX_DATA:")
            val pairs = dataStr.split(",")
            var temp: Float? = null
            var hum: Float? = null
            var pressure: Float? = null
            var lat: Double? = null
            var lon: Double? = null
            var node = nodeId

            for (pair in pairs) {
                val kv = pair.split("=")
                if (kv.size == 2) {
                    when (kv[0].trim()) {
                        "node" -> node = kv[1].trim()
                        "tempC" -> temp = kv[1].trim().toFloatOrNull()
                        "hum" -> hum = kv[1].trim().toFloatOrNull()
                        "hPa" -> pressure = kv[1].trim().toFloatOrNull()
                        "lat" -> lat = kv[1].trim().toDoubleOrNull()
                        "lon" -> lon = kv[1].trim().toDoubleOrNull()
                    }
                }
            }

            val wx = WeatherData(
                nodeId = node,
                temperature = temp,
                humidity = hum,
                pressure = pressure,
                latitude = lat,
                longitude = lon
            )

            val current = _weatherData.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.nodeId == node }
            if (existingIndex >= 0) {
                current[existingIndex] = wx
            } else {
                current.add(wx)
                postFeedback("🌡 Wetterdaten von $node empfangen")
            }
            _weatherData.value = current
        } catch (e: Exception) {
            Log.e(TAG, "Weather payload parse error", e)
        }
    }

    private fun parseDiscoveredStation(line: String) {
        try {
            val parts = line.split(" ")
            var nodeId = ""
            var hops = 0
            var rssi = 0f
            var snr = 0f

            for (part in parts) {
                when {
                    part.startsWith("station=") -> nodeId = part.removePrefix("station=")
                    part.startsWith("hops=") -> hops = part.removePrefix("hops=").toIntOrNull() ?: 0
                    part.startsWith("rssi=") -> rssi = part.removePrefix("rssi=").toFloatOrNull() ?: 0f
                    part.startsWith("snr=") -> snr = part.removePrefix("snr=").toFloatOrNull() ?: 0f
                }
            }

            if (nodeId.isNotEmpty()) {
                val station = MeshStation(nodeId, System.currentTimeMillis(), rssi, snr, hops)
                val current = _stations.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.nodeId == nodeId }
                if (existingIndex >= 0) {
                    current[existingIndex] = station
                } else {
                    current.add(station)
                    postFeedback("📡 Neuer Knoten entdeckt: $nodeId")
                }
                _stations.value = current
                addKnownNode(nodeId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Station parse error", e)
        }
    }

    private fun parseAck(line: String) {
        try {
            val msgIdMatch = Regex("msgId=(\\d+)").find(line)
            val msgId = msgIdMatch?.groupValues?.get(1)?.toIntOrNull() ?: return

            val updated = _messages.value.map {
                if (it.isOutgoing && it.msgId == msgId) it.copy(acked = true) else it
            }
            _messages.value = updated
        } catch (e: Exception) {
            Log.e(TAG, "ACK parse error", e)
        }
    }

    private fun parseTraceroute(line: String) {
        // Just add to log, already handled
    }

    // ── Commands ──────────────────────────────────────────────────────────

    fun sendBroadcastMessage(text: String) {
        viewModelScope.launch {
            bleManager.sendCommand(text)
        }
    }

    fun sendDirectMessage(nodeId: String, text: String) {
        val cleanId = nodeId.removePrefix("0x")
        viewModelScope.launch {
            bleManager.sendCommand("/msg 0x$cleanId $text")
        }
    }

    fun sendEncryptedMessage(nodeId: String, text: String) {
        val cleanId = nodeId.removePrefix("0x")
        viewModelScope.launch {
            bleManager.sendCommand("/eto 0x$cleanId $text")
        }
    }

    fun sendPublicEncrypted(text: String) {
        viewModelScope.launch {
            bleManager.sendCommand("/pub $text")
        }
    }

    fun scanStations() {
        viewModelScope.launch {
            bleManager.sendCommand("/scan")
        }
    }

    fun requestWeather(target: String = "all") {
        viewModelScope.launch {
            if (target == "all") {
                bleManager.sendCommand("/wxreq all")
            } else {
                bleManager.sendCommand("/wxreq $target")
            }
        }
    }

    fun setWeatherMode(enabled: Boolean) {
        viewModelScope.launch {
            bleManager.sendCommand(if (enabled) "/wx on" else "/wx off")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun setWeatherLocation(lat: Double, lon: Double) {
        viewModelScope.launch {
            bleManager.sendCommand("/wxloc $lat $lon")
        }
    }

    fun setMaxHops(hops: Int) {
        viewModelScope.launch {
            bleManager.sendCommand("/ttl $hops")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun generateKey() {
        viewModelScope.launch {
            bleManager.sendCommand("/mykey gen")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun setReliableSend(enabled: Boolean) {
        viewModelScope.launch {
            bleManager.sendCommand(if (enabled) "/reliable on" else "/reliable off")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun setTxPower(power: Int) {
        viewModelScope.launch {
            bleManager.sendCommand("/txpower $power")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun setSleepMode(enabled: Boolean) {
        viewModelScope.launch {
            bleManager.sendCommand(if (enabled) "/sleep on" else "/sleep off")
            kotlinx.coroutines.delay(200)
            bleManager.sendCommand("/settings")
        }
    }

    fun requestSettings() {
        viewModelScope.launch {
            bleManager.sendCommand("/settings")
        }
    }

    fun sendTraceroute(nodeId: String) {
        val cleanId = nodeId.removePrefix("0x")
        viewModelScope.launch {
            bleManager.sendCommand("/traceroute 0x$cleanId")
        }
    }

    fun setPeerKey(nodeId: String, hexKey: String) {
        val cleanId = nodeId.removePrefix("0x")
        viewModelScope.launch {
            bleManager.sendCommand("/key set 0x$cleanId $hexKey")
        }
    }

    fun deletePeerKey(nodeId: String) {
        val cleanId = nodeId.removePrefix("0x")
        viewModelScope.launch {
            bleManager.sendCommand("/key del 0x$cleanId")
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        chatPersistence.clearAll()
    }

    fun clearConversation(conversationId: String) {
        val remaining = _messages.value.filter { msg ->
            val convId = if (msg.destination == "broadcast" || msg.destination == "0xFFFF") {
                "broadcast"
            } else if (msg.isOutgoing) {
                msg.destination
            } else {
                msg.origin
            }
            convId != conversationId
        }
        _messages.value = remaining
        chatPersistence.clearConversation(conversationId)
    }

    // ── Background Service ───────────────────────────────────────────

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        _backgroundServiceEnabled.value = enabled
        appPrefs.edit().putBoolean("background_service_enabled", enabled).apply()
        val app = getApplication<Application>()
        if (enabled) {
            val intent = Intent(app, MeshBleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } else {
            val intent = Intent(app, MeshBleService::class.java)
            app.stopService(intent)
        }
    }

    /**
     * Called from incoming message parsing to trigger a notification
     * if background service is active and app is in background
     */
    private fun notifyNewMessage(msg: MeshMessage) {
        if (_backgroundServiceEnabled.value) {
            val app = getApplication<Application>()
            val intent = Intent(app, MeshBleService::class.java).apply {
                action = MeshBleService.ACTION_NEW_MESSAGE
                putExtra(MeshBleService.EXTRA_SENDER, msg.origin)
                putExtra(MeshBleService.EXTRA_TEXT, msg.text)
            }
            app.startService(intent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}

