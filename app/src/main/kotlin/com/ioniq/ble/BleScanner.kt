package com.ioniq.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import timber.log.Timber

/**
 * Scans for Bluetooth Low Energy ELM327 adapters.
 *
 * Typical ELM327 adapters advertise themselves with names like:
 *   "OBDII", "VEEPEAK", "VEEPEAK_V01", "ELM327"
 */
class BleScanner(private val context: Context) {

    private val scanner = BluetoothLeScannerCompat.getScanner()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val current = _scanResults.value.toMutableList()
            val existing = current.indexOfFirst { it.device.address == result.device.address }
            if (existing >= 0) current[existing] = result else current.add(result)
            _scanResults.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    fun startScan(filterByName: String? = null) {
        val filters = if (filterByName != null) {
            listOf(ScanFilter.Builder()
                .setDeviceName(filterByName)
                .build())
        } else emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        _isScanning.value = true
        Timber.d("BLE scan started")
    }

    fun stopScan() {
        scanner.stopScan(scanCallback)
        _isScanning.value = false
        Timber.d("BLE scan stopped")
    }
}
