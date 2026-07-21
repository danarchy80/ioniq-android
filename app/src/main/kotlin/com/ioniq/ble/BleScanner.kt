package com.ioniq.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    @Volatile private var isScanning = false
    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var scanScanner: android.bluetooth.le.BluetoothLeScanner? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return

        // Guard: check permissions
        if (!hasBlePermissions()) {
            _scanError.value = "Bluetooth permissions not granted"
            Timber.e("Cannot scan: missing BLUETOOTH_SCAN permission")
            return
        }

        // Guard: check Bluetooth is enabled
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            _scanError.value = "No Bluetooth adapter available"
            Timber.e("Cannot scan: no Bluetooth adapter")
            return
        }

        if (!adapter.isEnabled) {
            _scanError.value = "Bluetooth is disabled"
            Timber.e("Cannot scan: Bluetooth is disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _scanError.value = "BLE scanner unavailable"
            Timber.e("Cannot scan: BLE scanner is null")
            return
        }

        isScanning = true
        _scanError.value = null
        _scanResults.value = emptyList()

        val seen = mutableSetOf<String>()

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name
                // Show all named BLE devices (filtering removed to support more adapters)
                if (name != null && device.address !in seen) {
                    seen.add(device.address)
                    _scanResults.value = _scanResults.value + device
                    Timber.d("Found BLE device: $name (${device.address})")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: errorCode=$errorCode")
                isScanning = false
                _scanError.value = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    else -> "Unknown error (code $errorCode)"
                }
            }
        }

        try {
            scanner.startScan(callback)
            scanCallback = callback
            scanScanner = scanner
            Timber.i("BLE scan started...")

            // Auto-stop after 15 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopScan()
            }, 15_000L)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException during scan")
            _scanError.value = "Permission denied during scan"
            isScanning = false
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during scan")
            _scanError.value = "Scan failed: ${e.message}"
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            scanScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Timber.w("Error stopping BLE scan: ${e.message}")
        }
        scanCallback = null
        scanScanner = null
        Timber.i("BLE scan stopped")
    }

    fun clearError() {
        _scanError.value = null
    }

    private fun hasBlePermissions(): Boolean {
        val required = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
