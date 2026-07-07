package com.ioniq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ble.ElmBleManager
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ui.VehicleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: VehicleViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val deviceName by viewModel.selectedDeviceName.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ioniq EV", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    ElmBleManager.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                    ElmBleManager.ConnectionState.CONNECTING -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                }
                            )
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionCard(state = connectionState, deviceName = deviceName, scannedCount = scannedDevices.size, onScan = { viewModel.scanForAdapters() }, onConnect = { scannedDevices.firstOrNull()?.let { viewModel.connectToDevice(it) } }, onDisconnect = { viewModel.disconnect() })
            BatteryCard(telemetry = telemetry)
            ChargingCard(telemetry = telemetry)
            CellHealthCard(telemetry = telemetry)
        }
    }
}

@Composable
private fun ConnectionCard(state: ElmBleManager.ConnectionState, deviceName: String?, scannedCount: Int, onScan: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (state) {
                        ElmBleManager.ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                        ElmBleManager.ConnectionState.CONNECTING -> Icons.Default.BluetoothSearching
                        else -> Icons.Default.BluetoothDisabled
                    },
                    contentDescription = null,
                    tint = when (state) {
                        ElmBleManager.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                        ElmBleManager.ConnectionState.CONNECTING -> Color(0xFFFFC107)
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when (state) {
                            ElmBleManager.ConnectionState.CONNECTED -> "Connected"
                            ElmBleManager.ConnectionState.CONNECTING -> "Connecting..."
                            ElmBleManager.ConnectionState.DISCONNECTING -> "Disconnecting..."
                            else -> "Not Connected"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(deviceName ?: if (scannedCount > 0) "$scannedCount device(s) found" else "Scan to connect ELM327 adapter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            when (state) {
                ElmBleManager.ConnectionState.CONNECTED -> {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
                ElmBleManager.ConnectionState.CONNECTING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan")
                        }
                        Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = scannedCount > 0) {
                            Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(telemetry: VehicleTelemetry?) {
    val soc = telemetry?.soc
    val progressColor = when {
        soc != null && soc > 60f -> Color(0xFF4CAF50)
        soc != null && soc > 20f -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryFull, contentDescription = null, tint = progressColor, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(soc?.let { "${it.toInt()}%" } ?: "--%", fontSize = 46.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displayLarge)
                    Text(telemetry?.batteryVoltage?.let { "${it}V" } ?: "--V", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (soc != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = soc / 100f, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = progressColor)
            }
        }
    }
}

@Composable
private fun ChargingCard(telemetry: VehicleTelemetry?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isCharging = telemetry?.chargingState != null && telemetry.chargingState != ChargingState.NOT_CHARGING
                Icon(if (isCharging) Icons.Default.EvStation else Icons.Default.Speed, contentDescription = null, tint = if (isCharging) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (isCharging) "Charging" else "Not Charging", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(telemetry?.chargePower?.let { "${it}kW" } ?: "--", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CellHealthCard(telemetry: VehicleTelemetry?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Cell Health", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    val minV = telemetry?.cellVoltageMin
                    val maxV = telemetry?.cellVoltageMax
                    Text(if (minV != null && maxV != null) "${minV}mV – ${maxV}mV (Δ${maxV - minV}mV)" else "Min/Max: --mV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
