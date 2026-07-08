package com.ioniq.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ui.VehicleViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(
    viewModel: VehicleViewModel,
    bluetoothReady: StateFlow<Boolean>,
    onRequestPermissions: () -> Unit
) {
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val reconnectAttempts by viewModel.reconnectAttempts.collectAsState()
    val vehicleState by viewModel.vehicleState.collectAsState(initial = null)
    val scanError by viewModel.scanError.collectAsState()
    val btReady by bluetoothReady.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Connection status header (only show when BT is ready)
        if (btReady) {
            ConnectionStatusCard(connectionState, isReconnecting, reconnectAttempts)
            Spacer(Modifier.height(16.dp))
        }

        when {
            !btReady -> {
                // Bluetooth not ready — show permission/setup UI
                BluetoothSetupCard(onRequestPermissions)
            }
            connectionState == ElmBleManager.ConnectionState.DISCONNECTED -> {
                // Show scanner
                ScannerSection(viewModel, scanResults, scanError)
            }
            connectionState == ElmBleManager.ConnectionState.CONNECTED -> {
                // Show telemetry
                TelemetryDisplay(vehicleState)
            }
            else -> {
                // Connecting/Disconnecting
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
fun BluetoothSetupCard(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Bluetooth Setup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enable Bluetooth and grant permissions to scan for OBD-II adapters",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    state: ElmBleManager.ConnectionState,
    isReconnecting: Boolean,
    attempts: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                ElmBleManager.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                ElmBleManager.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                ElmBleManager.ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
                ElmBleManager.ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (state) {
                    ElmBleManager.ConnectionState.CONNECTED -> Icons.Default.Sync
                    ElmBleManager.ConnectionState.CONNECTING -> Icons.Default.Sync
                    ElmBleManager.ConnectionState.DISCONNECTING -> Icons.Default.SyncDisabled
                    ElmBleManager.ConnectionState.DISCONNECTED -> Icons.Default.SyncDisabled
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = when {
                        isReconnecting -> "Reconnecting (attempt $attempts/5)..."
                        else -> when (state) {
                            ElmBleManager.ConnectionState.CONNECTED -> "Connected"
                            ElmBleManager.ConnectionState.CONNECTING -> "Connecting..."
                            ElmBleManager.ConnectionState.DISCONNECTING -> "Disconnecting..."
                            ElmBleManager.ConnectionState.DISCONNECTED -> "Disconnected"
                        }
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (isReconnecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun ScannerSection(
    viewModel: VehicleViewModel,
    devices: List<BluetoothDevice>,
    scanError: String?
) {
    Text("Bluetooth Scanner", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { viewModel.startScan() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Search, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Scan for OBD-II Adapters")
    }

    // Show error if present
    if (scanError != null) {
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    scanError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    if (devices.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Found ${devices.size} device(s):", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(devices) { device ->
                DeviceListItem(device) { viewModel.connect(device) }
            }
        }
    }
}

@Composable
fun LinearProgressIndicator(modifier: Modifier) {
    androidx.compose.material3.LinearProgressIndicator(modifier = modifier)
}

@Composable
fun DeviceListItem(device: BluetoothDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onConnect
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Connect")
        }
    }
}

@Composable
fun TelemetryDisplay(telemetry: VehicleTelemetry?) {
    if (telemetry == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Connecting to vehicle...")
        }
    } else {
        LazyColumn {
            item {
                // SOC and charging state
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "State of Charge",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${telemetry.soc?.toInt() ?: 0}%",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (telemetry.chargingState) {
                                    ChargingState.CHARGING_AC -> "Charging (AC)"
                                    ChargingState.CHARGING_DC -> "Charging (DC)"
                                    ChargingState.NOT_CHARGING -> "Not Charging"
                                    ChargingState.CHARGING_COMPLETE -> "Charge Complete"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            item {
                // Battery metrics
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Battery",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        TelemetryRow("Voltage", "${telemetry.batteryVoltage?.let { "%.1f V".format(it) } ?: "—"}")
                        TelemetryRow("Current", "${telemetry.batteryCurrent?.let { "%.1f A".format(it) } ?: "—"}")
                        TelemetryRow("Power", "${telemetry.chargePower?.let { "%.1f kW".format(it) } ?: "—"}")
                        TelemetryRow("Temp", "${telemetry.batteryTempMax?.let { "%.0f °C".format(it) } ?: "—"}")
                    }
                }
            }

            item {
                // Temperatures
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Temperature",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        TelemetryRow("Inlet", "${telemetry.inletTemp?.let { "%.0f °C".format(it) } ?: "—"}")
                        TelemetryRow("Ambient", "${telemetry.ambientTemp?.let { "%.0f °C".format(it) } ?: "—"}")
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
