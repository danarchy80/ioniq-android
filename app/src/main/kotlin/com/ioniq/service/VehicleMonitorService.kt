package com.ioniq.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ioniq.R
import com.ioniq.ble.ObdTransport
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/**
 * Foreground Service that keeps the telemetry pipeline alive when the app is
 * backgrounded. It does NOT own the connection itself — VehicleRepository
 * (singleton) does. This service simply:
 *
 *   1. Promotes the process to "foreground" so Android won't kill it.
 *   2. Updates the notification with connection status.
 *   3. Listens for reconnect/disconnect actions from the notification.
 *
 * Lifecycle:
 *  - Started by MainActivity when connectionState becomes CONNECTED
 *  - Stopped by MainActivity (or self-stops) when connection stays down
 *    with no lastDevice address
 */
class VehicleMonitorService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vehicle_monitor_channel"

        const val ACTION_DISCONNECT = "com.ioniq.action.DISCONNECT"
        const val ACTION_RECONNECT  = "com.ioniq.action.RECONNECT"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: VehicleRepository

    override fun onCreate() {
        super.onCreate()
        Timber.i("VehicleMonitorService created")
        createNotificationChannel()
        repo = VehicleRepository.getInstance(this).also { Timber.i("Using singleton repo inside service") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                Timber.i("Service received DISCONNECT action")
                serviceScope.launch {
                    try { repo.disconnect() } catch (e: Exception) { Timber.e(e, "disconnect() failed") }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                Timber.i("Service received RECONNECT action — delegating to repo")
                // The repository's own auto-reconnect loop handles this; this
                // action is a no-op placeholder for explicit user-driven retry.
            }
        }

        // Start as foreground with initial notification
        startForeground(NOTIFICATION_ID, buildNotification("Connected — monitoring"))

        // Monitor repo's connection state to drive the notification label.
        // Auto-stop ourselves if the repo is DISCONNECTED with no last device address.
        serviceScope.launch {
            var consecutiveDisconnected = 0
            repo.connectionState
                .drop(0)
                .distinctUntilChanged()
                .collect { state ->
                    val msg = when (state) {
                        ObdTransport.ConnectionState.CONNECTED    -> "Connected — monitoring telemetry"
                        ObdTransport.ConnectionState.CONNECTING   -> "Connecting..."
                        ObdTransport.ConnectionState.DISCONNECTING -> "Disconnecting..."
                        ObdTransport.ConnectionState.DISCONNECTED -> "Disconnected"
                    }
                    updateNotification(msg)

                    if (state == ObdTransport.ConnectionState.DISCONNECTED ||
                        state == ObdTransport.ConnectionState.DISCONNECTING) {
                        consecutiveDisconnected++
                        // After two consecutive disconnected samples with no last device address, give up
                        if (consecutiveDisconnected >= 2) {
                            Timber.i("Repo parked DISCONNECTED with no address, stopping service")
                            delay(1_500)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else {
                        consecutiveDisconnected = 0
                    }
                }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("VehicleMonitorService destroyed")
        // Do NOT destroy the repo — it's a singleton shared with the Activity
        serviceScope.cancel()
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vehicle Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent BLE connection to OBD-II adapter"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VehicleMonitorService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            this, 2,
            Intent(this, VehicleMonitorService::class.java).apply { action = ACTION_RECONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ioniq EV Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainIntent)
            .addAction(R.drawable.ic_notification, "Reconnect", reconnectIntent)
            .addAction(R.drawable.ic_notification, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
