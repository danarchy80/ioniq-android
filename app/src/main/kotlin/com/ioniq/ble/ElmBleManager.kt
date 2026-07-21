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
 * - Timeout guards on every phase of the connection handshake
 */
class ElmBleManager(context: Context) : ObdTransport {

    // ---- Connection State ----
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    override val reconnectCount: StateFlow<Int> = _reconnectCount.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    // ---- Auto-reconnect config ----
    private var autoReconnectEnabled = false
    private var targetDevice: BluetoothDevice? = null
    private val maxReconnectAttempts = 15
    private val baseDelayMs = 2000L
    private val maxDelayMs = 30_000L

    // ---- Coroutine scope ----
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null

    // ---- Configurable timeouts (ms) ----
    var gattConnectTimeoutMs = 15_000L
    var descriptorWriteTimeoutMs = 10_000L
    var serviceDiscoveryTimeoutMs = 10_000L
    var elmInitTotalTimeoutMs = 30_000L
    var atCommandTimeoutMs = 5_000L

    // ---- ELM327 command/response ----
    @Volatile private var lastResponse = StringBuilder()
    @Volatile private var responseReady = false
    private val responseLock = Object()

    // ---- GATT ----
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    // ---- Synchronization state (handshake pipeline) ----
    // 0=idle 1=discovering services 2=writing CCCD 3=ELM-init
    @Volatile private var handshakePhase = 0
    private val handshakeLock = Object()

    companion object {
        private val TAG = "ElmBleManager"
        // Common ELM327 BLE OBD adapter UUIDs (most adapters use these)
        val OBD_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext

    /**
     * True if the device advertises a GATT-compatible service (i.e. it's a BLE adapter).
     * Quick heuristic used by the transport selector before committing to BLE or RFCOMM.
     */
    fun isBleObdAdapter(device: BluetoothDevice): Boolean {
        // Classic-only adapters won't have any GATT services; we check type flags.
        val type = device.type
        return type == BluetoothDevice.DEVICE_TYPE_LE ||
            type == BluetoothDevice.DEVICE_TYPE_DUAL
    }

    // ---- GATT Callback ----
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("GATT connected → discovering services")
                    _connectionState.value = ConnectionState.CONNECTING
                    handshakePhase = 1
                    gatt.discoverServices()
                    startHandshakeWatchdog(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelHandshakeWatchdog()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _isReconnecting.value = false
                    handshakePhase = 0
                    try { gatt.close() } catch (_: Throwable) { /* ignore */ }
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
                handshakeFailed("Service discovery failed status=$status")
                return
            }
            val service = gatt.getService(OBD_SERVICE_UUID)
            if (service == null) {
                Timber.e("OBD service $OBD_SERVICE_UUID not found")
                handshakeFailed("OBD service not found — not a BLE ELM327?")
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
                    handshakePhase = 2
                    gatt.writeDescriptor(cccd)
                } else {
                    // No CCCD — some adapters go straight to writable
                    finishHandshake()
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handshakeFailed("CCCD write failed status=$status")
                return
            }
            Timber.i("Notifications enabled → device ready")
            finishHandshake()
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

    // Handshake watchdog: if the pipeline stalls in a phase, kill the connection
    // so the caller doesn't hang forever.
    private var watchdogJob: Job? = null
    private fun startHandshakeWatchdog(gatt: BluetoothGatt) {
        watchdogJob?.cancel()
        watchdogJob = managerScope.launch {
            // Phase 1: waiting for service discovery
            val svcDeadline = System.currentTimeMillis() + serviceDiscoveryTimeoutMs
            while (isActive && handshakePhase == 1) {
                if (System.currentTimeMillis() > svcDeadline) {
                    Timber.e("Handshake watchdog: service discovery timed out after ${serviceDiscoveryTimeoutMs}ms")
                    handshakeFailed("Service discovery timed out")
                    return@launch
                }
                delay(200)
            }
            // Phase 2: waiting for CCCD write
            val descDeadline = System.currentTimeMillis() + descriptorWriteTimeoutMs
            while (isActive && handshakePhase == 2) {
                if (System.currentTimeMillis() > descDeadline) {
                    Timber.e("Handshake watchdog: CCCD write timed out after ${descriptorWriteTimeoutMs}ms")
                    handshakeFailed("Descriptor write timed out")
                    return@launch
                }
                delay(200)
            }
        }
    }

    private fun cancelHandshakeWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun handshakeFailed(reason: String) {
        Timber.e("Handshake failed: $reason")
        cancelHandshakeWatchdog()
        handshakePhase = 0
        _connectionState.value = ConnectionState.DISCONNECTED
        gatt?.disconnect()
        // autoReconnect will kick in via onConnectionStateChange
    }

    private fun finishHandshake() {
        cancelHandshakeWatchdog()
        handshakePhase = 3
        // Run ELM init with total budget
        managerScope.launch {
            try {
                withTimeout(elmInitTotalTimeoutMs) {
                    initializeElm()
                }
                handshakePhase = 0
                _connectionState.value = ConnectionState.CONNECTED
                _reconnectCount.value = 0
                _isReconnecting.value = false
            } catch (e: TimeoutCancellationException) {
                Timber.e("ELM init timed out after ${elmInitTotalTimeoutMs}ms")
                handshakeFailed("ELM init total timeout")
            }
        }
    }

    /**
     * Connect to a specific BLE device.
     */
    override fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        autoReconnectEnabled = true
        _reconnectCount.value = 0
        _isReconnecting.value = false
        reconnectJob?.cancel()
        // Pre-flight: if Bluetooth is off, don't attempt — will crash in native stack
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Timber.w("Bluetooth adapter off — deferring GATT connect to ${device.address}")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        try {
            doConnect(device)
        } catch (t: Throwable) {
            Timber.e(t, "connectToDevice failed for ${device.address}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun doConnect(device: BluetoothDevice) {
        // Pre-flight: if Bluetooth is off, don't attempt — native stack can crash
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Timber.w("Bluetooth adapter off — skipping GATT connect to ${device.address}")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        handshakePhase = 0
        managerScope.launch {
            try {
                Timber.i("Connecting GATT to ${device.address} (timeout=${gattConnectTimeoutMs}ms)...")
                try { gatt?.close() } catch (t: Throwable) { Timber.w(t, "gatt.close() failed") }
                gatt = device.connectGatt(appContext, false, gattCallback)

                if (gatt == null) {
                    Timber.e("connectGatt returned null for ${device.address}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (autoReconnectEnabled) startAutoReconnect()
                    return@launch
                }

                // GATT connect timeout — if we never reach STATE_CONNECTED within budget
                connectTimeoutJob?.cancel()
                connectTimeoutJob = launch {
                    delay(gattConnectTimeoutMs)
                    if (_connectionState.value == ConnectionState.CONNECTING &&
                        handshakePhase < 1
                    ) {
                        Timber.e("GATT connect timed out after ${gattConnectTimeoutMs}ms")
                        handshakeFailed("GATT connect timeout")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.e(t, "Connection error")
                cancelHandshakeWatchdog()
                _connectionState.value = ConnectionState.DISCONNECTED
                if (autoReconnectEnabled) startAutoReconnect()
            }
        }
    }

    /**
     * Send an OBD-II command string and wait for response.
     * @return response string (trimmed, no ">"), or null on timeout.
     */
    override suspend fun sendCommand(command: String, timeoutMs: Long): String? {
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
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim()
                }
            }

            // Recovery flush: if the ELM is stuck mid-"Searching…" or otherwise
            // unresponsive, send CRs to force it to drop the current command
            // and re-emit the ">" prompt. Best-effort — ignore write failures.
            if (result == null) {
                runCatching {
                    Timber.d("sendCommand timed out for '$command' — sending \\r\\r recovery flush")
                    tx.value = "\r\r".toByteArray()
                    g.writeCharacteristic(tx)
                    kotlinx.coroutines.delay(200)
                }
            }

            result
        }
    }

    // ---- Auto-reconnect ----

    override fun enableAutoReconnect() { autoReconnectEnabled = true }

    override fun disableAutoReconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
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
                    // Wait up to gattConnectTimeoutMs for full CONNECTED state
                    val connected = withTimeoutOrNull(gattConnectTimeoutMs) {
                        connectionState.first { it == ConnectionState.CONNECTED }
                    }
                    if (connected != null) {
                        Timber.i("Reconnected on attempt $attempt")
                        _isReconnecting.value = false
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "Reconnect attempt $attempt failed")
                }
            }

            _isReconnecting.value = false
            Timber.e("Auto-reconnect exhausted ($maxReconnectAttempts attempts)")
        }
    }

    // ---- Lifecycle ----

    override fun disconnect() {
        disableAutoReconnect()
        gatt?.disconnect()
    }

    override fun release() {
        disableAutoReconnect()
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        cancelHandshakeWatchdog()
        managerScope.cancel()
        gatt?.close()
        gatt = null
    }
}
