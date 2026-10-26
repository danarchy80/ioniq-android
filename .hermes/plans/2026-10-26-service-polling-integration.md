# Service Polling Integration Plan

## Goal
Wire the new parsers (BmsParsers, VehicleParsers, CapacityCalibrator, IccuPids) into VehicleRepository's polling loop so the new VehicleTelemetry fields actually get populated.

## Scope — single dispatch

### File to modify:
1. `app/src/main/kotlin/com/ioniq/data/repository/VehicleRepository.kt`

### Exact changes:

#### 1. Add imports at top of file:
```kotlin
import com.ioniq.obd.BmsParsers
import com.ioniq.obd.VehicleParsers
import com.ioniq.obd.EcuConfig
import com.ioniq.obd.IoniqPids
import com.ioniq.obd.IccuPids
import com.ioniq.util.CapacityCalibrator
import com.ioniq.util.VinDecoder
```

#### 2. Add a `prefs` property to VehicleRepository:
```kotlin
private val prefs: SharedPreferences = context.getSharedPreferences("vehicle_prefs", Context.MODE_PRIVATE)
private var vin: String? = null
```

#### 3. Extend `UdsRead` data class with new fields:
```kotlin
data class UdsRead(
    val cells: List<Int>? = null,
    val aux: ObdParser.AuxBattery? = null,
    val tpms: ObdParser.Tpms? = null,
    val session: ObdParser.ChargeSession? = null,
    val evse: ObdParser.EvseInfo? = null,
    val targets: ObdParser.ChargeTargets? = null,
    val lifetime: ObdParser.LifetimeCharging? = null,
    val isolationKohm: Int? = null,
    // NEW:
    val bmsMain: BmsParsers.BmsMain? = null,
    val bmsStatus: BmsParsers.BmsStatus? = null,
    val vcu: VehicleParsers.VcuStatus? = null,
    val motor: VehicleParsers.MotorData? = null,
    val hvac: VehicleParsers.HvacData? = null,
    val cluster: VehicleParsers.ClusterData? = null,
    val clusterTrips: VehicleParsers.ClusterTrips? = null,
    val doors: VehicleParsers.DoorState? = null,
    val esc: VehicleParsers.EscData? = null,
    val usableCapacityKwh: Float? = null
)
```

#### 4. Add new method `readExtendedBlock(t: ObdTransport, charging: Boolean): UdsRead`

This method polls all the new ECUs. It must:
- Switch headers per ECU (ATSH7E2, ATSH7E3, etc.)
- Send `1003` diagnostic session before Body (770) and ESC (730) which need extended session
- Use IccuPids.candidates() for 12V detection — try first candidate, if it fails try second, call noteSuccess()
- Call CapacityCalibrator.observe() with SOC + remainingEnergy + SOH
- Always restore default header in `finally`
- Catch exceptions per-ECU so one failure doesn't kill the whole block

Pseudocode structure:
```kotlin
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

        // ---- ICCU @ 7E5: 12V with facelift detection ----
        t.sendCommand(ObdPids.HDR_LDC, timeoutMs = 2000)
        val candidates = IccuPids.candidates(prefs, vin)
        var aux12v: ObdParser.AuxBattery? = null
        for (did in candidates) {
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

        // ---- TPMS @ 7A0 ----
        t.sendCommand(ObdPids.HDR_TPMS, timeoutMs = 2000)
        result = result.copy(tpms = t.readPid(ObdPids.TPMS)?.let(ObdParser::parseTpms))

        // ---- VCU @ 7E2 ----
        t.sendCommand(ObdPids.HDR_VCU, timeoutMs = 2000)
        result = result.copy(vcu = t.readPid(IoniqPids.Vcu.DRIVE_STATUS)?.let { VehicleParsers.parseVcu(it) })

        // ---- Motor @ 7E3 ----
        t.sendCommand(ObdPids.HDR_MOTOR, timeoutMs = 2000)
        result = result.copy(motor = t.readPid(IoniqPids.Motor.MOTOR_DATA)?.let { VehicleParsers.parseMotor(it) })

        // ---- HVAC @ 7B3 ----
        t.sendCommand(ObdPids.HDR_HVAC, timeoutMs = 2000)
        result = result.copy(hvac = t.readPid(IoniqPids.Hvac.CLIMATE)?.let { VehicleParsers.parseHvac(it) })

        // ---- Cluster @ 7C6 ----
        t.sendCommand(ObdPids.HDR_CLUSTER, timeoutMs = 2000)
        result = result.copy(
            cluster = t.readPid(IoniqPids.Cluster.MAIN)?.let { VehicleParsers.parseCluster(it) },
            clusterTrips = t.readPid(IoniqPids.Cluster.TRIPS)?.let { VehicleParsers.parseClusterTrips(it) }
        )

        // ---- Body @ 770 (needs extended session) ----
        try {
            t.sendCommand("ATSH770", timeoutMs = 2000)
            t.sendCommand("1003", timeoutMs = 2000)  // diagnostic session
            delay(300)
            result = result.copy(doors = t.readPid(IoniqPids.Body.DOORS)?.let { VehicleParsers.parseDoors(it) })
        } catch (e: Exception) { Timber.d("Body ECU read failed: ${e.message}") }

        // ---- ESC @ 730 ----
        try {
            t.sendCommand(ObdPids.HDR_ESC, timeoutMs = 2000)
            result = result.copy(esc = t.readPid(IoniqPids.Esc.DYNAMICS)?.let { VehicleParsers.parseEsc(it) })
        } catch (e: Exception) { Timber.d("ESC read failed: ${e.message}") }

        // ---- CCS @ 744: charge session (only while plugged in) ----
        if (charging) {
            t.sendCommand(ObdPids.HDR_CCS, timeoutMs = 2000)
            result = result.copy(
                session = t.readPid(ObdPids.CHARGE_CONTROLLER)?.let(ObdParser::parseChargeController),
                evse = t.readPid(ObdPids.EVSE_INFO)?.let(ObdParser::parseEvseinfo),
                targets = t.readPid(ObdPids.CHARGE_TARGETS)?.let(ObdParser::parseChargeTargets)
            )
        }

        // ---- Capacity calibrator ----
        val soc = bmsMain?.socBms ?: bmsStatus?.displayedSoc
        val remaining = bmsStatus?.remainingEnergyKwh
        val soh = bmsStatus?.soh
        if (soc != null && remaining != null && vin != null) {
            val cap = CapacityCalibrator.observe(prefs, vin!!, soc, remaining, soh)
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
```

#### 5. Replace existing `readUdsBlock` call with `readExtendedBlock`:
In the polling loop, change:
```kotlin
val uds = readUdsBlock(t, charging)
```
to:
```kotlin
val uds = readExtendedBlock(t, charging)
```

#### 6. Wire new fields into VehicleTelemetry constructor:
In the `VehicleTelemetry(...)` construction inside the polling loop, add:
```kotlin
// BMS Main
bmsSoc = udsRead.bmsMain?.socBms,
bmsCurrent = udsRead.bmsMain?.currentA,
bmsVoltage = udsRead.bmsMain?.voltageV,
// BMS Status
soh = udsRead.bmsStatus?.soh,
maxChargePowerKw = udsRead.bmsStatus?.maxChargePowerKw,
maxDischargePowerKw = udsRead.bmsStatus?.maxDischargePowerKw,
remainingEnergyKwh = udsRead.bmsStatus?.remainingEnergyKwh,
heaterStatus = udsRead.bmsStatus?.heaterStatus,
heaterPowerKw = udsRead.bmsStatus?.heaterPowerKw,
thermalMode = udsRead.bmsStatus?.let { null },  // thermal mode comes from 220106, not 0105
// VCU
vcuSpeed = udsRead.vcu?.speedKmh,
vcuGear = udsRead.vcu?.gear,
vcuReady = udsRead.vcu?.ready,
vcuTorqueOutput = udsRead.vcu?.torqueOutputNm,
vcuMotorRpm = udsRead.vcu?.motorRpm,
// Motor
motorStatorTemp = udsRead.motor?.statorTempC,
motorInverterTemp = udsRead.motor?.inverterTempC,
motorDcLinkVoltage = udsRead.motor?.dcLinkVoltageV,
motorDcLinkCurrent = udsRead.motor?.dcLinkCurrentA,
// HVAC
hvacCabinTemp = udsRead.hvac?.cabinTempC,
hvacOutsideTemp = udsRead.hvac?.outsideTempC,
hvacCompressorSpeed = udsRead.hvac?.compressorRpm,
hvacCoolantIn = udsRead.hvac?.coolantInTempC,
hvacCoolantOut = udsRead.hvac?.coolantOutTempC,
// Cluster
clusterDisplaySpeed = udsRead.cluster?.displaySpeedKmh,
clusterRange = udsRead.cluster?.rangeKm,
tripADistance = udsRead.clusterTrips?.tripADistanceKm,
tripBDistance = udsRead.clusterTrips?.tripBDistanceKm,
// Body
doorDriverOpen = udsRead.doors?.driverOpen,
doorPassengerOpen = udsRead.doors?.passengerOpen,
doorRearLeftOpen = udsRead.doors?.rearLeftOpen,
doorRearRightOpen = udsRead.doors?.rearRightOpen,
hoodOpen = udsRead.doors?.hoodOpen,
tailgateOpen = udsRead.doors?.tailgateOpen,
// Capacity
usableCapacityKwh = udsRead.usableCapacityKwh,
```

#### 7. VIN detection:
After the first successful BMS read, try to extract VIN:
```kotlin
// In the polling loop, after reading BMS main:
if (vin == null) {
    // Try to get VIN from VCU DID F190
    t.sendCommand(ObdPids.HDR_VCU, timeoutMs = 2000)
    val vinRaw = t.readPid("22F190")
    if (vinRaw != null) {
        val decoded = VinDecoder.decode(ObdParser.extractVin(vinRaw))
        if (decoded != null && decoded.valid) {
            vin = decoded.vin
            Timber.i("VIN detected: $vin")
        }
    }
}
```

Wait — we need a VIN parser. Add to ObdParser:
```kotlin
fun extractVin(raw: String?): String? {
    if (isBad(raw)) return null
    val hex = cleanResponse(raw!!).replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
    val bytes = toIntList(hex)
    return bytes.map { it.toChar() }.joinToString("").filter { it in 'A'..'Z' || it in '0'..'9' }
}
```

Actually, the GJSoft app has `vin_decode()` which converts hex pairs to ASCII. Let me just add that to ObdParser.

#### 8. Delete old `readUdsBlock` method (replaced by `readExtendedBlock`).

## Output format
Write all changes, run `./gradlew assembleDebug 2>&1 | tail -30`, report:
1. Files changed
2. Build result
3. Any concerns
