package com.ioniq.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ioniq.data.model.CellReading
import com.ioniq.data.model.VehicleTelemetry

@Database(
    entities = [VehicleTelemetry::class, CellReading::class],
    version = 6,
    exportSchema = false
)
abstract class IoniqDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun cellReadingDao(): CellReadingDao

    companion object {
        @Volatile private var INSTANCE: IoniqDatabase? = null

        /** v5 → v6: additive columns for the expanded IONIQ DID map. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val adds = listOf(
                    "bmsSoc REAL", "bmsCurrent REAL", "bmsVoltage REAL",
                    "maxChargePowerKw REAL", "maxDischargePowerKw REAL",
                    "remainingEnergyKwh REAL", "heaterStatus INTEGER", "heaterPowerKw REAL",
                    "thermalMode INTEGER",
                    "vcuSpeed REAL", "vcuGear INTEGER", "vcuReady INTEGER",
                    "vcuTorqueOutput REAL", "vcuMotorRpm INTEGER",
                    "motorStatorTemp REAL", "motorInverterTemp REAL",
                    "motorDcLinkVoltage REAL", "motorDcLinkCurrent REAL",
                    "hvacCabinTemp REAL", "hvacOutsideTemp REAL", "hvacCompressorSpeed INTEGER",
                    "hvacCoolantIn REAL", "hvacCoolantOut REAL",
                    "clusterDisplaySpeed REAL", "clusterRange INTEGER",
                    "tripADistance REAL", "tripBDistance REAL",
                    "doorDriverOpen INTEGER", "doorPassengerOpen INTEGER",
                    "doorRearLeftOpen INTEGER", "doorRearRightOpen INTEGER",
                    "hoodOpen INTEGER", "tailgateOpen INTEGER",
                    "usableCapacityKwh REAL"
                )
                adds.forEach { db.execSQL("ALTER TABLE telemetry ADD COLUMN $it") }
            }
        }

        fun getDatabase(context: Context): IoniqDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IoniqDatabase::class.java,
                    "ioniq.db"
                ).fallbackToDestructiveMigration()
                 .addMigrations(MIGRATION_5_6)
                 .build()
                 .also { INSTANCE = it }
            }
    }
}
