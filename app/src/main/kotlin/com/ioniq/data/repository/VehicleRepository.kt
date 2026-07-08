package com.ioniq.data.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.ioniq.ble.BleScanner
import com.ioniq.ble.ElmBleManager
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
 *   BLE Scanner → ElmBleManager → OBD PID polling → Room DB → Home Assistant push
 *
 * Lifecycle: created by ViewModel or Service, call destroy() when done.
 */
class VehicleRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner = BleScanner(context)
    private val elmManager = ElmBleManager(context)
    private val db = IoniqDatabase.getDatabase(context)
    private val telemetryDao = db.telemetryDao()
    private val cellReadingDao = db.cellReadingDao()
    // ObdParser is a Kotlin singleton object — call directly as ObdParser.method()

    // ---- Home Assistant client (lazy init with saved config) ----
    private var haClient: HomeAssistantClient? = null

    // ---- Exposed state ----
    val scanResults: StateFlow<List<BluetoothDevice>> = scanner.scanResults
    val scanError: StateFlow<String?> = scanner.scanError
    val connectionState: StateFlow<ElmBleManager.ConnectionState> = elmManager.connectionState
    val isReconnecting: StateFlow<Boolean> = elmManager.isReconnecting
    val reconnectAttempts: StateFlow<Int> = elmManager.reconnectCount

    private val _vehicleState = MutableStateFlow<VehicleTelemetry?>(null)
    val vehicleState: StateFlow<VehicleTelemetry?> = _vehicleState.asStateFlow()

    private var pollJob: Job? = null

    // ---- Scanning ----
    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()

    // ---- Connection ----
    fun connect(device: BluetoothDevice) {
        elmManager.connectToDevice(device)

        // Start polling when connected
        connectionState
            .filter { it == ElmBleManager.ConnectionState.CONNECTED }
            .take(1)
            .onEach { startPolling() }
            .launchIn(scope)

        // Stop polling when disconnected
        connectionState
            .filter { it == ElmBleManager.ConnectionState.DISCONNECTED }
            .onEach {
                pollJob?.cancel()
                pollJob = null
            }
            .launchIn(scope)
    }

    fun disconnect() {
        elmManager.disconnect()
        pollJob?.cancel()
        pollJob = null
    }

    // ---- PID Polling Loop ----
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            Timber.i("Starting OBD-II PID polling loop")

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

            // Slow poll: cumulative energy stats (every 10s)
            var slowTick = 0

            while (isActive && connectionState.value == ElmBleManager.ConnectionState.CONNECTED) {

                val now = Instant.now().toEpochMilli()
                val soc = elmManager.readPid(ObdPids.SOC_DISPLAY)?.let { ObdParser.parseSoc(it) }
                val voltage = elmManager.readPid(ObdPids.PACK_VOLTAGE)?.let { ObdParser.parseBatteryVoltage(it) }
                val current = elmManager.readPid(ObdPids.PACK_CURRENT)?.let { ObdParser.parseBatteryCurrent(it) }
                val battTempMax = elmManager.readPid(ObdPids.BATTERY_TEMP)?.let {
                    ObdParser.parseBatteryTemp(it).maxOrNull()
                }
                val inletTemp = elmManager.readPid(ObdPids.INLET_TEMP)?.let { ObdParser.parseInletTemp(it) }
                val ambientTemp = elmManager.readPid(ObdPids.AMBIENT_TEMP)?.let { ObdParser.parseAmbientTemp(it) }
                val chargingState = elmManager.readPid(ObdPids.CHARGING_STATE)?.let { ObdParser.parseChargingState(it) } ?: com.ioniq.data.model.ChargingState.NOT_CHARGING

                // Slow-poll fields every 5th cycle
                var cumCharge: Float? = null
                var cumDischarge: Float? = null
                var cellMin: Int? = null
                var cellMax: Int? = null
                var cellVoltages: List<CellReading>? = null

                if (slowTick % 5 == 0) {
                    cumCharge = elmManager.readPid(ObdPids.CUMULATIVE_ENERGY_CHARGED)?.let {
                        ObdParser.parseCumulativeEnergy(it)
                    }
                    cumDischarge = elmManager.readPid(ObdPids.CUMULATIVE_ENERGY_DISCHARGED)?.let {
                        ObdParser.parseCumulativeEnergy(it)
                    }
                    val cellData = elmManager.readPid(ObdPids.CELL_VOLTAGES)?.let {
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

                // Persist to Room
                val rowId = telemetryDao.insert(telemetry)
                cellVoltages?.forEach { cell ->
                    cellReadingDao.insert(cell.copy(telemetryId = rowId))
                }

                _vehicleState.value = telemetry

                // Push to Home Assistant
                haClient?.pushTelemetry(telemetry)

                slowTick++

                // Poll interval: 2s between cycles
                delay(2000L)
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

    // ---- Lifecycle ----

    fun destroy() {
        pollJob?.cancel()
        scanner.stopScan()
        elmManager.release()
        haClient?.release()
        scope.cancel()
    }
}
