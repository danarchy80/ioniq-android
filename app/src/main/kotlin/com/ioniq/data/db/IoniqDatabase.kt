package com.ioniq.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ioniq.data.model.CellReading
import com.ioniq.data.model.VehicleTelemetry

@Database(
    entities = [VehicleTelemetry::class, CellReading::class],
    version = 3,
    exportSchema = false
)
abstract class IoniqDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun cellReadingDao(): CellReadingDao

    companion object {
        @Volatile private var INSTANCE: IoniqDatabase? = null

        fun getDatabase(context: Context): IoniqDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IoniqDatabase::class.java,
                    "ioniq.db"
                ).fallbackToDestructiveMigration()
                 .build()
                 .also { INSTANCE = it }
            }
    }
}
