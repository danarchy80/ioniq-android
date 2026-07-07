package com.ioniq.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ioniq.R
import timber.log.Timber
import java.util.*

/**
 * Foreground service maintaining persistent BLE+OBD connection
 * to the Ioniq EV. Required to survive OEM battery optimization.
 *
 * Responsibilities:
 *  - Reconnect BLE on unexpected drop
 *  - Poll OBD PIDs at 1 Hz
 *  - Push telemetry to Room DB + Home Assistant WebSocket
 *  - Show persistent notification with live SOC
 */
class VehicleMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "vehicle_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val OBD_POLL_INTERVAL_MS = 1000L
    }

    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        Timber.i("VehicleMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        // STICKY: if OS kills service, restart it
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    pollVehicleData()
                }
            }, 0, OBD_POLL_INTERVAL_MS)
        }
    }

    private fun pollVehicleData() {
        // TODO: wire to ElmBleManager.readOBD() once connected
        // For now: stub
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vehicle Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for vehicle connection"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ioniq Connected")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
        Timber.i("VehicleMonitorService destroyed")
    }
}
