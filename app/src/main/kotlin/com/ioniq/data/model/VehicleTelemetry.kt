package com.ioniq.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Vehicle telemetry snapshot — persisted to Room for offline access
 * and historical charting.
 */
@Entity(tableName = "telemetry")
data class VehicleTelemetry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val soc: Float? = null,                  // State of charge %
    val soh: Float? = null,                  // State of health %
    val batteryVoltage: Float? = null,       // Pack voltage V
    val batteryCurrent: Float? = null,       // Pack current A
    val batteryTempMin: Float? = null,       // Battery temp min °C
    val batteryTempMax: Float? = null,       // Battery temp max °C
    val inletTemp: Float? = null,            // Charge inlet temp °C
    val ambientTemp: Float? = null,          // Ambient temp °C
    val cellVoltageMin: Int? = null,         // Min cell mV
    val cellVoltageMax: Int? = null,         // Max cell mV
    val odometer: Float? = null,             // km
    val chargingState: ChargingState = ChargingState.NOT_CHARGING,
    val chargePower: Float? = null,          // kW
    val cumulativeEnergyCharged: Float? = null,   // kWh
    val cumulativeEnergyDischarged: Float? = null, // kWh

    // ---- 12V aux battery (UDS DID E011) ----
    val auxBatteryVoltage: Float? = null,    // V
    val auxBatteryCurrent: Float? = null,    // A (+ = charging)
    val auxBatterySoc: Float? = null,        // %
    val auxBatteryTemp: Float? = null,       // °C

    // ---- TPMS (UDS DID C00B) ----
    val tpmsFlPsi: Float? = null, val tpmsFrPsi: Float? = null,
    val tpmsRlPsi: Float? = null, val tpmsRrPsi: Float? = null,
    val tpmsFlTemp: Float? = null, val tpmsFrTemp: Float? = null,
    val tpmsRlTemp: Float? = null, val tpmsRrTemp: Float? = null,

    // ---- Full cell voltages (UDS DIDs 0102–010C), mV, comma-separated ----
    val cellVoltagesCsv: String? = null,

    // ---- Charging session (UDS DIDs E001–E003 @ 744) ----
    val evseMaxVoltage: Float? = null,        // V offered by EVSE
    val evseMaxCurrent: Float? = null,        // A offered by EVSE
    val evseMaxPowerKw: Float? = null,        // kW offered by EVSE
    val evseSessionEnergyKwh: Float? = null,  // kWh delivered this session
    val bmsTargetVoltage: Float? = null,      // V requested by BMS
    val bmsTargetCurrent: Float? = null,      // A requested by BMS
    val chargeTimeRemainingMin: Int? = null,  // minutes (AC or DC per state)

    // ---- Lifetime charging stats (UDS DID 0111 @ 7E4) ----
    val acChargeCount: Long? = null,
    val dcChargeCount: Long? = null,
    val acChargeEnergyKwh: Long? = null,
    val dcChargeEnergyKwh: Long? = null,

    // ---- HV isolation (UDS DID 0101 @ 7E4) ----
    val isolationResistanceKohm: Int? = null,

    // ---- BMS Main (UDS DID 0101 @ 7E4) ----
    val bmsSoc: Float? = null,                  // BMS SOC %
    val bmsCurrent: Float? = null,              // A (negative = charging)
    val bmsVoltage: Float? = null,              // V

    // ---- BMS Status (UDS DID 0105 @ 7E4) ----
    // (soh already declared above)
    val maxChargePowerKw: Float? = null,        // kW
    val maxDischargePowerKw: Float? = null,     // kW
    val remainingEnergyKwh: Float? = null,      // kWh
    val heaterStatus: Int? = null,              // raw heater status byte
    val heaterPowerKw: Float? = null,           // kW

    // ---- BMS charge stats (UDS DID 0106 @ 7E4) ----
    val thermalMode: Int? = null,               // raw thermal management mode

    // ---- VCU drive status (UDS DID 0101 @ 7E2) ----
    val vcuSpeed: Float? = null,                // km/h
    val vcuGear: Int? = null,                   // 0=P, 1=R, 2=N, 3=D
    val vcuReady: Boolean? = null,              // ignition-ready state
    val vcuTorqueOutput: Float? = null,         // Nm
    val vcuMotorRpm: Int? = null,               // rpm

    // ---- Motor / inverter (UDS DID 0101 @ 7E3) ----
    val motorStatorTemp: Float? = null,         // °C
    val motorInverterTemp: Float? = null,       // °C
    val motorDcLinkVoltage: Float? = null,      // V
    val motorDcLinkCurrent: Float? = null,      // A

    // ---- HVAC (UDS DID 0100 @ 7B3) ----
    val hvacCabinTemp: Float? = null,           // °C
    val hvacOutsideTemp: Float? = null,         // °C
    val hvacCompressorSpeed: Int? = null,       // RPM
    val hvacCoolantIn: Float? = null,           // °C
    val hvacCoolantOut: Float? = null,          // °C

    // ---- Cluster (UDS DIDs B002 / B00C @ 7C6) ----
    val clusterDisplaySpeed: Float? = null,     // km/h (displayed)
    val clusterRange: Int? = null,              // km (displayed range)
    val tripADistance: Float? = null,           // km
    val tripBDistance: Float? = null,           // km

    // ---- Body (UDS DID BC03 @ 770) ----
    val doorDriverOpen: Boolean? = null,
    val doorPassengerOpen: Boolean? = null,
    val doorRearLeftOpen: Boolean? = null,
    val doorRearRightOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    val tailgateOpen: Boolean? = null,

    // ---- Calibrated usable capacity (CapacityCalibrator) ----
    val usableCapacityKwh: Float? = null        // kWh
)

enum class ChargingState {
    NOT_CHARGING,
    CHARGING_AC,
    CHARGING_DC,
    CHARGING_COMPLETE
}
