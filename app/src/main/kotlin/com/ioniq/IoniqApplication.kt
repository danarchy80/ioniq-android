package com.ioniq

import android.app.Application
import androidx.work.Configuration
import timber.log.Timber

/**
 * Ioniq Application
 *
 * Initializes:
 * - Timber logging
 * - Custom WorkManager configuration
 */
class IoniqApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
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
