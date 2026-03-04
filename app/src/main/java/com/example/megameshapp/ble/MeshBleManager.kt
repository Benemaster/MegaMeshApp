package com.example.megameshapp.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE Manager for communicating with MegaMesh ESP32 devices
 * using Nordic UART Service (NUS)
 */
class MeshBleManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshBLE"

        // Nordic UART Service UUIDs (same as firmware)
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write to device
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notifications from device
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class BleDeviceInfo(
        val name: String,
        val address: String,
        val rssi: Int
    )

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Scanning : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _receivedData = MutableStateFlow<String>("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var lineBuffer = StringBuilder()

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            Log.w(TAG, "Missing BLE permissions")
            return
        }

        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        // Also do an unfiltered scan to catch devices advertising as "MegaMesh"
        val unfilteredSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, unfilteredSettings, unfilteredScanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasPermissions()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
            scanner.stopScan(unfilteredScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addScanResult(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.contains("MegaMesh", ignoreCase = true)) {
                addScanResult(result)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addScanResult(result: ScanResult) {
        val device = result.device
        val name = device.name ?: "Unknown"
        val address = device.address
        val rssi = result.rssi

        val current = _discoveredDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == address }
        val info = BleDeviceInfo(name, address, rssi)
        if (existing >= 0) {
            current[existing] = info
        } else {
            current.add(info)
        }
        _discoveredDevices.value = current
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) return
        stopScan()

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = ConnectionState.Connecting

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
    }

    // Negotiated MTU chunk size (default conservative, updated via MTU callback)
    private var mtuChunkSize = 20
    private var servicesReady = false

    @SuppressLint("MissingPermission")
    suspend fun sendCommand(command: String) {
        val rx = rxCharacteristic ?: run {
            Log.w(TAG, "sendCommand: RX characteristic null")
            return
        }
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "sendCommand: GATT null")
            return
        }

        val data = (command + "\n").toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sendCommand: ${command} (${data.size} bytes, chunkSize=$mtuChunkSize)")

        for (i in data.indices step mtuChunkSize) {
            val chunk = data.copyOfRange(i, minOf(i + mtuChunkSize, data.size))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: new writeCharacteristic method
                val result = gatt.writeCharacteristic(
                    rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                Log.d(TAG, "writeCharacteristic (API33+) result: $result")
            } else {
                // Legacy API
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                rx.value = chunk
                @Suppress("DEPRECATION")
                val result = gatt.writeCharacteristic(rx)
                Log.d(TAG, "writeCharacteristic (legacy) result: $result")
            }
            delay(50) // Coroutine delay between chunks
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    servicesReady = false
                    _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    servicesReady = false
                    _connectionState.value = ConnectionState.Disconnected
                    _connectedDeviceName.value = null
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val nusService = gatt.getService(NUS_SERVICE_UUID)
            if (nusService == null) {
                Log.e(TAG, "NUS service not found")
                return
            }

            rxCharacteristic = nusService.getCharacteristic(NUS_RX_CHAR_UUID)
            txCharacteristic = nusService.getCharacteristic(NUS_TX_CHAR_UUID)

            // Enable notifications on TX characteristic
            txCharacteristic?.let { tx ->
                gatt.setCharacteristicNotification(tx, true)
                val descriptor = tx.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } else {
                    // No descriptor — go straight to MTU request
                    gatt.requestMtu(512)
                }
            } ?: run {
                // No TX char — still request MTU
                gatt.requestMtu(512)
            }
            // DO NOT set Connected here — wait for descriptor write + MTU
            Log.i(TAG, "NUS service found, waiting for descriptor + MTU")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "Descriptor written status=$status, requesting MTU")
            // After CCCD is written, request larger MTU
            gatt.requestMtu(512)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuChunkSize = mtu - 3
                Log.i(TAG, "MTU=$mtu, chunkSize=$mtuChunkSize")
            } else {
                mtuChunkSize = 20
                Log.w(TAG, "MTU change failed, using $mtuChunkSize")
            }
            if (!servicesReady) {
                servicesReady = true
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "NUS service fully ready, signaling Connected")
            }
        }

        // API 33+ notification callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                val text = String(value, Charsets.UTF_8)
                processIncomingData(text)
            }
        }

        // Legacy notification callback (pre-API 33)
        @Deprecated("Deprecated in API 33", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                val text = String(data, Charsets.UTF_8)
                processIncomingData(text)
            }
        }
    }

    private fun processIncomingData(chunk: String) {
        lineBuffer.append(chunk)
        while (true) {
            val newlineIndex = lineBuffer.indexOf('\n')
            if (newlineIndex < 0) break
            val line = lineBuffer.substring(0, newlineIndex).trimEnd('\r')
            lineBuffer.delete(0, newlineIndex + 1)
            if (line.isNotEmpty()) {
                _receivedData.value = line
            }
        }
    }
}
