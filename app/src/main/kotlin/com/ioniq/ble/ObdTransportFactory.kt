package com.ioniq.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import timber.log.Timber

/**
 * Picks the right OBD transport for a given BluetoothDevice.
 *
 * Strategy:
 *   - If device.type is DEVICE_TYPE_LE → BLE GATT  (ElmBleManager)
 *   - If device.type is DEVICE_TYPE_DUAL and advertises the OBD BLE service → BLE
 *   - Otherwise (DEVICE_TYPE_CLASSIC or DEVICE_TYPE_UNKNOWN that's already paired as classic) → RFCOMM
 *
 * Devices discovered via BLE scan are inherently BLE; devices discovered via
 * the in-app "paired classic devices" list are inherently classic.
 */
object ObdTransportFactory {

    fun create(context: Context, device: BluetoothDevice, hint: TransportHint = TransportHint.AUTO): ObdTransport {
        return when (hint) {
            TransportHint.BLE -> {
                Timber.i("Using BLE transport for ${device.address} (${device.name})")
                ElmBleManager(context)
            }
            TransportHint.CLASSIC -> {
                Timber.i("Using classic/RFCOMM transport for ${device.address} (${device.name})")
                ElmClassicManager(context)
            }
            TransportHint.AUTO -> autoSelect(context, device)
        }
    }

    private fun autoSelect(context: Context, device: BluetoothDevice): ObdTransport {
        // BluetoothDevice.getType() requires BLUETOOTH_CONNECT permission.
        return try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> {
                    Timber.i("AUTO → BLE (LE-only) ${device.address}")
                    ElmBleManager(context)
                }
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                    Timber.i("AUTO → Classic RFCOMM ${device.address}")
                    ElmClassicManager(context)
                }
                BluetoothDevice.DEVICE_TYPE_DUAL -> {
                    // Dual-stack: prefer classic for ELM327 adapters because BLE mode
                    // on dual devices is often flaky. Fall back to BLE only if the
                    // device UUIDs don't include the SPP service.
                    Timber.i("AUTO → Classic (dual-stack) ${device.address}")
                    ElmClassicManager(context)
                }
                else -> {
                    // Unknown — try classic; it's more reliable for ELM327 clones
                    Timber.i("AUTO → Classic (unknown type=${device.type}) ${device.address}")
                    ElmClassicManager(context)
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "AUTO: missing BLUETOOTH_CONNECT, defaulting to classic")
            ElmClassicManager(context)
        }
    }
}

enum class TransportHint { AUTO, BLE, CLASSIC }
