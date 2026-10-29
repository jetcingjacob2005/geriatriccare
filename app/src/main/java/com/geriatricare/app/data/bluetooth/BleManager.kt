package com.geriatricare.app.data.bluetooth

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.geriatricare.app.data.model.BleConnectionState
import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.model.ConnectionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton BLE manager that handles scanning, connecting, and
 * receiving UART data from the ESP32 (running the GeriatriCare sketch).
 *
 * The ESP32 sketch exposes a UART-style BLE service (same UUIDs the
 * classic HM-10 module used), so this UUID scheme still applies:
 *   Service:        0000FFE0-0000-1000-8000-00805F9B34FB
 *   Characteristic: 0000FFE1-0000-1000-8000-00805F9B34FB
 *
 * Current ESP32-C3 firmware sends fixed-size binary ECG and vital packets that
 * fit the default BLE payload. Legacy JSON firmware remains supported.
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val ESP32_SERVICE_UUID    : UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val ESP32_CHAR_UUID       : UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID             : UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val SCAN_PERIOD = 12_000L // 12 seconds
        private const val ECG_DISPLAY_FRAME_MS = 50L // Smooth 20 FPS chart redraw.
    }

    private val bluetoothManager  = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter  : BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt     : BluetoothGatt? = null
    private val handler           = Handler(Looper.getMainLooper())

    // ── Public State Flows ───────────────────────────────────────────────────

    private val _bleData = MutableStateFlow(BleData())
    val bleData: StateFlow<BleData> = _bleData
    private val _lastPacketAt = MutableStateFlow(0L)
    val lastPacketAt: StateFlow<Long> = _lastPacketAt

    private val _connectionState = MutableStateFlow(BleConnectionState())
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices
    private val _discoveryInfo = MutableStateFlow<Map<String, BleDiscoveryInfo>>(emptyMap())
    val discoveryInfo: StateFlow<Map<String, BleDiscoveryInfo>> = _discoveryInfo

    /** A human-readable discovery status for the pairing dialog. */
    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage: StateFlow<String?> = _scanMessage

    private val _ecgBuffer = MutableStateFlow<List<Float>>(emptyList())
    val ecgBuffer: StateFlow<List<Float>> = _ecgBuffer
    private val _ecgDisplayBuffer = MutableStateFlow<List<Float>>(emptyList())
    val ecgDisplayBuffer: StateFlow<List<Float>> = _ecgDisplayBuffer
    private val ecgStream = EcgStreamBuffer()
    private val _ecgSampleRate = MutableStateFlow(50f)
    val ecgSampleRate: StateFlow<Float> = _ecgSampleRate
    private var lastEcgAt = 0L
    private var lastEcgDisplayPublishAt = 0L

    // ── BLE Scan ─────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordDiscovery(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { recordDiscovery(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(stopScanRunnable)
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A Bluetooth scan is already running. Please try again."
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth scan registration failed. Restart Bluetooth and try again."
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support Bluetooth Low Energy scanning."
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android Bluetooth returned an internal error. Turn Bluetooth off and on, then retry."
                else -> "Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on, then retry."
            }
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    private fun recordDiscovery(result: ScanResult) {
        val device = result.device
        val address = device.address
        val info = BleDiscoveryInfo.merge(
            _discoveryInfo.value[address], result.scanRecord?.deviceName,
            try { device.name } catch (_: SecurityException) { null },
            result.scanRecord?.serviceUuids?.any { it.uuid == ESP32_SERVICE_UUID } == true
        )
        // Update even for an already-seen address: the name can arrive in a later scan response.
        _discoveryInfo.value = _discoveryInfo.value + (address to info)
        if (_scannedDevices.value.none { it.address == address }) {
            _scannedDevices.value = _scannedDevices.value + device
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_connectionState.value.status in listOf(ConnectionStatus.SCANNING, ConnectionStatus.CONNECTED)) return
        handler.removeCallbacks(stopScanRunnable)
        _scannedDevices.value = emptyList()
        _discoveryInfo.value = emptyMap()
        _scanMessage.value = null

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            reportMissingScanPermission()
            return
        }
        // A replacement board has a different address. Manual discovery must not
        // be held hostage by an automatic connection attempt to the old board.
        if (_connectionState.value.status == ConnectionStatus.CONNECTING) {
            closePreviousGatt()
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
        }

        if (bluetoothAdapter == null) {
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = "Bluetooth hardware is not available on this phone."
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = "Bluetooth is turned off. Turn it on, then scan again."
            return
        }
        // Android 11 and earlier gate BLE discovery behind the device-wide Location
        // setting even when the app has location permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager?.isLocationEnabled == true
            } else {
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }
            if (!locationEnabled) {
                _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
                _scanMessage.value = "Turn on the phone's Location setting, then scan again. Android requires it for BLE discovery on this version."
                return
            }
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = "Bluetooth Low Energy scanner is unavailable. Turn Bluetooth off and on, then retry."
            return
        }

        _connectionState.value = BleConnectionState(status = ConnectionStatus.SCANNING)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            // No service filter: this intentionally discovers any advertising ESP32,
            // including boards whose advertised name is supplied in scan response data.
            scanner.startScan(null, settings, scanCallback)
            handler.postDelayed(stopScanRunnable, SCAN_PERIOD)
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = "Bluetooth permission was not granted. Allow Nearby devices, then scan again."
        } catch (_: IllegalStateException) {
            _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
            _scanMessage.value = "Bluetooth is not ready. Turn it off and on, then retry."
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            _scanMessage.value = "Bluetooth permission changed. Allow Nearby devices, then retry."
        } catch (_: IllegalStateException) {
            _scanMessage.value = "Bluetooth was turned off. Turn it on, then retry."
        }
        if (_connectionState.value.status == ConnectionStatus.SCANNING) {
            _connectionState.value = _connectionState.value.copy(status = ConnectionStatus.DISCONNECTED)
        }
    }

    fun reportMissingScanPermission() {
        _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
        _scanMessage.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            "Nearby devices permission is required to find the ESP32. Allow it in Android Settings, then scan again."
        else "Location permission is required for BLE discovery on this Android version. Allow it, then scan again."
    }

    // ── GATT Connection ──────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState(
                        status = ConnectionStatus.CONNECTED,
                        deviceName = _discoveryInfo.value[gatt.device.address]?.name ?: gatt.device.name ?: "ESP32 Device",
                        deviceAddress = gatt.device.address
                    )
                    // Default BLE MTU only carries ~20 usable bytes per notification,
                    // which truncates our JSON payload. Request more room before
                    // discovering services; onMtuChanged (below) continues the setup
                    // whether or not the request succeeds.
                    gatt.requestMtu(185)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState(status = ConnectionStatus.DISCONNECTED)
                    _bleData.value = BleData()
                    _ecgBuffer.value = emptyList()
                    _ecgDisplayBuffer.value = emptyList()
                    ecgStream.clear()
                    _lastPacketAt.value = 0L
                    dataBuffer.clear()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val char = gatt.getService(ESP32_SERVICE_UUID)
                           ?.getCharacteristic(ESP32_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt) return
            val bytes = characteristic.value ?: return
            handleNotification(bytes)
        }

        // Android 13+ (API 33+) callback overload
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) return
            handleNotification(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        closePreviousGatt()
        _connectionState.value = BleConnectionState(
            status = ConnectionStatus.CONNECTING,
            deviceName = _discoveryInfo.value[device.address]?.name ?: device.name ?: "Unknown",
            deviceAddress = device.address
        )
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closePreviousGatt() {
        val previous = bluetoothGatt
        bluetoothGatt = null // Late callbacks from the retired connection must be ignored.
        previous?.disconnect()
        previous?.close()
    }

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        bluetoothAdapter?.getRemoteDevice(address)?.let { connect(it) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    // ── Data Parsing ─────────────────────────────────────────────────────────

    private val dataBuffer = StringBuilder()

    @Synchronized
    private fun handleNotification(bytes: ByteArray) {
        when (val packet = BleBinaryPacketDecoder.decode(bytes)) {
            is BleBinaryPacketDecoder.Ecg -> {
                ecgStream.acceptBinary(packet.samples, packet.sequence, packet.timingValid)
                publishEcg()
            }
            is BleBinaryPacketDecoder.Vitals -> publishVitals(packet)
            null -> parseData(String(bytes, Charsets.UTF_8)) // Legacy JSON firmware.
        }
    }

    private fun publishVitals(packet: BleBinaryPacketDecoder.Vitals) {
        if (packet.leadOff) {
            ecgStream.clear()
            _ecgBuffer.value = emptyList()
            _ecgDisplayBuffer.value = emptyList()
            lastEcgAt = 0L
            lastEcgDisplayPublishAt = 0L
        }
        val opticalReady = packet.fingerPresent && packet.max30102Ready
        val hr = packet.heartRate.takeIf {
            opticalReady && SensorReadingValidator.isValidHeartRate(it)
        } ?: 0f
        val spo2 = packet.spo2.takeIf {
            opticalReady && SensorReadingValidator.isValidSpO2(it)
        } ?: 0f
        val temp = packet.temperature.takeIf(SensorReadingValidator::isValidTemperature) ?: 0f
        _bleData.value = BleData(
            ecg = if (packet.leadOff) -1 else ecgStream.analysis.lastOrNull()?.toInt() ?: _bleData.value.ecg,
            hr = hr,
            spo2 = spo2,
            temp = temp
        )
        _lastPacketAt.value = System.currentTimeMillis()
    }

    @Synchronized
    private fun parseData(raw: String) {
        if (raw.isEmpty()) return
        dataBuffer.append(raw)

        // Continuously extract and parse every complete { ... } JSON object in buffer
        while (true) {
            val start = dataBuffer.indexOf('{')
            if (start == -1) {
                // No start brace found, discard garbage to avoid buffer build-up
                dataBuffer.clear()
                break
            }
            if (start > 0) {
                // Remove any leading characters before the first '{'
                dataBuffer.delete(0, start)
            }
            val end = dataBuffer.indexOf('}')
            if (end == -1) {
                // Incomplete JSON packet, wait for next BLE notification
                break
            }

            val json = dataBuffer.substring(0, end + 1)
            dataBuffer.delete(0, end + 1)

            try {
                val packet = com.google.gson.JsonParser.parseString(json).asJsonObject
                if (ecgStream.accept(packet)) {
                    publishEcg()
                    // ECG frames must not refresh vital freshness.
                    continue
                }
                val data = SensorPacketDecoder.decode(json, _bleData.value)
                if (data != null) {
                    if ((json.contains("\"ecg\"") || json.contains("\"leadOff\"")) && SensorReadingValidator.isValidEcg(data.ecg)) {
                        appendEcg(data.ecg.toFloat())
                    }

                    // Merge only validated values. Sensor packets may be partial, but malformed
                    // values must never replace a previously valid reading or reach persistence.
                    val current = _bleData.value
                    val merged = BleData(
                        ecg   = ecgStream.analysis.lastOrNull()?.toInt()
                            ?: if (SensorReadingValidator.isValidEcg(data.ecg)) data.ecg else current.ecg,
                        hr    = data.hr,
                        spo2  = data.spo2,
                        temp  = data.temp
                    )

                    // Emits immediately if any vital changed (StateFlow checks equals())
                    _bleData.value = merged
                    _lastPacketAt.value = System.currentTimeMillis()
                }
            } catch (_: Exception) { }
        }

        // Safety limit to avoid unbounded memory growth in case of malformed stream
        if (dataBuffer.length > 2048) {
            dataBuffer.clear()
        }
    }

    @Synchronized
    private fun appendEcg(value: Float) {
        ecgStream.legacy(value)
        publishEcg()
    }

    private fun publishEcg() {
        val now = System.currentTimeMillis()
        lastEcgAt = now
        _ecgSampleRate.value = ecgStream.sampleRateHz.toFloat()
        _ecgBuffer.value = ecgStream.analysis
        // Keep analysis at the full sensor rate while drawing a smooth 20 FPS
        // right-to-left monitor trace. Contact loss is reflected immediately.
        if (now - lastEcgDisplayPublishAt >= ECG_DISPLAY_FRAME_MS || ecgStream.display.lastOrNull()?.let { it < 0f } == true) {
            _ecgDisplayBuffer.value = ecgStream.display
            lastEcgDisplayPublishAt = now
        }
        _bleData.value = _bleData.value.copy(ecg = ecgStream.analysis.lastOrNull()?.toInt() ?: -1)
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    init {
        handler.post(object : Runnable {
            override fun run() {
                synchronized(this@BleManager) {
                    if (lastEcgAt > 0L && System.currentTimeMillis() - lastEcgAt > 2000L) {
                        ecgStream.clear()
                        _ecgBuffer.value = emptyList()
                        _ecgDisplayBuffer.value = emptyList()
                        _bleData.value = _bleData.value.copy(ecg = -1)
                        lastEcgAt = 0L
                        lastEcgDisplayPublishAt = 0L
                    }
                    val last = _lastPacketAt.value
                    if (last > 0L && System.currentTimeMillis() - last > 10_000L) {
                        _bleData.value = BleData()
                        _ecgBuffer.value = emptyList()
                        _ecgDisplayBuffer.value = emptyList()
                        ecgStream.clear()
                        _lastPacketAt.value = 0L
                        lastEcgDisplayPublishAt = 0L
                    }
                }
                handler.postDelayed(this, 1000L)
            }
        })
    }
}
