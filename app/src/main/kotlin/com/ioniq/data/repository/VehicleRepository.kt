package com.ioniq.data.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.ioniq.ble.BleScanner
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.db.IoniqDatabase
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.obd.ObdParser
import com.ioniq.obd.ObdPids
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Central orchestrator: BLE → OBD commands → parser → Room DB → UI state.
 *
 * Data flow:
 * 1. BleScanner discovers ELM327 devices
 * 2. ElmBleManager establishes GATT connection
 * 3. This class sends OBD commands and collects responses
 * 4. ObdParser converts hex bytes to engineering values
 * 5. VehicleTelemetry is assembled and written to Room
 * 6. StateFlow emits live data to the UI (ViewModel)
 */
class VehicleRepository(private val context: Context) {

    private val db = IoniqDatabase.getInstance(context)
    private val dao = db.telemetryDao()
    val bleManager = ElmBleManager(context)
    private val bleScanner = BleScanner(context)

    // Live vehicle state exposed to UI
    private val _vehicleState = MutableStateFlow<VehicleTelemetry?>(null)
    val vehicleState: Flow<VehicleTelemetry?> = _vehicleState.asStateFlow()

    val connectionState = bleManager.connectionState
    val scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())

    // Response buffer: accumulates bytes until we get a complete response
    private val responseBuffer = StringBuilder()
    private var pendingPid: String? = null
    private val responseCallbacks = ConcurrentLinkedQueue<(String) -> Unit>()

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // PID polling sequence with interval weights
    private val pidSequence = listOf(
        ObdPids.SOC_DISPLAY to 2000L,                   // SOC every 2s
        ObdPids.PACK_VOLTAGE to 2000L,                  // Pack voltage every 2s
        ObdPids.PACK_CURRENT to 2000L,
        ObdPids.BATTERY_TEMP to 5000L,                  // Temp every 5s
        ObdPids.CELL_VOLTAGE_MIN to 5000L,
        ObdPids.CELL_VOLTAGE_MAX to 5000L,
        ObdPids.CHARGING_STATE to 5000L,
        ObdPids.CHARGING_POWER to 3000L,
        ObdPids.SOH to 10000L,                          // SOH every 10s
        ObdPids.ODOMETER to 10000L,
        ObdPids.DC_BUS_VOLTAGE to 5000L,
        ObdPids.DC_BUS_CURRENT to 5000L,
    ).map { PidRequest(it.first, it.second) }

    private data class PidRequest(val pid: String, val intervalMs: Long)

    // Most recent parsed values (for assembling telemetry snapshots)
    private val latestValues = mutableMapOf<String, Float?>()

    /**
     * Start BLE scanning for ELM327 adapters.
     */
    fun startScan() {
        scope.launch {
            val devices = mutableListOf<BluetoothDevice>()
            bleScanner.scan().collect { device ->
                if (devices.none { it.address == device.address }) {
                    devices.add(device)
                    scanResults.value = devices.toList()
                }
            }
        }
    }

    /**
     * Connect to a discovered ELM327 device and begin data flow.
     */
    fun connect(device: BluetoothDevice) {
        bleManager.connect(device)

        // Listen for raw BLE data and feed into response parser
        scope.launch {
            bleManager.rawData.collect { data ->
                data?.let { handleRawBytes(it) }
            }
        }

        // When connected, run init sequence then start polling
        scope.launch {
            bleManager.connectionState
                .filter { it == ElmBleManager.ConnectionState.CONNECTED }
                .first()

            Timber.i("BLE connected, initializing ELM327...")
            initializeElm327()
            startPidPolling()
        }
    }

    /**
     * Send ELM327 initialization commands sequentially.
     */
    private suspend fun initializeElm327() {
        for (cmd in ObdParser.initializationCommands()) {
            val response = sendCommandAndWait(cmd, timeoutMs = 3000)
            Timber.d("ELM init: $cmd → $response")
            delay(200) // Brief pause between init commands
        }
    }

    /**
     * Start the periodic PID polling loop.
     */
    private fun startPidPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                for (request in pidSequence) {
                    if (!isActive) break

                    val response = sendCommandAndWait(request.pid, timeoutMs = 2000)
                    if (response != null) {
                        val parsed = ObdParser.parseResponse(request.pid, response)
                        if (parsed.value != null) {
                            latestValues[request.pid] = parsed.value
                        }
                    }

                    // Assemble and emit telemetry snapshot every cycle
                    assembleAndEmitTelemetry()

                    delay(request.intervalMs.coerceAtMost(2000))
                }
            }
        }
    }

    /**
     * Send an OBD command and suspend until we get a response.
     */
    private suspend fun sendCommandAndWait(command: String, timeoutMs: Long): String? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingPid = command
                responseCallbacks.add { response ->
                    if (cont.isActive) cont.resume(response, null)
                }
                bleManager.sendCommand(command)
            }
        }
    }

    /**
     * Process raw BLE bytes → accumulate ASCII response → fire callbacks.
     */
    private fun handleRawBytes(data: ByteArray) {
        val text = String(data, Charsets.US_ASCII)
        responseBuffer.append(text)

        // ELM327 signals end of response with ">" prompt or "\r\r>"
        if (responseBuffer.contains(">")) {
            val fullResponse = responseBuffer.toString().substringBefore(">")
            responseBuffer.clear()

            val callback = responseCallbacks.poll()
            callback?.invoke(fullResponse.trim())
        }
    }

    /**
     * Assemble latest parsed values into a VehicleTelemetry and emit.
     */
    private fun assembleAndEmitTelemetry() {
        val telemetry = VehicleTelemetry(
            soc = latestValues[ObdPids.SOC_DISPLAY],
            soh = latestValues[ObdPids.SOH],
            batteryVoltage = latestValues[ObdPids.PACK_VOLTAGE],
            batteryCurrent = latestValues[ObdPids.PACK_CURRENT],
            batteryTempMin = latestValues[ObdPids.BATTERY_TEMP],
            batteryTempMax = latestValues[ObdPids.BATTERY_TEMP],
            cellVoltageMin = latestValues[ObdPids.CELL_VOLTAGE_MIN],
            cellVoltageMax = latestValues[ObdPids.CELL_VOLTAGE_MAX],
            odometer = latestValues[ObdPids.ODOMETER],
            chargingState = when (latestValues[ObdPids.CHARGING_STATE]?.toInt()) {
                1 -> ChargingState.CHARGING_AC
                2 -> ChargingState.CHARGING_DC
                3 -> ChargingState.CHARGING_COMPLETE
                else -> ChargingState.NOT_CHARGING
            },
            chargePower = latestValues[ObdPids.CHARGING_POWER]
        )

        _vehicleState.value = telemetry

        // Persist to Room (async, fire-and-forget)
        scope.launch {
            try {
                dao.insertTelemetry(telemetry)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist telemetry")
            }
        }
    }

    /**
     * Get historical telemetry for charting.
     */
    fun getHistory(sinceMs: Long) = dao.getTelemetryHistory(sinceMs)

    /**
     * Clean up all resources.
     */
    fun destroy() {
        pollingJob?.cancel()
        scope.cancel()
        bleManager.disconnect()
    }
}
