package com.ioniq.service

import android.app.*
import android.bluetooth.BluetoothDevice
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
 * Foreground Service that keeps the BLE connection alive and persists
 * across app lifecycle changes. Manages the ongoing notification and
 * triggers VehicleRepository connection logic.
 *
 * Lifecycle:
 *  - Started when user connects to adapter (or on boot if previously connected)
 *  - Runs continuously while adapter is connected or auto-reconnecting
 *  - Stops self when user explicitly disconnects
 */
class VehicleMonitorService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vehicle_monitor_channel"
        private const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        fun start(context: Context, deviceAddress: String) {
            val intent = Intent(context, VehicleMonitorService::class.java).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VehicleMonitorService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: VehicleRepository

    override fun onCreate() {
        super.onCreate()
        Timber.i("VehicleMonitorService created")
        createNotificationChannel()
        repo = VehicleRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)

        // Start as foreground with initial notification
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        if (deviceAddress != null) {
            serviceScope.launch {
                try {
                    val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    if (btAdapter == null) {
                        Timber.e("No Bluetooth adapter available")
                        stopSelf()
                        return@launch
                    }

                    val device: BluetoothDevice = btAdapter.getRemoteDevice(deviceAddress)
                    Timber.i("Service connecting to device: $deviceAddress")
                    repo.connect(device)

                    // Monitor connection state and update notification
                    repo.connectionState
                        .drop(1) // skip initial
                        .distinctUntilChanged()
                        .collect { state ->
                            val msg = when (state) {
                                ObdTransport.ConnectionState.CONNECTED -> "Connected — monitoring telemetry"
                                ObdTransport.ConnectionState.CONNECTING -> "Connecting..."
                                ObdTransport.ConnectionState.DISCONNECTING -> "Disconnecting..."
                                ObdTransport.ConnectionState.DISCONNECTED -> "Disconnected — reconnecting..."
                            }
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildNotification(msg))
                        }
                } catch (e: Exception) {
                    Timber.e(e, "Service connection failed")
                    updateNotification("Error: ${e.message}")
                }
            }
        }

        // START_STICKY: let OS restart us if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("VehicleMonitorService destroyed")
        repo.destroy()
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
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ioniq EV Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
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
