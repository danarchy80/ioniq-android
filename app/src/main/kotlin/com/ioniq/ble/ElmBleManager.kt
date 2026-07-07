package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ELM327 BLE Manager — handles connection to OBD-II Bluetooth adapter.
 *
 * Uses Android's native BluetoothGatt API for maximum compatibility.
 * Nordic library APIs change frequently across versions; raw GATT is stable.
 */
class ElmBleManager(private val context: Context) {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _rawData = MutableStateFlow<ByteArray?>(null)
    val rawData: Flow<ByteArray?> = _rawData.asStateFlow()

    private var gatt: android.bluetooth.BluetoothGatt? = null

    // ELM327 service/characteristic UUIDs (standard HM-10 / AT-09 adapters)
    companion object {
        val SERVICE_UUID: java.util.UUID =
            java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: java.util.UUID =
            java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: java.util.UUID =
            java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    @android.annotation.SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun sendCommand(command: String) {
        val service = gatt?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(TX_CHAR_UUID) ?: return
        characteristic.value = (command + "\r").toByteArray()
        gatt?.writeCharacteristic(characteristic)
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: android.bluetooth.BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID) ?: return
                val char = service.getCharacteristic(RX_CHAR_UUID) ?: return
                gatt.setCharacteristicNotification(char, true)
                // Enable notification descriptor
                val descriptor = char.getDescriptor(
                    java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor?.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(
            gatt: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic
        ) {
            _rawData.value = characteristic.value
        }
    }
}
