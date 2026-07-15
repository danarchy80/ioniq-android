package com.ioniq

import android.app.Application
import androidx.work.Configuration
import com.ioniq.diag.LogBuffer
import timber.log.Timber

/**
 * Ioniq Application
 *
 * Initializes:
 * - Timber logging (includes ring-buffer capture for support emails)
 * - Custom WorkManager configuration
 */
class IoniqApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // Always capture logs to the ring buffer (works in debug + release)
        Timber.plant(LogBuffer.TimberTree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Ioniq application started")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN
            )
            .build()
}
