package com.ioniq.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.screens.HomeScreen
import com.ioniq.ui.theme.IoniqTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: VehicleViewModel by viewModels {
        VehicleViewModelFactory(VehicleRepository(this))
    }

    // Expose permission state to Compose
    private val _bluetoothReady = MutableStateFlow(false)
    val bluetoothReady: StateFlow<Boolean> = _bluetoothReady.asStateFlow()

    // Permission launcher (Android 12+ needs BLUETOOTH_SCAN + BLUETOOTH_CONNECT)
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Timber.i("BLE permissions result: $allGranted — $results")
        if (allGranted) {
            checkBluetoothEnabled()
        } else {
            _bluetoothReady.value = false
        }
    }

    // Launcher to prompt user to enable Bluetooth
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Timber.i("Bluetooth enabled by user")
            _bluetoothReady.value = true
        } else {
            Timber.w("User declined to enable Bluetooth")
            _bluetoothReady.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IoniqTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(viewModel, _bluetoothReady, ::requestBlePermissions)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestBlePermissions()
    }

    fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires runtime BLE permissions
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            val needsRequest = perms.any {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            if (needsRequest) {
                blePermissionLauncher.launch(perms)
            } else {
                checkBluetoothEnabled()
            }
        } else {
            // Pre-Android 12: only location needed
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                blePermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                checkBluetoothEnabled()
            }
        }
    }

    private fun checkBluetoothEnabled() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> {
                Timber.e("No Bluetooth adapter on this device")
                _bluetoothReady.value = false
            }
            !adapter.isEnabled -> {
                Timber.w("Bluetooth is disabled — prompting user")
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> {
                _bluetoothReady.value = true
            }
        }
    }
}
