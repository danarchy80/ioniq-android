package com.ioniq.ui

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ioniq.ble.ObdTransport
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.data.repository.VehicleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VehicleViewModel(
    private val repo: VehicleRepository
) : ViewModel() {

    val scanResults: StateFlow<List<BluetoothDevice>> = repo.scanResults
    val scanError: StateFlow<String?> = repo.scanError
    val connectionState: StateFlow<ObdTransport.ConnectionState> = repo.connectionState
    val isReconnecting: StateFlow<Boolean> = repo.isReconnecting
    val reconnectAttempts: StateFlow<Int> = repo.reconnectAttempts
    val vehicleState: StateFlow<VehicleTelemetry?> = repo.vehicleState
    val pairedClassicDevices: StateFlow<List<BluetoothDevice>> = repo.pairedClassicDevices

    fun startScan() = repo.startScan()
    fun stopScan() = repo.stopScan()
    fun connect(device: BluetoothDevice) = repo.connect(device)
    fun disconnect() = repo.disconnect()
    fun refreshPairedDevices() = repo.refreshPairedDevices()
    fun pairClassic(device: BluetoothDevice): Boolean = repo.pair(device)
    fun clearScanError() = repo.let { /* BleScanner handles this internally */ }

    override fun onCleared() {
        super.onCleared()
        repo.destroy()
    }
}

class VehicleViewModelFactory(
    private val repo: VehicleRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VehicleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VehicleViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
