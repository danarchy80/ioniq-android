package com.ioniq.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.data.repository.VehicleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel bridging VehicleRepository → Compose UI.
 */
class VehicleViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repo = VehicleRepository(app)

    val connectionState = repo.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ElmBleManager.ConnectionState.DISCONNECTED)

    val scannedDevices = repo.scanResults
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val telemetry = repo.vehicleState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as VehicleTelemetry?)

    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    val selectedDeviceName: Flow<String?> = _selectedDeviceName.asStateFlow()

    fun scanForAdapters() { repo.startScan() }
    fun connectToDevice(device: BluetoothDevice) {
        _selectedDeviceName.value = device.name ?: device.address
        repo.connect(device)
    }
    fun disconnect() {
        repo.destroy()
        _selectedDeviceName.value = null
    }

    override fun onCleared() {
        super.onCleared()
        repo.destroy()
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { VehicleViewModel(app) }
        }
    }
}
