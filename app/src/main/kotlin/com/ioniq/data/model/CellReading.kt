package com.ioniq.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Individual battery cell readings for detailed diagnostics.
 */
@Entity(tableName = "cell_readings")
data class CellReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val telemetryId: Long,       // FK to VehicleTelemetry.id
    val cellIndex: Int,          // 0..95 typically
    val voltage: Float,          // millivolts
    val temperature: Float? = null
)
