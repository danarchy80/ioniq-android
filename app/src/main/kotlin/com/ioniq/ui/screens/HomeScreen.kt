package com.ioniq.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ui.VehicleViewModel

@Composable
fun HomeScreen(viewModel: VehicleViewModel) {
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val reconnectAttempts by viewModel.reconnectAttempts.collectAsState()
    val vehicleState by viewModel.vehicleState.collectAsState(initial = null)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Connection status header
        ConnectionStatusCard(connectionState, isReconnecting, reconnectAttempts)
        Spacer(Modifier.height(16.dp))

        when (connectionState) {
            ElmBleManager.ConnectionState.DISCONNECTED -> {
                // Show scanner
                ScannerSection(viewModel, scanResults)
            }
            ElmBleManager.ConnectionState.CONNECTED -> {
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
                    ElmBleManager.ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                    ElmBleManager.ConnectionState.CONNECTING -> Icons.Default.Sync
                    ElmBleManager.ConnectionState.DISCONNECTING -> Icons.Default.SyncDisabled
                    ElmBleManager.ConnectionState.DISCONNECTED -> Icons.Default.BluetoothDisabled
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = when {
                        isReconnecting -> "Reconnecting (attempt $attempts/5)..."
                        else -> state.name.lowercase().replaceFirstChar { it.uppercase() }
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
fun ScannerSection(viewModel: VehicleViewModel, devices: List<BluetoothDevice>) {
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

    if (devices.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Found ${devices.size} device(s):", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            items(devices) { device ->
                DeviceListItem(device) { viewModel.connect(device) }
            }
        }
    }
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("State of Charge", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${telemetry.soc?.let { "%.1f".format(it) } ?: "—"}%",
                                style = MaterialTheme.typography.displaySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Charging", style = MaterialTheme.typography.labelMedium)
                            Text(
                                when (telemetry.chargingState) {
                                    ChargingState.CHARGING_AC -> "AC ⚡"
                                    ChargingState.CHARGING_DC -> "DC ⚡⚡"
                                    ChargingState.CHARGING_COMPLETE -> "Full ✓"
                                    else -> "Not Charging"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            TelemetryGrid(telemetry)
        }
    }
}

@Composable
fun TelemetryGrid(telemetry: VehicleTelemetry) {
    Column {
        TelemetryRow("Battery Voltage", "${telemetry.batteryVoltage?.let { "%.1f".format(it) } ?: "—"} V")
        TelemetryRow("Battery Current", "${telemetry.batteryCurrent?.let { "%.1f".format(it) } ?: "—"} A")
        TelemetryRow("Battery Temp", "${telemetry.batteryTempMax?.let { "%.0f".format(it) } ?: "—"}°C")
        TelemetryRow("Inlet Temp", "${telemetry.inletTemp?.let { "%.0f".format(it) } ?: "—"}°C")
        TelemetryRow("Ambient", "${telemetry.ambientTemp?.let { "%.0f".format(it) } ?: "—"}°C")
        telemetry.chargePower?.let {
            TelemetryRow("Charge Power", "${"%.1f".format(it)} kW")
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
