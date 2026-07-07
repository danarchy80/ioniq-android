package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks
import timber.log.Timber

/**
 * Manages BLE connection to an ELM327 OBD-II adapter using
 * Nordic Semiconductor's BLE library (industry-standard on Android).
 *
 * Lifecycle:
 *   scan() -> connect(device) -> sendAT("ATR") -> readOBD("0105") -> disconnect()
 */
class ElmBleManager(context: Context) :
    BleManager<ElmBleManager.ElmCallbacks>(context),
    ElmBleManager.ElmCallbacks {

    interface ElmCallbacks : BleManagerCallbacks {
        fun onDataReceived(data: String)
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _obdData = MutableStateFlow<String?>(null)
    val obdData: Flow<String?> = _obdData.asStateFlow()

    private var rxCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
    private var txCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null

    override fun log(priority: Int, message: String) =
        Timber.log(priority, message)

    override fun getMinLogPriority(): Int = android.util.Log.DEBUG

    override fun onDataReceived(data: String) {
        _obdData.value = data
    }

    override fun onDeviceConnecting(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTED
        Timber.i("ELM327 ready: ${device.name ?: device.address}")
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.DISCONNECTING
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun initializeEcu() {
        // Send standard ELM327 init commands
        sendAT("ATZ")   // Reset
        sendAT("ATE0")  // Echo off
        sendAT("ATL0")  // Linefeeds off
        sendAT("ATS0")  // Spaces off
        sendAT("ATH1")  // Headers on
        sendAT("ATSP6") // Protocol 6: ISO 15765-4 CAN (11/500)
    }

    fun sendAT(command: String) {
        request {
            txCharacteristic?.let {
                writeCharacteristic(it, (command + "\r").toByteArray())
                    .enqueue()
            }
        }
    }

    fun readOBD(mode: String, pid: String) {
        // Example: mode "01" pid "05" -> coolant temp
        val cmd = mode + pid
        sendCommand(cmd)
    }

    private fun sendCommand(cmd: String) {
        request {
            txCharacteristic?.let {
                writeCharacteristic(it, (cmd + "\r").toByteArray())
                    .enqueue()
            }
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}
