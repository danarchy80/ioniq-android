package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.UUID

/**
 * BLE GATT manager for ELM327 OBD-II adapter connection.
 *
 * Features:
 * - Connect/disconnect to ELM327 BLE adapter
 * - ELM init sequence (ATZ, ATE0, ATH0, ATSP0)
 * - Send OBD-II commands, receive responses
 * - Automatic reconnection with exponential backoff
 */
class ElmBleManager(context: Context) {

    // ---- Connection State ----
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    // ---- Auto-reconnect config ----
    private var autoReconnectEnabled = false
    private var targetDevice: BluetoothDevice? = null
    private val maxReconnectAttempts = 15
    private val baseDelayMs = 2000L
    private val maxDelayMs = 30_000L

    // ---- Coroutine scope ----
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    // ---- ELM327 command/response ----
    @Volatile private var lastResponse = StringBuilder()
    @Volatile private var responseReady = false
    private val responseLock = Object()

    // ---- GATT ----
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    companion object {
        private val TAG = "ElmBleManager"
        // Common ELM327 BLE OBD adapter UUIDs (most adapters use these)
        val OBD_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext

    // ---- GATT Callback ----
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("GATT connected → discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _isReconnecting.value = false
                    gatt.close()
                    this@ElmBleManager.gatt = null
                    txChar = null
                    rxChar = null
                    Timber.w("GATT disconnected from ${gatt.device.address}")
                    if (autoReconnectEnabled) startAutoReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Service discovery failed: status=$status")
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }
            val service = gatt.getService(OBD_SERVICE_UUID)
            if (service == null) {
                Timber.e("OBD service $OBD_SERVICE_UUID not found")
                _connectionState.value = ConnectionState.DISCONNECTED
                gatt.disconnect()
                return
            }
            txChar = service.getCharacteristic(TX_CHAR_UUID)
            rxChar = service.getCharacteristic(RX_CHAR_UUID)

            // Enable notifications on RX char
            rxChar?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.CONNECTED
                _reconnectCount.value = 0
                _isReconnecting.value = false
                Timber.i("Notifications enabled → device ready")
                // Run ELM init
                managerScope.launch { initializeElm() }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                val data = characteristic.value ?: return
                val text = String(data, Charsets.UTF_8)
                synchronized(responseLock) {
                    lastResponse.append(text)
                    // ELM327 ends responses with ">"
                    if (text.contains(">")) {
                        responseReady = true
                        responseLock.notifyAll()
                    }
                }
            }
        }
    }

    /**
     * Connect to a specific BLE device.
     */
    fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        autoReconnectEnabled = true
        _reconnectCount.value = 0
        _isReconnecting.value = false
        reconnectJob?.cancel()
        doConnect(device)
    }

    private fun doConnect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        managerScope.launch {
            try {
                Timber.i("Connecting to ${device.address}...")
                gatt?.close()
                gatt = device.connectGatt(appContext, false, gattCallback)
            } catch (e: Exception) {
                Timber.e(e, "Connection error")
                _connectionState.value = ConnectionState.DISCONNECTED
                if (autoReconnectEnabled) startAutoReconnect()
            }
        }
    }

    /**
     * Send ELM327 AT initialization sequence after GATT services discovered.
     */
    private suspend fun initializeElm() {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        for (cmd in commands) {
            val resp = sendCommand(cmd, timeoutMs = 5000L)
            Timber.d("Init $cmd → ${resp ?: "TIMEOUT"}")
        }
        Timber.i("ELM initialization complete")
    }

    /**
     * Send an OBD-II command string and wait for response.
     * @return response string (trimmed, no ">"), or null on timeout.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = 3000L): String? {
        return withContext(Dispatchers.IO) {
            val g = gatt ?: return@withContext null
            val tx = txChar ?: return@withContext null

            synchronized(responseLock) {
                responseReady = false
                lastResponse.clear()
            }

            val cmdBytes = "$command\r".toByteArray()
            tx.value = cmdBytes
            val written = g.writeCharacteristic(tx)
            if (!written) {
                Timber.w("writeCharacteristic returned false for: $command")
                return@withContext null
            }

            // Wait for response with timeout
            val result = withTimeoutOrNull(timeoutMs) {
                synchronized(responseLock) {
                    while (!responseReady) {
                        responseLock.wait(50)
                    }
                }
                synchronized(responseLock) {
                    lastResponse.toString()
                        .replace(">", "")
                        .replace("\\r", " ")
                        .replace("\\n", " ")
                        .trim()
                }
            }
            result
        }
    }

    /**
     * Read an OBD-II PID value.
     */
    suspend fun readPid(pid: String): String? = sendCommand(pid)

    // ---- Auto-reconnect ----

    fun enableAutoReconnect() { autoReconnectEnabled = true }

    fun disableAutoReconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        _isReconnecting.value = false
    }

    private fun startAutoReconnect() {
        reconnectJob?.cancel()
        if (!autoReconnectEnabled || targetDevice == null) return

        reconnectJob = managerScope.launch {
            _isReconnecting.value = true
            Timber.i("Auto-reconnect loop started (max $maxReconnectAttempts)")

            var attempt = 0
            while (attempt < maxReconnectAttempts && autoReconnectEnabled) {
                attempt++
                _reconnectCount.value = attempt

                val delayMs = (baseDelayMs * (1L shl (attempt - 1).coerceAtMost(5)))
                    .coerceAtMost(maxDelayMs)
                Timber.d("Reconnect attempt $attempt in ${delayMs}ms")
                delay(delayMs)

                if (!autoReconnectEnabled || _connectionState.value == ConnectionState.CONNECTED) break

                try {
                    targetDevice?.let { doConnect(it) }
                    // Wait up to 12s for connection
                    val connected = withTimeoutOrNull(12_000L) {
                        connectionState.first { it == ConnectionState.CONNECTED }
                    }
                    if (connected != null) {
                        Timber.i("Reconnected on attempt $attempt")
                        _isReconnecting.value = false
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Reconnect attempt $attempt failed")
                }
            }

            _isReconnecting.value = false
            Timber.e("Auto-reconnect exhausted ($maxReconnectAttempts attempts)")
        }
    }

    // ---- Lifecycle ----

    fun disconnect() {
        disableAutoReconnect()
        gatt?.disconnect()
    }

    fun release() {
        disableAutoReconnect()
        reconnectJob?.cancel()
        managerScope.cancel()
        gatt?.close()
        gatt = null
    }
}
