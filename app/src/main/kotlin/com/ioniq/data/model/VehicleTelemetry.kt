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
    val isolationResistanceKohm: Int? = null
)

enum class ChargingState {
    NOT_CHARGING,
    CHARGING_AC,
    CHARGING_DC,
    CHARGING_COMPLETE
}
