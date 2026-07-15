package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import timber.log.Timber

/**
 * Classic Bluetooth RFCOMM/SPP manager for ELM327 OBD-II adapters.
 *
 * Many popular OBD adapters (ELM327 v1.5/v2.0 clones, Vgate iCar Pro classic,
 * OBDLink LX, etc.) use classic Bluetooth SPP rather than BLE. Driving those
 * through BLE GATT causes the OBD bus to hang post-connect, because the
 * adapter has no GATT services at all.
 *
 * This manager mirrors the ElmBleManager API (implements ObdTransport) so
 * VehicleRepository can use whichever transport the adapter supports.
 */
class ElmClassicManager(context: Context) : ObdTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    override val reconnectCount: StateFlow<Int> = _reconnectCount.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val appContext = context.applicationContext
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Configurable timeouts (ms) ----
    var rfcConnectTimeoutMs = 30_000L
    var elmInitTotalTimeoutMs = 30_000L
    var atCommandTimeoutMs = 5_000L

    // ---- Auto-reconnect ----
    private var autoReconnectEnabled = false
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private val maxReconnectAttempts = 15
    private val baseDelayMs = 2000L
    private val maxDelayMs = 30_000L

    // ---- Socket / streams ----
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var running = false

    // ---- Incoming data accumulator (background reader) ----
    private val responseBuffer = StringBuilder()
    @Volatile private var responseReady = false
    private val responseLock = Object()
    private var readJob: Job? = null

    companion object {
        // Standard SPP UUID used by ELM327 classic adapters
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private val TAG = "ElmClassicManager"
    }

    override fun connectToDevice(device: BluetoothDevice) {
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
                Timber.i("RFCOMM connecting to ${device.address} (timeout=${rfcConnectTimeoutMs}ms)...")

                // Cancel discovery to speed up RFCOMM connect
                try {
                    val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                    bm.adapter?.cancelDiscovery()
                } catch (_: Exception) {}

                val sock = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    Timber.w(e, "createRfcommSocketToServiceRecord failed, trying fallback")
                    // Some adapters need the insecure fallback
                    @Suppress("DEPRECATION")
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
                socket = sock

                val connected = withTimeoutOrNull(rfcConnectTimeoutMs) {
                    try {
                        sock.connect()
                        true
                    } catch (e: Exception) {
                        Timber.w(e, "RFCOMM connect via primary failed, trying fallback")
                        try {
                            sock.close()
                        } catch (_: Exception) {}
                        // Fallback: insecure socket
                        val fallback = device::class.java
                            .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(device, 1) as BluetoothSocket
                        try {
                            fallback.connect()
                            socket = fallback
                            true
                        } catch (e2: Exception) {
                            Timber.e(e2, "Fallback RFCOMM connect also failed")
                            false
                        }
                    }
                }

                if (connected != true) {
                    Timber.e("RFCOMM connect timed out after ${rfcConnectTimeoutMs}ms")
                    cleanupSocket()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (autoReconnectEnabled) startAutoReconnect()
                    return@launch
                }

                Timber.i("RFCOMM socket connected, starting reader thread + ELM init")
                input = sock.inputStream
                output = sock.outputStream
                running = true
                startReader()

                // ELM init with total budget
                try {
                    withTimeout(elmInitTotalTimeoutMs) {
                        initializeElm()
                    }
                    _connectionState.value = ConnectionState.CONNECTED
                    _reconnectCount.value = 0
                    _isReconnecting.value = false
                    Timber.i("ELM init complete (classic) — device ready")
                } catch (e: TimeoutCancellationException) {
                    Timber.e("ELM init timed out after ${elmInitTotalTimeoutMs}ms")
                    handleDisconnect("ELM init timeout")
                }

            } catch (e: Exception) {
                Timber.e(e, "RFCOMM connection error")
                cleanupSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
                if (autoReconnectEnabled) startAutoReconnect()
            }
        }
    }

    /**
     * Background reader: accumulates incoming bytes until an ELM '>' prompt
     * is seen, then signals the waiting sendCommand().
     */
    private fun startReader() {
        readJob?.cancel()
        readJob = managerScope.launch {
            val buf = ByteArray(1024)
            try {
                while (running) {
                    val n = withContext(Dispatchers.IO) {
                        // Non-blocking-ish: with a short read is okay; input.read() is blocking
                        // but we rely on socket close to unblock when stopping
                        input?.read(buf) ?: -1
                    }
                    if (n <= 0) {
                        if (!running) break
                        Timber.w("RFCOMM read returned $n — disconnecting")
                        handleDisconnect("Socket read EOF")
                        break
                    }
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    synchronized(responseLock) {
                        responseBuffer.append(text)
                        if (text.contains(">")) {
                            responseReady = true
                            responseLock.notifyAll()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Timber.w(e, "RFCOMM reader error")
                    handleDisconnect("Reader exception")
                }
            }
        }
    }

    private suspend fun initializeElm() {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        for (cmd in commands) {
            val resp = sendCommand(cmd, timeoutMs = atCommandTimeoutMs)
            Timber.d("Init $cmd → ${resp ?: "TIMEOUT"}")
        }
        Timber.i("ELM initialization complete (classic)")
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String? {
        return withContext(Dispatchers.IO) {
            val os = output ?: return@withContext null

            synchronized(responseLock) {
                responseReady = false
                responseBuffer.clear()
            }

            try {
                os.write("$command\r".toByteArray())
                os.flush()
            } catch (e: Exception) {
                Timber.w(e, "RFCOMM write failed for: $command")
                return@withContext null
            }

            val result = withTimeoutOrNull(timeoutMs) {
                synchronized(responseLock) {
                    while (!responseReady) {
                        responseLock.wait(50)
                    }
                }
                synchronized(responseLock) {
                    responseBuffer.toString()
                        .replace(">", "")
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim()
                }
            }
            result
        }
    }

    private fun handleDisconnect(reason: String) {
        Timber.w("Classic BT disconnect: $reason")
        running = false
        cleanupSocket()
        _connectionState.value = ConnectionState.DISCONNECTED
        if (autoReconnectEnabled) startAutoReconnect()
    }

    private fun cleanupSocket() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    // ---- Auto-reconnect ----

    override fun enableAutoReconnect() { autoReconnectEnabled = true }

    override fun disableAutoReconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        _isReconnecting.value = false
    }

    private fun startAutoReconnect() {
        reconnectJob?.cancel()
        if (!autoReconnectEnabled || targetDevice == null) return

        reconnectJob = managerScope.launch {
            _isReconnecting.value = true
            Timber.i("Classic auto-reconnect loop started (max $maxReconnectAttempts)")

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
                    val connected = withTimeoutOrNull(rfcConnectTimeoutMs) {
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
            Timber.e("Classic auto-reconnect exhausted ($maxReconnectAttempts attempts)")
        }
    }

    // ---- Lifecycle ----

    override fun disconnect() {
        disableAutoReconnect()
        running = false
        cleanupSocket()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun release() {
        disableAutoReconnect()
        reconnectJob?.cancel()
        readJob?.cancel()
        running = false
        cleanupSocket()
        managerScope.cancel()
    }
}
