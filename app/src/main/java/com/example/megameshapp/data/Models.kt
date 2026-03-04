package com.example.megameshapp.data

/**
 * Represents a chat message in the mesh network
 */
data class MeshMessage(
    val id: Long = System.currentTimeMillis(),
    val origin: String,         // e.g. "0x1A2B"
    val destination: String,    // e.g. "0xFFFF" for broadcast, or specific node
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rssi: Float? = null,
    val snr: Float? = null,
    val hops: Int? = null,
    val maxHops: Int? = null,
    val encrypted: Boolean = false,
    val isOutgoing: Boolean = false,
    val msgId: Int? = null,
    val acked: Boolean = false
)

/**
 * Represents a discovered station in the mesh network
 */
data class MeshStation(
    val nodeId: String,     // e.g. "0x1A2B"
    val lastSeen: Long = System.currentTimeMillis(),
    val rssi: Float = 0f,
    val snr: Float = 0f,
    val hops: Int = 0
)

/**
 * Weather data received from a mesh node
 */
data class WeatherData(
    val nodeId: String,
    val temperature: Float? = null,   // °C
    val humidity: Float? = null,      // %
    val pressure: Float? = null,      // hPa
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Device settings received from the connected node via /settings
 */
data class DeviceSettings(
    val nodeId: String = "Unknown",
    val maxHops: Int = 7,
    val weatherMode: Boolean = false,
    val personalKeyValid: Boolean = false,
    val personalKey: String? = null,
    val loraFreq: Float = 868.0f,
    val loraBW: Float = 125.0f,
    val loraSF: Int = 9,
    val loraCR: Int = 7,
    val loraPower: Int = 17,
    val bleConnected: Boolean = false,
    val peerKeys: Int = 0,
    val stations: Int = 0,
    val reliableSend: Boolean = true,
    val outboundBuffered: Int = 0,
    val sleepMode: Boolean = false,
    val batteryVoltage: Float = 0f,
    val batteryPercent: Int = 0
)

