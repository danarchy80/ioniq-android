package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.sync.withLock
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
    private var connectionJob: Job? = null
    private val maxReconnectAttempts = ObdTransport.MAX_RECONNECT_ATTEMPTS
    private val baseDelayMs = 2000L
    private val maxDelayMs = 30_000L

    // ---- Socket / streams ----
    private var socket: BluetoothSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var running = false

    // ---- Incoming data accumulator (background reader) ----
    private val responseBuffer = StringBuilder()
    private val responseLock = Object()
    private val commandMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingResponse: CompletableDeferred<String?>? = null
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

    private fun doConnect(device: BluetoothDevice, scheduleReconnectOnFailure: Boolean = true): Job {
        val previousJob = connectionJob
        closePendingSocket()
        previousJob?.cancel()
        val job = managerScope.launch {
            previousJob?.join()
            if (!isActive) return@launch
            _connectionState.value = ConnectionState.CONNECTING
            try {
                Timber.i("RFCOMM connecting to ${device.address} (timeout=${rfcConnectTimeoutMs}ms)...")

                // Cancel discovery to speed up RFCOMM connect
                try {
                    val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                    bm.adapter?.cancelDiscovery()
                } catch (_: Exception) {}

                // Wait a moment for discovery cancellation to propagate
                delay(500)

                // Step 1: Fetch SDP UUIDs to warm the cache (critical on many devices)
                try {
                    Timber.i("Fetching SDP UUIDs from device...")
                    val found = device.fetchUuidsWithSdp()
                    val uuids = device.uuids
                    Timber.i("SDP fetch result: $found, cached UUIDs: ${uuids?.joinToString() ?: "null"}")
                } catch (e: Exception) {
                    Timber.w(e, "fetchUuidsWithSdp failed (non-fatal)")
                }

                // Step 2: Try primary socket (secure RFCOMM with SPP UUID)
                var connected = false
                var activeSocket: BluetoothSocket? = null

                // Try multiple times with small delays — some adapters need a moment
                for (attempt in 1..3) {
                    if (connected) break
                    Timber.i("Primary RFCOMM connect attempt $attempt...")
                    delay(if (attempt > 1) 1000L else 0L)

                    val sock = try {
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    } catch (e: Exception) {
                        Timber.w(e, "createRfcommSocketToServiceRecord failed, trying insecure variant")
                        try {
                            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        } catch (e2: Exception) {
                            Timber.e(e2, "Both secure and insecure socket creation failed")
                            null
                        }
                    }

                    if (sock != null) {
                        activeSocket = sock
                        try {
                            // Cancel discovery again right before connect
                            try {
                                val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                                bm.adapter?.cancelDiscovery()
                            } catch (_: Exception) {}

                            connectSocket(sock)
                            connected = true
                            Timber.i("Primary RFCOMM connect succeeded on attempt $attempt")
                        } catch (e: RfcommConnectTimeoutException) {
                            try { sock.close() } catch (_: Exception) {}
                            throw e
                        } catch (e: CancellationException) {
                            try { sock.close() } catch (_: Exception) {}
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Primary RFCOMM connect attempt $attempt failed: ${e.message}")
                            try { sock.close() } catch (_: Exception) {}
                        }
                    }
                }

                // Step 3: If primary failed, try insecure + reflection fallback with multiple channels
                if (!connected) {
                    Timber.i("Primary attempts exhausted, trying reflection fallback across channels 1-5...")
                    // Channels commonly used by ELM327 adapters
                    for (channel in 1..5) {
                        if (connected) break
                        Timber.i("Reflection fallback: channel $channel")
                        try {
                            val method = device::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            val fallback = method.invoke(device, channel) as BluetoothSocket
                            activeSocket = fallback
                            
                            // Cancel discovery right before fallback connect too
                            try {
                                val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                                bm.adapter?.cancelDiscovery()
                            } catch (_: Exception) {}
                            delay(200)
                            
                            connectSocket(fallback)
                            connected = true
                            Timber.i("Reflection fallback connect succeeded on channel $channel")
                        } catch (e: RfcommConnectTimeoutException) {
                            try { activeSocket?.close() } catch (_: Exception) {}
                            activeSocket = null
                            throw e
                        } catch (e: CancellationException) {
                            try { activeSocket?.close() } catch (_: Exception) {}
                            activeSocket = null
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Reflection fallback channel $channel failed: ${e.message}")
                            try { activeSocket?.close() } catch (_: Exception) {}
                            activeSocket = null
                        }
                    }
                }

                if (!connected) {
                    Timber.e("All RFCOMM connect attempts failed after ${rfcConnectTimeoutMs}ms")
                    Timber.e("Suggestions: unpair/re-pair ${device.name ?: device.address}, power-cycle the OBD adapter, or check if another app holds the connection")
                    cleanupSocket()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (autoReconnectEnabled && scheduleReconnectOnFailure) startAutoReconnect()
                    return@launch
                }

                val sock = activeSocket!!
                socket = sock
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

            } catch (e: CancellationException) {
                closePendingSocket()
                cleanupSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
                throw e
            } catch (e: Exception) {
                Timber.e(e, "RFCOMM connection error")
                closePendingSocket()
                cleanupSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
                if (autoReconnectEnabled && scheduleReconnectOnFailure) startAutoReconnect()
            }
        }
        connectionJob = job
        return job
    }

    /** Connect with a socket-closing watchdog; coroutine cancellation alone cannot stop RFCOMM connect(). */
    private suspend fun connectSocket(sock: BluetoothSocket) {
        pendingSocket = sock
        try {
            connectWithSocketTimeout(
                timeoutMs = rfcConnectTimeoutMs,
                closeSocket = { runCatching { sock.close() } },
                connect = { sock.connect() }
            )
        } finally {
            if (pendingSocket === sock) pendingSocket = null
        }
    }

    private fun closePendingSocket() {
        val pending = pendingSocket
        pendingSocket = null
        runCatching { pending?.close() }
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
                    var waiter: CompletableDeferred<String?>? = null
                    var response: String? = null
                    synchronized(responseLock) {
                        responseBuffer.append(text)
                        if (text.contains(">")) {
                            response = responseBuffer.toString()
                                .replace(">", "")
                                .replace("\r", " ")
                                .replace("\n", " ")
                                .trim()
                            waiter = pendingResponse
                            pendingResponse = null
                            responseBuffer.clear()
                        }
                    }
                    waiter?.complete(response)
                }
            } catch (e: Exception) {
                if (running) {
                    Timber.w(e, "RFCOMM reader error")
                    handleDisconnect("Reader exception")
                }
            }
        }
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String? = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val os = output ?: return@withContext null
            val waiter = CompletableDeferred<String?>()

            synchronized(responseLock) {
                responseBuffer.clear()
                pendingResponse?.complete(null)
                pendingResponse = waiter
            }

            try {
                os.write("$command\r".toByteArray())
                os.flush()
            } catch (e: Exception) {
                synchronized(responseLock) {
                    if (pendingResponse === waiter) pendingResponse = null
                }
                waiter.complete(null)
                Timber.w(e, "RFCOMM write failed for: $command")
                return@withContext null
            }

            val result = try {
                awaitRfcommResponse(waiter, timeoutMs)
            } finally {
                synchronized(responseLock) {
                    if (pendingResponse === waiter) pendingResponse = null
                }
            }

            if (result == null) {
                if (waiter.isCompleted) {
                    Timber.w("RFCOMM command '$command' ended before a response (connection closed)")
                } else {
                    Timber.w("RFCOMM command '$command' timed out after ${timeoutMs}ms")
                }

                // Best-effort ELM recovery; don't write to a socket already closed by disconnect.
                if (running) {
                    runCatching {
                        Timber.d("Sending recovery flush after '$command'")
                        os.write("\r\r".toByteArray())
                        os.flush()
                        kotlinx.coroutines.delay(150)
                    }
                }
            } else {
                Timber.d("RFCOMM command '$command' response: '${result.take(120)}'")
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
        val pending = synchronized(responseLock) {
            responseBuffer.clear()
            pendingResponse.also { pendingResponse = null }
        }
        pending?.complete(null)
        closePendingSocket()
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
        connectionJob?.cancel()
        closePendingSocket()
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
                    val attemptJob = targetDevice?.let { doConnect(it, scheduleReconnectOnFailure = false) }
                    attemptJob?.join()
                    if (_connectionState.value == ConnectionState.CONNECTED) {
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
        connectionJob?.cancel()
        closePendingSocket()
        running = false
        cleanupSocket()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun release() {
        disableAutoReconnect()
        reconnectJob?.cancel()
        connectionJob?.cancel()
        closePendingSocket()
        readJob?.cancel()
        running = false
        cleanupSocket()
        managerScope.cancel()
    }
}
