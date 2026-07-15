package com.ioniq.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * In-app classic-BT device access:
 *   - exposes bonded (paired) classic BT devices as a StateFlow
 *   - initiates pairing via createBond() — user stays inside the app
 *     (system pairing dialog appears over our UI, no redirect to Settings)
 *   - listens for ACTION_BOND_STATE_CHANGED to refresh the list live
 *
 * This lets the user pick a previously-paired OBD adapter from inside the
 * app and reconnect without ever leaving it, mirroring the iOS pairing UX.
 */
class ClassicBtDeviceProvider(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                Timber.d("Bond state changed: ${intent.getStringExtra(BluetoothDevice.EXTRA_DEVICE)}")
                refresh()
            }
        }
    }

    @Volatile private var listening = false

    init { refresh() }

    /** Re-read bonded devices from the adapter. */
    fun refresh() {
        try {
            val bonded = adapter?.bondedDevices?.toList() ?: emptyList()
            _pairedDevices.value = bonded
        } catch (e: SecurityException) {
            Timber.w(e, "Missing BLUETOOTH_CONNECT permission when reading bonded devices")
            _pairedDevices.value = emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read bonded devices")
            _pairedDevices.value = emptyList()
        }
    }

    /** Initiate bonding with an unpaired BT device. Returns true if bonding started. */
    fun pair(device: BluetoothDevice): Boolean {
        return try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                Timber.d("Device ${device.address} already bonded")
                true
            } else {
                Timber.i("Starting bond with ${device.address} (${device.name})")
                device.createBond()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Missing BLUETOOTH_CONNECT permission for pairing")
            false
        } catch (e: Exception) {
            Timber.w(e, "createBond() failed")
            false
        }
    }

    /** Start listening for live bond-state updates. Safe to call multiple times. */
    fun startListening() {
        if (listening) return
        listening = true
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            appContext.registerReceiver(bondReceiver, filter)
        } catch (e: Exception) {
            Timber.w(e, "Failed to register bond receiver")
            listening = false
        }
    }

    /** Stop listening. */
    fun stopListening() {
        if (!listening) return
        listening = false
        try {
            appContext.unregisterReceiver(bondReceiver)
        } catch (_: Exception) {}
    }

    /** Find a bonded device by address (case-insensitive). Returns null if not found. */
    fun findByAddress(address: String): BluetoothDevice? =
        _pairedDevices.value.firstOrNull { it.address.equals(address, ignoreCase = true) }
}
