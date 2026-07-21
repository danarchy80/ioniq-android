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
    suspend fun sendCommand(command: String, timeoutMs: Long = 5000L): String?

    /** Convenience: send a PID query. Default 5 s timeout gives the ELM chip
     *  enough headroom when stuck in a "Searching…" cycle before we bail. */
    suspend fun readPid(pid: String): String? = sendCommand(pid)

    /**
     * Standard ELM327 AT initialization sequence shared by all transports.
     * Sends: ATZ (reset), ATE0 (echo off), ATL0 (linefeeds off),
     *        ATS0 (spaces off), ATH0 (headers off), ATSP0 (auto protocol).
     * Implementations can override if they need transport-specific init.
     */
    suspend fun initializeElm(timeoutMs: Long = 5000L) {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        for (cmd in commands) {
            val resp = sendCommand(cmd, timeoutMs = timeoutMs)
            timber.log.Timber.d("Init $cmd → ${resp ?: "TIMEOUT"}")
        }
        timber.log.Timber.i("ELM initialization complete")
    }

    fun disconnect()
    fun release()
    fun enableAutoReconnect()
    fun disableAutoReconnect()
}

/** Convenience typealias — implementers can use bare `ConnectionState` without `ObdTransport.` prefix. */
typealias ConnectionState = ObdTransport.ConnectionState
