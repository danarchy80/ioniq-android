package com.ioniq.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Common transport interface for OBD-II adapters.
 * Both BLE (GATT) and Classic (RFCOMM/SPP) managers implement this,
 * so VehicleRepository can poll PIDs over either transport transparently.
 */
interface ObdTransport {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    val connectionState: StateFlow<ConnectionState>
    val isReconnecting: StateFlow<Boolean>
    val reconnectCount: StateFlow<Int>

    /** Connect to the given device. Implementation chooses BLE or RFCOMM. */
    fun connectToDevice(device: android.bluetooth.BluetoothDevice)

    /** Send an ELM327 AT/OBD command, trimmed response, or null on timeout. */
    suspend fun sendCommand(command: String, timeoutMs: Long = 3000L): String?

    /** Convenience: send a PID query. */
    suspend fun readPid(pid: String): String? = sendCommand(pid)

    fun disconnect()
    fun release()
    fun enableAutoReconnect()
    fun disableAutoReconnect()
}

/** Convenience typealias — implementers can use bare `ConnectionState` without `ObdTransport.` prefix. */
typealias ConnectionState = ObdTransport.ConnectionState
