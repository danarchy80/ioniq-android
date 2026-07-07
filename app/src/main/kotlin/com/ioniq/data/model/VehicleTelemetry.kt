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
    val cumulativeEnergyDischarged: Float? = null  // kWh
)

enum class ChargingState {
    NOT_CHARGING,
    CHARGING_AC,
    CHARGING_DC,
    CHARGING_COMPLETE
}
