package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE Scanner — discovers nearby ELM327 OBD-II adapters.
 *
 * Uses Android's native BluetoothLeScanner for simplicity and
 * compatibility across all Android versions 8.0+.
 */
class BleScanner(private val context: Context) {

    @android.annotation.SuppressLint("MissingPermission")
    fun scan(): Flow<BluetoothDevice> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager
        val adapter = manager.adapter ?: run { close(); return@callbackFlow }
        val scanner = adapter.bluetoothLeScanner ?: run { close(); return@callbackFlow }

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name
                // Filter for ELM327 / OBD adapters by name
                if (name != null && (name.contains("ELM", ignoreCase = true) ||
                    name.contains("OBD", ignoreCase = true) ||
                    name.contains("Vgate", ignoreCase = true) ||
                    name.contains("VEEPEAK", ignoreCase = true))) {
                    trySend(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(RuntimeException("BLE scan failed with error code: $errorCode"))
            }
        }

        // Scan for all devices (filter in callback for ELM327 names)
        scanner.startScan(callback)

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            scanner.stopScan(callback)
            close()
        }, 15_000L) // 15 second scan timeout

        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }
    }
}
