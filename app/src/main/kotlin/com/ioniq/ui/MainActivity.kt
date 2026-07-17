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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ioniq.ble.ObdTransport
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.service.VehicleMonitorService
import com.ioniq.ui.screens.HomeScreen
import com.ioniq.ui.theme.IoniqTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: VehicleViewModel by viewModels {
        VehicleViewModelFactory(VehicleRepository.getInstance(this))
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
        Timber.i("=== MainActivity.onCreate START ===")
        Timber.i("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}")
        
        try {
            Timber.i("Setting Compose content...")
            setContent {
                Timber.i("Compose content executing")

                // Observe connection state so we can start/stop the foreground service
                val connState by viewModel.connectionState.collectAsStateWithLifecycle()
                LaunchedEffect(connState) {
                    Timber.i("Connection state changed to $connState")
                    if (connState == ObdTransport.ConnectionState.CONNECTED) {
                        // Start foreground service to keep poll running while backgrounded
                        Timber.i("Starting VehicleMonitorService (foreground)")
                        val intent = Intent(this@MainActivity, VehicleMonitorService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else if (connState == ObdTransport.ConnectionState.DISCONNECTED ||
                               connState == ObdTransport.ConnectionState.DISCONNECTING) {
                        // Stop service when disconnected
                        Timber.i("Stopping VehicleMonitorService")
                        stopService(Intent(this@MainActivity, VehicleMonitorService::class.java))
                    }
                }

                IoniqTheme {
                    Timber.i("Inside IoniqTheme")
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Timber.i("Inside Surface, about to create HomeScreen")
                        HomeScreen(viewModel, _bluetoothReady, ::requestBlePermissions)
                        Timber.i("HomeScreen created successfully")
                    }
                }
            }
            Timber.i("=== MainActivity.onCreate COMPLETE ===")
        } catch (e: Exception) {
            Timber.e(e, "CRASH during MainActivity.onCreate:")
            throw e
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.i("MainActivity.onStart")
        requestBlePermissions()
    }

    fun requestBlePermissions() {
        Timber.i("requestBlePermissions called, SDK=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires runtime BLE permissions
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            val needsRequest = perms.any {
                Timber.d("Checking permission $it: ${checkSelfPermission(it)}")
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            Timber.i("Checking if permissions needed: $needsRequest")
            if (needsRequest) {
                Timber.i("Launching permission request for: ${perms.joinToString()}")
                blePermissionLauncher.launch(perms)
            } else {
                Timber.i("All permissions already granted")
                checkBluetoothEnabled()
            }
        } else {
            // Pre-Android 12: only location needed
            Timber.i("Pre-Android 12 path")
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                blePermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                checkBluetoothEnabled()
            }
        }
    }

    private fun checkBluetoothEnabled() {
        Timber.i("checkBluetoothEnabled")
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
                Timber.i("Bluetooth is enabled and ready")
                _bluetoothReady.value = true
            }
        }
    }
}
