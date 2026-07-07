package com.ioniq.data.db

import androidx.room.*
import com.ioniq.data.model.CellReading
import com.ioniq.data.model.VehicleTelemetry
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insert(telemetry: VehicleTelemetry): Long

    @Insert
    suspend fun insertCellReadings(readings: List<CellReading>)

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTelemetry(): VehicleTelemetry?

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestTelemetry(): Flow<VehicleTelemetry?>

    @Query("SELECT * FROM telemetry WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getTelemetryHistory(since: Long): Flow<List<VehicleTelemetry>>

    @Query("DELETE FROM telemetry WHERE timestamp < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)
}

@Dao
interface CellReadingDao {

    @Insert
    suspend fun insert(reading: CellReading)

    @Insert
    suspend fun insertAll(readings: List<CellReading>)

    @Query("SELECT * FROM cell_readings WHERE telemetryId = :id ORDER BY cellIndex ASC")
    suspend fun getForTelemetry(id: Long): List<CellReading>
}
