package com.ioniq.data.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.ioniq.ble.BleScanner
import com.ioniq.ble.ClassicBtDeviceProvider
import com.ioniq.ble.ObdTransport
import com.ioniq.ble.ObdTransportFactory
import com.ioniq.ble.TransportHint
import com.ioniq.data.db.IoniqDatabase
import com.ioniq.data.model.CellReading
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ha.HomeAssistantClient
import com.ioniq.obd.ObdParser
import com.ioniq.obd.ObdPids
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.Instant

/**
 * Central repository orchestrating:
 *   BLE Scanner → ObdTransport (BLE or classic RFCOMM) → OBD PID polling → Room DB → Home Assistant
 *
 * Lifecycle: singleton instance, call destroy() when app exits.
 *
 * Transport selection: the right implementation (ElmBleManager or ElmClassicManager)
 * is picked per-device based on device.type (see ObdTransportFactory). This fixes
 * the long-standing bug where classic SPP adapters were being driven through BLE
 * GATT, causing the OBD bus to hang post-connect.
 */
class VehicleRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner = BleScanner(context)
    private val classicProvider = ClassicBtDeviceProvider(context)
    private val db = IoniqDatabase.getDatabase(context)
    private val telemetryDao = db.telemetryDao()
    private val cellReadingDao = db.cellReadingDao()

    // ---- Active transport (BLE or classic RFCOMM) ----
    private var transport: ObdTransport? = null
    private var transportFlowsMirrorJob: Job? = null

    // ---- Home Assistant client (lazy init with saved config) ----
    private var haClient: HomeAssistantClient? = null

    companion object {
        @Volatile
        private var INSTANCE: VehicleRepository? = null

        fun getInstance(context: Context): VehicleRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ---- Exposed state (mirrored from active transport) ----
    val scanResults: StateFlow<List<BluetoothDevice>> = scanner.scanResults
    val scanError: StateFlow<String?> = scanner.scanError

    private val _connectionState = MutableStateFlow(ObdTransport.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ObdTransport.ConnectionState> = _connectionState.asStateFlow()
    private val _isInitialState = MutableStateFlow(true)
    val isInitialState: StateFlow<Boolean> = _isInitialState.asStateFlow()
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _reconnectAttempts = MutableStateFlow(0)
    val reconnectAttempts: StateFlow<Int> = _reconnectAttempts.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    /**
     * High-level poll health — surfaced to the UI as a subtle status banner
     * instead of triggering a hard disconnect on failure.
     *
     *  - POLLING:          normal rapid polling every 2s
     *  - VEHICLE_OFF:      all PIDs returning null (vehicle was turned off
     *                      or entered sleep) — continue slow-polling every 10s
     *                      looking for re-awakening
     *  - ECU_UNREACHABLE:  OBD adapter / ECU not answering at all (timeouts /
     *                      exceptions); slow-poll every 30s so the UI can
     *                      still show "waiting for vehicle" while the ELM
     *                      recoveries (\r\r) and auto-reconnect do their thing
     */
    enum class PollStatus { POLLING, VEHICLE_OFF, ECU_UNREACHABLE }

    private val _pollStatus = MutableStateFlow(PollStatus.POLLING)
    val pollStatus: StateFlow<PollStatus> = _pollStatus.asStateFlow()

    private val _pollFailCount = MutableStateFlow(0)
    val pollFailCount: StateFlow<Int> = _pollFailCount.asStateFlow()

    fun clearConnectionError() {
        _connectionError.value = null
    }

    val connectedTransportName: StateFlow<String?>
        get() = MutableStateFlow(transport?.let {
            if (it is com.ioniq.ble.ElmBleManager) "BLE" else "Classic RFCOMM"
        })

    private val _vehicleState = MutableStateFlow<VehicleTelemetry?>(null)
    val vehicleState: StateFlow<VehicleTelemetry?> = _vehicleState.asStateFlow()

    private var pollJob: Job? = null

    // ---- Scanning ----
    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()

    // ---- Connection ----

    /** Connect using auto-selected transport (based on device.type). */
    fun connect(device: BluetoothDevice) = connect(device, TransportHint.AUTO)

    /** Connect with explicit transport hint. */
    fun connect(device: BluetoothDevice, hint: TransportHint) {
        // Tear down any previous transport first
        disconnect()
        _connectionError.value = null

        val t = try {
            val created = ObdTransportFactory.create(context, device, hint)
            transport = created
            mirrorTransportState(created)
            created
        } catch (e: Throwable) {
            Timber.e(e, "Failed to create transport for ${device.address}")
            _connectionError.value = "Failed to initialize connection: ${e.message}"
            _connectionState.value = ObdTransport.ConnectionState.DISCONNECTED
            return
        }

        try {
            t.connectToDevice(device)
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied connecting to ${device.address}")
            _connectionError.value = "Bluetooth permission denied"
            _connectionState.value = ObdTransport.ConnectionState.DISCONNECTED
        } catch (e: Throwable) {
            Timber.e(e, "Exception connecting to ${device.address}")
            _connectionError.value = "Connection failed: ${e.message ?: "unknown error"}"
            _connectionState.value = ObdTransport.ConnectionState.DISCONNECTED
        }

        // Start polling when connected
        connectionState
            .filter { it == ObdTransport.ConnectionState.CONNECTED }
            .take(1)
            .onEach { startPolling(t) }
            .launchIn(scope)

        // Stop polling when disconnected
        connectionState
            .filter { it == ObdTransport.ConnectionState.DISCONNECTED }
            .onEach {
                pollJob?.cancel()
                pollJob = null
            }
            .launchIn(scope)
    }

    /**
     * Mirror the active transport's state flows into this repository's own flows
     * so UI/ViewModel code stays unchanged. Releasing happens in disconnect().
     */
    private fun mirrorTransportState(t: ObdTransport) {
        transportFlowsMirrorJob?.cancel()
        transportFlowsMirrorJob = scope.launch {
            launch { t.connectionState.collect { _connectionState.value = it } }
            launch { t.isReconnecting.collect { _isReconnecting.value = it } }
            launch { t.reconnectCount.collect { _reconnectAttempts.value = it } }
        }
    }

    fun disconnect() {
        transportFlowsMirrorJob?.cancel()
        transportFlowsMirrorJob = null
        transport?.disconnect()
        transport?.release()
        transport = null
        _connectionState.value = ObdTransport.ConnectionState.DISCONNECTED
        _isReconnecting.value = false
        _reconnectAttempts.value = 0
        pollJob?.cancel()
        pollJob = null
    }

    // ---- PID Polling Loop (works on EITHER transport now) ----

    private fun startPolling(t: ObdTransport) {
        pollJob?.cancel()
        pollJob = scope.launch {
            Timber.i("Starting OBD-II PID polling loop over ${if (t is com.ioniq.ble.ElmBleManager) "BLE" else "Classic RFCOMM"}")

            // Connect HA client if config available
            initHaClient()

            // Rapid poll: SOC, voltage, current, temps (every 2s)
            val rapidPids = listOf(
                ObdPids.SOC_DISPLAY,
                ObdPids.PACK_VOLTAGE,
                ObdPids.PACK_CURRENT,
                ObdPids.BATTERY_TEMP,
                ObdPids.INLET_TEMP,
                ObdPids.AMBIENT_TEMP,
                ObdPids.CHARGING_STATE
            )

            // Slow poll: cumulative energy stats (every 5th cycle)
            var slowTick = 0
            var consecutiveFailures = 0
            val maxFailures = 10

            // Intervals for each poll status
            val NORMAL_DELAY_MS = 2000L
            val VEHICLE_OFF_DELAY_MS = 10_000L
            val ECU_UNREACHABLE_DELAY_MS = 30_000L
            val RECOVER_THRESHOLD_FAILS = 100 // never give up — let transport
            // auto-reconnect handle the physical layer

            while (isActive && t.connectionState.value == ObdTransport.ConnectionState.CONNECTED) {

                try {
                    val now = Instant.now().toEpochMilli()
                    val soc = t.readPid(ObdPids.SOC_DISPLAY)?.let { ObdParser.parseSoc(it) }
                    val voltage = t.readPid(ObdPids.PACK_VOLTAGE)?.let { ObdParser.parseBatteryVoltage(it) }
                    val current = t.readPid(ObdPids.PACK_CURRENT)?.let { ObdParser.parseBatteryCurrent(it) }
                    val battTempMax = t.readPid(ObdPids.BATTERY_TEMP)?.let {
                        ObdParser.parseBatteryTemp(it).maxOrNull()
                    }
                    val inletTemp = t.readPid(ObdPids.INLET_TEMP)?.let { ObdParser.parseInletTemp(it) }
                    val ambientTemp = t.readPid(ObdPids.AMBIENT_TEMP)?.let { ObdParser.parseAmbientTemp(it) }
                    val chargingState = t.readPid(ObdPids.CHARGING_STATE)?.let { ObdParser.parseChargingState(it) } ?: com.ioniq.data.model.ChargingState.NOT_CHARGING

                    // Slow-poll fields every 5th cycle
                    var cumCharge: Float? = null
                    var cumDischarge: Float? = null
                    var cellMin: Int? = null
                    var cellMax: Int? = null
                    var cellVoltages: List<CellReading>? = null

                    if (slowTick % 5 == 0) {
                        cumCharge = t.readPid(ObdPids.CUMULATIVE_ENERGY_CHARGED)?.let {
                            ObdParser.parseCumulativeEnergy(it)
                        }
                        cumDischarge = t.readPid(ObdPids.CUMULATIVE_ENERGY_DISCHARGED)?.let {
                            ObdParser.parseCumulativeEnergy(it)
                        }
                        val cellData = t.readPid(ObdPids.CELL_VOLTAGES)?.let {
                            ObdParser.parseCellVoltages(now, 0, it)
                        }
                        cellVoltages = cellData
                        cellMin = cellData?.minOfOrNull { it.voltage }?.toInt()
                        cellMax = cellData?.maxOfOrNull { it.voltage }?.toInt()
                    }

                    val telemetry = VehicleTelemetry(
                        id = 0,
                        timestamp = now,
                        soc = soc,
                        batteryVoltage = voltage,
                        batteryCurrent = current,
                        batteryTempMax = battTempMax,
                        inletTemp = inletTemp,
                        ambientTemp = ambientTemp,
                        chargingState = chargingState,
                        cumulativeEnergyCharged = cumCharge,
                        cumulativeEnergyDischarged = cumDischarge,
                        cellVoltageMin = cellMin,
                        cellVoltageMax = cellMax
                    )

                    // Detect unresponsive adapter: all critical PIDs returned null
                    val allFailed = soc == null && voltage == null && current == null && battTempMax == null
                    if (allFailed) {
                        consecutiveFailures++
                        _pollFailCount.value = consecutiveFailures
                        if (_pollStatus.value == PollStatus.POLLING && consecutiveFailures >= 3) {
                            Timber.i("Poll entered VEHICLE_OFF after $consecutiveFailures null cycles")
                            _pollStatus.value = PollStatus.VEHICLE_OFF
                        }
                        if (consecutiveFailures > 0) {
                            Timber.v("Poll cycle ${consecutiveFailures}: all PID reads returned null — slow polling")
                        }
                        // NOTE: Do NOT hard-disconnect. Let the transport's
                        // auto-reconnect and the \r\r recovery handle ELM-level
                        // issues; the UI sees VEHICLE_OFF in the meantime.
                    } else {
                        if (consecutiveFailures > 0) Timber.i("Recovered after $consecutiveFailures null cycles")
                        consecutiveFailures = 0
                        _pollFailCount.value = 0
                        if (_pollStatus.value != PollStatus.POLLING) {
                            Timber.i("Poll recovered into POLLING state")
                        }
                        _pollStatus.value = PollStatus.POLLING
                    }

                    // Persist to Room (skip when everything is null to avoid spam rows)
                    val rowId = telemetryDao.insert(telemetry)
                    cellVoltages?.forEach { cell ->
                        cellReadingDao.insert(cell.copy(telemetryId = rowId))
                    }

                    _vehicleState.value = telemetry

                    // Push to Home Assistant
                    haClient?.pushTelemetry(telemetry)

                    slowTick++

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // propagate cancellation normally
                } catch (e: TimeoutCancellationException) {
                    // ELM sendCommand timeout — counts as ECU unreachable
                    consecutiveFailures++
                    _pollFailCount.value = consecutiveFailures
                    if (_pollStatus.value != PollStatus.ECU_UNREACHABLE) {
                        Timber.w("Poll entering ECU_UNREACHABLE after timeout: ${e.message}")
                        _pollStatus.value = PollStatus.ECU_UNREACHABLE
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Poll cycle exception: ${e::class.simpleName}: ${e.message}")
                    consecutiveFailures++
                    _pollFailCount.value = consecutiveFailures
                    if (_pollStatus.value != PollStatus.ECU_UNREACHABLE) {
                        Timber.w("Poll entering ECU_UNREACHABLE after exception: ${e::class.simpleName}")
                        _pollStatus.value = PollStatus.ECU_UNREACHABLE
                    }
                    // NOTE: We do NOT hard-disconnect anymore. The transport's
                    // reconnect logic will re-establish the link; the UI simply
                    // sees ECU_UNREACHABLE and keeps polling on a slower cadence.
                }

                // Dynamic poll interval: normal while healthy, slower otherwise
                val delayMs = when (_pollStatus.value) {
                    PollStatus.POLLING -> NORMAL_DELAY_MS
                    PollStatus.VEHICLE_OFF -> VEHICLE_OFF_DELAY_MS
                    PollStatus.ECU_UNREACHABLE -> ECU_UNREACHABLE_DELAY_MS
                }
                delay(delayMs)
            }

            Timber.i("Polling loop ended")
        }
    }

    // ---- Home Assistant Integration ----

    private fun initHaClient() {
        val prefs = context.getSharedPreferences("ha_config", Context.MODE_PRIVATE)
        val url = prefs.getString("ha_ws_url", null) ?: return
        val token = prefs.getString("ha_token", null) ?: return

        if (haClient != null) return // already connected

        haClient = HomeAssistantClient(url, token).also { client ->
            client.connect()
            // Log HA state changes
            client.state
                .onEach { Timber.d("HA state: $it") }
                .launchIn(scope)
        }
    }

    /**
     * Configure Home Assistant connection (called from Settings screen).
     */
    fun configureHomeAssistant(wsUrl: String, token: String) {
        val prefs = context.getSharedPreferences("ha_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ha_ws_url", wsUrl)
            .putString("ha_token", token)
            .apply()

        haClient?.release()
        haClient = null
        initHaClient()
    }

    fun disconnectHomeAssistant() {
        haClient?.release()
        haClient = null
        context.getSharedPreferences("ha_config", Context.MODE_PRIVATE).edit().clear().apply()
    }

    val haState: StateFlow<HomeAssistantClient.HaState>
        get() = haClient?.state ?: MutableStateFlow(HomeAssistantClient.HaState.DISCONNECTED)

    // ---- Classic (paired) Bluetooth devices & in-app pairing ----

    /** Live list of bonded (paired) classic-BT devices; used by the in-app picker. */
    val pairedClassicDevices: StateFlow<List<BluetoothDevice>>
        get() = classicProvider.pairedDevices

    /** Re-read bond list from the OS (e.g. after returning from a pairing dialog). */
    fun refreshPairedDevices() {
        classicProvider.refresh()
    }

    /** Start a pairing flow for an unpaired classic-BT device. Returns true if bonding began. */
    fun pair(device: BluetoothDevice): Boolean {
        classicProvider.startListening()
        return classicProvider.pair(device).also {
            if (it) Timber.i("Bonding started for ${device.address}")
        }
    }

    // ---- Lifecycle ----

    fun destroy() {
        pollJob?.cancel()
        scanner.stopScan()
        classicProvider.stopListening()
        transport?.release()
        transport = null
        transportFlowsMirrorJob?.cancel()
        haClient?.release()
        scope.cancel()
    }
}
