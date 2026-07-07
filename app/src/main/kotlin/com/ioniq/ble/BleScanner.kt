package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BLE Scanner — discovers nearby ELM327 OBD-II adapters.
 *
 * Exposes a StateFlow<List<BluetoothDevice>> so the UI can observe
 * discovered devices reactively.
 */
class BleScanner(private val context: Context) {

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults: StateFlow<List<BluetoothDevice>> = _scanResults.asStateFlow()

    @Volatile private var isScanning = false

    @android.annotation.SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return
        isScanning = true
        _scanResults.value = emptyList()

        val seen = mutableSetOf<String>()

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager
        val adapter = manager.adapter ?: run {
            isScanning = false
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            isScanning = false
            return
        }

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name
                // Filter for ELM327 / OBD adapters by name
                if (name != null && (name.contains("ELM", ignoreCase = true) ||
                    name.contains("OBD", ignoreCase = true) ||
                    name.contains("Vgate", ignoreCase = true) ||
                    name.contains("VEEPEAK", ignoreCase = true))) {
                    if (device.address !in seen) {
                        seen.add(device.address)
                        _scanResults.value = _scanResults.value + device
                        Timber.d("Found BLE device: $name (${device.address})")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: errorCode=$errorCode")
                isScanning = false
            }
        }

        scanner.startScan(callback)
        Timber.i("BLE scan started...")

        // Auto-stop after 15 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            isScanning = false
            Timber.i("BLE scan stopped (${seen.size} devices found)")
        }, 15_000L)
    }

    fun stopScan() {
        isScanning = false
    }
}
