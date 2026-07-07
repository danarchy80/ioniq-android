package com.ioniq.ui

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.data.repository.VehicleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VehicleViewModel(
    private val repo: VehicleRepository
) : ViewModel() {

    val scanResults: StateFlow<List<BluetoothDevice>> = repo.scanResults
    val connectionState: StateFlow<ElmBleManager.ConnectionState> = repo.connectionState
    val isReconnecting: StateFlow<Boolean> = repo.isReconnecting
    val reconnectAttempts: StateFlow<Int> = repo.reconnectAttempts
    val vehicleState: StateFlow<VehicleTelemetry?> = repo.vehicleState

    fun startScan() = repo.startScan()
    fun stopScan() = repo.stopScan()
    fun connect(device: BluetoothDevice) = repo.connect(device)
    fun disconnect() = repo.disconnect()

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
