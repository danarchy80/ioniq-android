package com.ioniq.data.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ioniq.ble.BleScanner
import com.ioniq.ble.ClassicBtDeviceProvider
import com.ioniq.ble.ObdTransport
import com.ioniq.ble.ObdTransportFactory
import com.ioniq.ble.TransportHint
import com.ioniq.data.db.IoniqDatabase
import com.ioniq.data.model.CellReading
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ha.HomeAssistantClient
import com.ioniq.obd.BmsParsers
import com.ioniq.obd.IccuPids
import com.ioniq.obd.IoniqPids
import com.ioniq.obd.ObdParser
import com.ioniq.obd.ObdPids
import com.ioniq.obd.VehicleParsers
import com.ioniq.util.CapacityCalibrator
import com.ioniq.util.VinDecoder
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

    // Plain prefs for per-VIN calibration state (IccuPids DID memory,
    // CapacityCalibrator observations). VIN is detected once via DID F190.
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vehicle_prefs", Context.MODE_PRIVATE)
    private var vin: String? = null

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

    // Collector jobs for the connect() StateFlow observers — cancelled on re-connect
    // so repeated connect/disconnect cycles don't accumulate permanent collectors.
    private var pollStartJob: Job? = null
    private var pollStopJob: Job? = null

    // Last connected device + transport hint, for the notification "Reconnect" action.
    private var lastDevice: BluetoothDevice? = null
    private var lastHint: TransportHint = TransportHint.AUTO

    // ---- Scanning ----
    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()

    // ---- Connection ----

    /** Connect using auto-selected transport (based on device.type). */
    fun connect(device: BluetoothDevice) = connect(device, TransportHint.AUTO)

    /** Connect with explicit transport hint. */
    fun connect(device: BluetoothDevice, hint: TransportHint) {
        // Leaving the pristine "never connected" state — from here on any
        // DISCONNECTED transition is a real connection loss, not the initial idle.
        _isInitialState.value = false
        lastDevice = device
        lastHint = hint

        // Tear down any previous transport first
        disconnect()
        _connectionError.value = null

        // Cancel any prior connect() collectors so they don't stack up across reconnects
        pollStartJob?.cancel()
        pollStopJob?.cancel()

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
        pollStartJob = connectionState
            .filter { it == ObdTransport.ConnectionState.CONNECTED }
            .take(1)
            .onEach { startPolling(t) }
            .launchIn(scope)

        // Stop polling when disconnected
        pollStopJob = connectionState
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

    /**
     * Re-attempt a connection to the last device (used by the notification
     * "Reconnect" action). No-op if we never connected.
     */
    fun reconnect() {
        val device = lastDevice
        if (device == null) {
            Timber.w("reconnect() called with no last device — ignoring")
            return
        }
        Timber.i("Manual reconnect to ${device.address}")
        val t = transport
        if (t != null) {
            // Existing transport still holds the device — re-drive its connect path.
            t.connectToDevice(device)
        } else {
            // Transport was torn down — reconnect fresh with the stored hint.
            connect(device, lastHint)
        }
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
                    var fullCells: List<Int>? = null          // 192-cell mV list (UDS)
                    var aux: ObdParser.AuxBattery? = null     // 12V aux battery
                    var tpms: ObdParser.Tpms? = null          // tire pressures
                    var udsRead = UdsRead()                   // full UDS block result

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

                    // UDS DID block (~every 30 s): cells + 12V + TPMS + health,
                    // plus the charge-controller block while plugged in.
                    // Switches ELM send headers per-ECU and always restores the
                    // default header afterwards so the rapid poll keeps working.
                    if (slowTick % 15 == 0) {
                        val charging = chargingState == com.ioniq.data.model.ChargingState.CHARGING_AC ||
                            chargingState == com.ioniq.data.model.ChargingState.CHARGING_DC
                        val uds = readExtendedBlock(t, charging)
                        fullCells = uds.cells
                        aux = uds.aux
                        tpms = uds.tpms
                        udsRead = uds
                        if (uds.cells != null) {
                            cellMin = uds.cells.minOrNull()
                            cellMax = uds.cells.maxOrNull()
                        }
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
                        cellVoltageMax = cellMax,
                        auxBatteryVoltage = aux?.voltage,
                        auxBatteryCurrent = aux?.current,
                        auxBatterySoc = aux?.soc,
                        auxBatteryTemp = aux?.temp,
                        tpmsFlPsi = tpms?.flPsi, tpmsFrPsi = tpms?.frPsi,
                        tpmsRlPsi = tpms?.rlPsi, tpmsRrPsi = tpms?.rrPsi,
                        tpmsFlTemp = tpms?.flTemp, tpmsFrTemp = tpms?.frTemp,
                        tpmsRlTemp = tpms?.rlTemp, tpmsRrTemp = tpms?.rrTemp,
                        cellVoltagesCsv = fullCells?.joinToString(","),
                        evseMaxVoltage = udsRead.evse?.maxVoltage,
                        evseMaxCurrent = udsRead.evse?.maxCurrent,
                        evseMaxPowerKw = udsRead.evse?.maxPowerKw,
                        evseSessionEnergyKwh = udsRead.evse?.sessionEnergyKwh,
                        bmsTargetVoltage = udsRead.targets?.hvTargetVoltage,
                        bmsTargetCurrent = udsRead.targets?.hvTargetCurrent,
                        chargeTimeRemainingMin = when (chargingState) {
                            com.ioniq.data.model.ChargingState.CHARGING_DC ->
                                udsRead.targets?.dcTimeRemainingMin
                            com.ioniq.data.model.ChargingState.CHARGING_AC ->
                                udsRead.targets?.acTimeRemainingMin
                            else -> null
                        },
                        acChargeCount = udsRead.lifetime?.acCount,
                        dcChargeCount = udsRead.lifetime?.dcCount,
                        acChargeEnergyKwh = udsRead.lifetime?.acEnergyKwh,
                        dcChargeEnergyKwh = udsRead.lifetime?.dcEnergyKwh,
                        isolationResistanceKohm = udsRead.isolationKohm,
                        // BMS main (DID 0101 @ 7E4)
                        bmsSoc = udsRead.bmsMain?.socBms,
                        bmsCurrent = udsRead.bmsMain?.currentA,
                        bmsVoltage = udsRead.bmsMain?.voltageV,
                        // BMS status (DID 0105 @ 7E4)
                        soh = udsRead.bmsStatus?.soh,
                        maxChargePowerKw = udsRead.bmsStatus?.maxChargePowerKw,
                        maxDischargePowerKw = udsRead.bmsStatus?.maxDischargePowerKw,
                        remainingEnergyKwh = udsRead.bmsStatus?.remainingEnergyKwh,
                        heaterStatus = udsRead.bmsStatus?.heaterStatus,
                        heaterPowerKw = udsRead.bmsStatus?.heaterPowerKw,
                        // VCU (DID 0101 @ 7E2)
                        vcuSpeed = udsRead.vcu?.speedKmh,
                        vcuGear = udsRead.vcu?.gear,
                        vcuReady = udsRead.vcu?.ready,
                        vcuTorqueOutput = udsRead.vcu?.torqueOutputNm,
                        vcuMotorRpm = udsRead.vcu?.motorRpm,
                        // Motor / inverter (DID 0101 @ 7E3)
                        motorStatorTemp = udsRead.motor?.statorTempC,
                        motorInverterTemp = udsRead.motor?.inverterTempC,
                        motorDcLinkVoltage = udsRead.motor?.dcLinkVoltageV,
                        motorDcLinkCurrent = udsRead.motor?.dcLinkCurrentA,
                        // HVAC (DID 0100 @ 7B3)
                        hvacCabinTemp = udsRead.hvac?.cabinTempC,
                        hvacOutsideTemp = udsRead.hvac?.outsideTempC,
                        hvacCompressorSpeed = udsRead.hvac?.compressorRpm,
                        hvacCoolantIn = udsRead.hvac?.coolantInTempC,
                        hvacCoolantOut = udsRead.hvac?.coolantOutTempC,
                        // Cluster (DIDs B002 / B00C @ 7C6)
                        clusterDisplaySpeed = udsRead.cluster?.displaySpeedKmh,
                        clusterRange = udsRead.cluster?.rangeKm,
                        tripADistance = udsRead.clusterTrips?.tripADistanceKm,
                        tripBDistance = udsRead.clusterTrips?.tripBDistanceKm,
                        // Body (DID BC03 @ 770)
                        doorDriverOpen = udsRead.doors?.driverOpen,
                        doorPassengerOpen = udsRead.doors?.passengerOpen,
                        doorRearLeftOpen = udsRead.doors?.rearLeftOpen,
                        doorRearRightOpen = udsRead.doors?.rearRightOpen,
                        hoodOpen = udsRead.doors?.hoodOpen,
                        tailgateOpen = udsRead.doors?.tailgateOpen,
                        // Calibrated usable capacity (CapacityCalibrator)
                        usableCapacityKwh = udsRead.usableCapacityKwh
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
                    fullCells?.forEachIndexed { i, mv ->
                        cellReadingDao.insert(
                            CellReading(telemetryId = rowId, cellIndex = i, voltage = mv.toFloat())
                        )
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

    // ---- UDS DID block (cells / 12V / TPMS / charging / health) ----

    /** Everything one UDS poll cycle can return; each field null if its read failed. */
    data class UdsRead(
        val cells: List<Int>? = null,
        val aux: ObdParser.AuxBattery? = null,
        val tpms: ObdParser.Tpms? = null,
        val session: ObdParser.ChargeSession? = null,
        val evse: ObdParser.EvseInfo? = null,
        val targets: ObdParser.ChargeTargets? = null,
        val lifetime: ObdParser.LifetimeCharging? = null,
        val isolationKohm: Int? = null,
        // BMS main / status (DIDs 0101 / 0105 @ 7E4)
        val bmsMain: BmsParsers.BmsMain? = null,
        val bmsStatus: BmsParsers.BmsStatus? = null,
        // Non-battery ECUs
        val vcu: VehicleParsers.VcuStatus? = null,
        val motor: VehicleParsers.MotorData? = null,
        val hvac: VehicleParsers.HvacData? = null,
        val cluster: VehicleParsers.ClusterData? = null,
        val clusterTrips: VehicleParsers.ClusterTrips? = null,
        val doors: VehicleParsers.DoorState? = null,
        val esc: VehicleParsers.EscData? = null,
        // Self-calibrated usable pack capacity (CapacityCalibrator)
        val usableCapacityKwh: Float? = null
    )

    /**
     * Reads the extended UDS DID block: BMS main/status + 192 cell voltages +
     * HV isolation + lifetime charge stats (BMS @ 7E4), 12V aux battery with
     * facelift DID detection (ICCU @ 7E5), TPMS (IPC @ 7A0), VCU (7E2),
     * motor (7E3), HVAC (7B3), cluster (7C6), body doors (770, needs the
     * extended diagnostic session), ESC dynamics (730), and — while charging —
     * the charge-controller block (CCS @ 744). Also detects the VIN once
     * (VCU DID F190) and feeds CapacityCalibrator.
     *
     * Each ECU needs the ELM327 send header switched via ATSH first; the
     * default header is always restored in `finally` so the rapid poll keeps
     * working even if this block fails partway (cheap ELM clones can choke
     * on header switches). Per-ECU try/catch keeps one dead ECU from killing
     * the whole block.
     */
    private suspend fun readExtendedBlock(t: ObdTransport, charging: Boolean): UdsRead {
        var result = UdsRead()
        try {
            // ---- BMS @ 7E4: main + status + cells + isolation + lifetime ----
            t.sendCommand(ObdPids.HDR_BMS, timeoutMs = 2000)
            val bmsMain = t.readPid(IoniqPids.Bms.MAIN)?.let { BmsParsers.parseMain(it) }
            val bmsStatus = t.readPid(IoniqPids.Bms.STATUS)?.let { BmsParsers.parseStatus(it) }
            val blocks = listOf(
                IoniqPids.Bms.CELLS_1, IoniqPids.Bms.CELLS_2, IoniqPids.Bms.CELLS_3,
                IoniqPids.Bms.CELLS_4, IoniqPids.Bms.CELLS_5, IoniqPids.Bms.CELLS_6
            ).mapNotNull { pid -> t.readPid(pid)?.let(ObdParser::parseCellBlock) }
            val cells = if (blocks.sumOf { it.size } >= 90) blocks.flatten() else null
            val isolation = t.readPid(ObdPids.HV_ISOLATION)?.let(ObdParser::parseIsolationResistance)
            val lifetime = t.readPid(IoniqPids.Bms.LIFETIME)?.let(ObdParser::parseLifetimeCharging)
            result = result.copy(
                bmsMain = bmsMain, bmsStatus = bmsStatus,
                cells = cells, isolationKohm = isolation, lifetime = lifetime
            )

            // ---- VIN detection: once, after the first successful BMS read ----
            if (vin == null && bmsMain != null) {
                try {
                    t.sendCommand(ObdPids.HDR_VCU, timeoutMs = 2000)
                    val vinRaw = t.readPid("22F190")
                    val decoded = VinDecoder.decode(ObdParser.extractVin(vinRaw))
                    if (decoded != null && decoded.valid) {
                        vin = decoded.vin
                        Timber.i("VIN detected: ${decoded.vin}")
                    }
                } catch (e: TimeoutCancellationException) {
                    Timber.d("VIN read timed out: ${e.message}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d("VIN read failed: ${e.message}")
                }
            }

            // ---- ICCU @ 7E5: 12V aux with facelift DID detection ----
            t.sendCommand(ObdPids.HDR_LDC, timeoutMs = 2000)
            var aux12v: ObdParser.AuxBattery? = null
            for (did in IccuPids.candidates(prefs, vin)) {
                val raw = t.readPid(did)
                if (raw != null) {
                    aux12v = ObdParser.parseAuxBattery(raw)
                    if (aux12v != null) {
                        IccuPids.noteSuccess(prefs, vin, did)
                        break
                    }
                }
            }
            result = result.copy(aux = aux12v)

            // ---- IPC @ 7A0: TPMS ----
            t.sendCommand(ObdPids.HDR_TPMS, timeoutMs = 2000)
            result = result.copy(tpms = t.readPid(ObdPids.TPMS)?.let(ObdParser::parseTpms))

            // ---- VCU @ 7E2: gear / ready / speed / torque / rpm ----
            try {
                t.sendCommand(ObdPids.HDR_VCU, timeoutMs = 2000)
                result = result.copy(
                    vcu = t.readPid(IoniqPids.Vcu.DRIVE_STATUS)?.let { VehicleParsers.parseVcu(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("VCU read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("VCU read failed: ${e.message}")
            }

            // ---- Motor @ 7E3: stator/inverter temps, DC link ----
            try {
                t.sendCommand(ObdPids.HDR_MOTOR, timeoutMs = 2000)
                result = result.copy(
                    motor = t.readPid(IoniqPids.Motor.MOTOR_DATA)?.let { VehicleParsers.parseMotor(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("Motor read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Motor read failed: ${e.message}")
            }

            // ---- HVAC @ 7B3: climate ----
            try {
                t.sendCommand(ObdPids.HDR_HVAC, timeoutMs = 2000)
                result = result.copy(
                    hvac = t.readPid(IoniqPids.Hvac.CLIMATE)?.let { VehicleParsers.parseHvac(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("HVAC read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("HVAC read failed: ${e.message}")
            }

            // ---- Cluster @ 7C6: displayed speed / range / trips ----
            try {
                t.sendCommand(ObdPids.HDR_CLUSTER, timeoutMs = 2000)
                result = result.copy(
                    cluster = t.readPid(IoniqPids.Cluster.MAIN)?.let { VehicleParsers.parseCluster(it) },
                    clusterTrips = t.readPid(IoniqPids.Cluster.TRIPS)?.let { VehicleParsers.parseClusterTrips(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("Cluster read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Cluster read failed: ${e.message}")
            }

            // ---- Body @ 770: doors (needs extended diagnostic session 1003) ----
            try {
                t.sendCommand(ObdPids.HDR_BODY, timeoutMs = 2000)
                t.sendCommand("1003", timeoutMs = 2000)
                delay(300)
                result = result.copy(
                    doors = t.readPid(IoniqPids.Body.DOORS)?.let { VehicleParsers.parseDoors(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("Body ECU read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Body ECU read failed: ${e.message}")
            }

            // ---- ESC @ 730: wheel speeds / steering angle / accelerations ----
            try {
                t.sendCommand(ObdPids.HDR_ESC, timeoutMs = 2000)
                result = result.copy(
                    esc = t.readPid(IoniqPids.Esc.DYNAMICS)?.let { VehicleParsers.parseEsc(it) }
                )
            } catch (e: TimeoutCancellationException) {
                Timber.d("ESC read timed out: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("ESC read failed: ${e.message}")
            }

            // ---- CCS @ 744: charge session — only meaningful while plugged in ----
            if (charging) {
                t.sendCommand(ObdPids.HDR_CCS, timeoutMs = 2000)
                result = result.copy(
                    session = t.readPid(ObdPids.CHARGE_CONTROLLER)?.let(ObdParser::parseChargeController),
                    evse = t.readPid(ObdPids.EVSE_INFO)?.let(ObdParser::parseEvseInfo),
                    targets = t.readPid(ObdPids.CHARGE_TARGETS)?.let(ObdParser::parseChargeTargets)
                )
            }

            // ---- Capacity calibrator: feed SOC + remaining energy (+ SOH) ----
            val soc = bmsMain?.socBms ?: bmsStatus?.displayedSoc
            val remaining = bmsStatus?.remainingEnergyKwh
            val soh = bmsStatus?.soh
            val currentVin = vin
            if (soc != null && remaining != null && currentVin != null) {
                val cap = CapacityCalibrator.observe(prefs, currentVin, soc, remaining, soh)
                result = result.copy(usableCapacityKwh = cap)
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("Extended block timed out: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Extended block read failed")
        } finally {
            runCatching { t.sendCommand(ObdPids.HDR_DEFAULT, timeoutMs = 2000) }
        }
        return result
    }

    // ---- Home Assistant Integration ----

    /**
     * Encrypted preferences for HA credentials (WebSocket URL + long-lived token).
     * Uses a distinct file name from the legacy plaintext "ha_config" prefs so the
     * one-time migration below can read old values and clear them safely.
     */
    private val haPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ha_config_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** One-time migration of the legacy plaintext HA config into encrypted storage. */
    private fun migrateLegacyHaConfig() {
        val legacy = context.getSharedPreferences("ha_config", Context.MODE_PRIVATE)
        val url = legacy.getString("ha_ws_url", null)
        val token = legacy.getString("ha_token", null)
        if (url == null && token == null) return // nothing to migrate
        val enc = haPrefs
        if (enc.contains("ha_ws_url") || enc.contains("ha_token")) {
            // Already migrated — just wipe the legacy plaintext copy.
            legacy.edit().clear().apply()
            return
        }
        enc.edit()
            .putString("ha_ws_url", url)
            .putString("ha_token", token)
            .apply()
        legacy.edit().clear().apply()
        Timber.i("Migrated legacy HA config to encrypted storage")
    }

    private fun initHaClient() {
        migrateLegacyHaConfig()
        val prefs = haPrefs
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
        haPrefs.edit()
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
        haPrefs.edit().clear().apply()
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
