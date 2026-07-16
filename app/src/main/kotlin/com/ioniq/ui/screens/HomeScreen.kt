package com.ioniq.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ble.ObdTransport
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.ui.VehicleViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── Chip color tokens ───────────────────────────

private val ChipBg = Color(0xFF162235)
private val ChipLabel = Color(0xFF90A4AE)
private val ChipValue = Color(0xFFE0E0E0)
private val ChipGood = Color(0xFF4CAF50)
private val ChipWarn = Color(0xFFFFA726)
private val ChipBad = Color(0xFFEF5350)
private val ChipAccent = Color(0xFF00B4E6)
private val ChipNeutral = Color(0xFF78909C)

// ─────────────────────────── HomeScreen ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VehicleViewModel,
    bluetoothReady: StateFlow<Boolean>,
    onRequestPermissions: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val reconnectAttempts by viewModel.reconnectAttempts.collectAsState()
    val vehicleState by viewModel.vehicleState.collectAsState(initial = null)
    val scanError by viewModel.scanError.collectAsState()
    val btReady by bluetoothReady.collectAsState()
    val context = LocalContext.current
    val telemetry = vehicleState  // vehicleState IS the telemetry data class

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Ioniq Telemetry") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->

    // ── Box ensures overlay renders ON TOP of all content ──
    Box(modifier = Modifier.padding(padding)) {

        LazyColumn(
            modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Connection status header ──
        if (btReady) {
            item {
                ConnectionStatusCard(connectionState, isReconnecting, reconnectAttempts)
            }
        }

        when {
            !btReady -> {
                item { BluetoothSetupCard(onRequestPermissions, context) }
                item { GettingStartedCard() }
            }
            connectionState == ObdTransport.ConnectionState.DISCONNECTED -> {
                item { ScannerSection(viewModel, scanResults, scanError) }
                item { PairedDevicesSection(viewModel) }
                item { GettingStartedCard() }
            }
            connectionState == ObdTransport.ConnectionState.CONNECTED -> {
                item { TelemetryDashboard(vehicleState) }
            }
            else -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Connecting to adapter…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    // ── Settings Overlay (rendered last in Box → on top of everything) ──
    if (showSettings) {
        SettingsOverlay(
            onDismiss = { showSettings = false }
        )
    }

    }  // Box
    }  // Scaffold
}

// ─────────────────────────── Settings Overlay ───────────────────────────

@Composable
fun SettingsOverlay(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logLines = remember { com.ioniq.diag.LogBuffer.drain() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // ── Device summary card ──
            Surface(
                color = Color(0xFF1A2738),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    StatRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    StatRow("App", "${com.ioniq.BuildConfig.VERSION_NAME} (${com.ioniq.BuildConfig.VERSION_CODE})")
                    @Suppress("DEPRECATION")
                    val btEnabled = try {
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
                    } catch (_: Exception) { false }
                    StatRow("Bluetooth", if (btEnabled) "On" else "Off")
                    StatRow("Log lines", "${logLines.size}")
                }
            }

            // ── Recent log scrollable panel ──
            Surface(
                color = Color(0xFF111820),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (logLines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No log entries yet.", color = ChipLabel)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logLines.reversed()) { line ->
                            Text(
                                line,
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── Action buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            val activity = context as? android.app.Activity
                            activity?.let {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                // Open BT settings
                                @Suppress("DEPRECATION")
                                it.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("BT Settings", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        com.ioniq.diag.SupportEmailSender.launch(context)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send Email", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ChipLabel, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = ChipValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun fmtPct(v: Float?) = v?.let { String.format("%.1f%%", it) } ?: "—"
private fun fmtVolt(v: Float?) = v?.let { String.format("%.1f V", it) } ?: "—"
private fun fmtAmp(v: Float?) = v?.let { String.format("%.1f A", it) } ?: "—"
private fun fmtKw(v: Float?) = v?.let { String.format("%.2f kW", it) } ?: "—"
private fun fmtTemp(v: Float?) = v?.let { String.format("%.1f °C", it) } ?: "—"
private fun fmtMv(v: Int?) = v?.let { String.format("%d mV", it) } ?: "—"
private fun fmtKm(v: Float?) = v?.let { String.format("%.0f km", it) } ?: "—"
private fun fmtKwh(v: Float?) = v?.let { String.format("%.2f kWh", it) } ?: "—"

// ─────────────────────────── Support Email Button ───────────────────────────

@Composable
fun SupportEmailButton(telemetry: VehicleTelemetry?) {
    val context = LocalContext.current

    OutlinedButton(
        onClick = {
            com.ioniq.diag.SupportEmailSender.launch(context, telemetry)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Send Support Email")
    }
}

// ─────────────────────────── BluetoothSetupCard ───────────────────────────

@Composable
fun BluetoothSetupCard(onRequestPermissions: () -> Unit, context: android.content.Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
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
                "Bluetooth Setup Required",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enable Bluetooth and grant permissions to scan for OBD-II adapters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Grant Permissions")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Bluetooth Settings")
            }
        }
    }
}

// ─────────────────────────── PairedDevicesSection ───────────────────────────

@androidx.compose.runtime.Composable
fun PairedDevicesSection(viewModel: VehicleViewModel) {
    val paired by viewModel.pairedClassicDevices.collectAsState()
    val context = LocalContext.current

    // (Re-)load bond list whenever this section is visible.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshPairedDevices() }

    Column {
        Text(
            "My Paired OBD Adapters",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ChipValue
        )
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { viewModel.refreshPairedDevices() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh paired list")
        }

        if (paired.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ChipBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = ChipAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "No paired adapters yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ChipValue
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pair your OBD-II adapter via your phone's Bluetooth settings first, then it will appear here for in-app connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChipLabel
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Bluetooth Settings")
                    }
                }
            }
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "${paired.size} paired device${if (paired.size != 1) "s" else ""}:",
            style = MaterialTheme.typography.bodySmall,
            color = ChipLabel
        )
        Spacer(Modifier.height(8.dp))
        paired.forEach { device ->
            PairedDeviceListItem(device) { viewModel.connect(device) }
        }
    }
}

@Composable
fun PairedDeviceListItem(device: BluetoothDevice, onConnect: () -> Unit) {
    val name = device.name.takeUnless { it.isNullOrBlank() } ?: "Unknown device"
    val bondState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
        device.bondState
    } else BluetoothDevice.BOND_NONE
    val isBonded = bondState == BluetoothDevice.BOND_BONDED

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onConnect,
        colors = CardDefaults.cardColors(containerColor = ChipBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isBonded) Icons.Default.CheckCircle else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (isBonded) ChipGood else ChipAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChipValue,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChipLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Text("Connect", color = ChipAccent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Connect",
                tint = ChipAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────── GettingStartedCard ───────────────────────────

@Composable
fun GettingStartedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChipBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = ChipAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Getting Started",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChipValue
                )
            }
            Spacer(Modifier.height(16.dp))

            OnboardingStep(
                number = 1,
                title = "Plug in your OBD-II adapter",
                body = "Insert the BLE OBD-II dongle into the port under your dashboard (usually on the driver's side, below the steering wheel)."
            )
            OnboardingStep(
                number = 2,
                title = "Pair via Bluetooth",
                body = "Go to Bluetooth Settings → Pair new device. Look for a name containing \"OBD\", \"ELM\", \"Vgate\", or \"VEEPEAK\". Default PIN is usually 1234 or 0000."
            )
            OnboardingStep(
                number = 3,
                title = "Grant app permissions",
                body = "Allow Bluetooth and Location permissions when prompted. These are needed to scan for and communicate with the adapter."
            )
            OnboardingStep(
                number = 4,
                title = "Tap \"Scan\" and connect",
                body = "Return to the app, press Scan for OBD-II Adapters, and tap on your adapter when it appears. The app will begin polling vehicle data automatically."
            )
            OnboardingStep(
                number = 5,
                title = "View live telemetry",
                body = "Once connected, the dashboard shows your State of Charge, battery voltage, temperature, charging power, and cell voltages in real time."
            )
        }
    }
}

@Composable
fun OnboardingStep(number: Int, title: String, body: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ChipAccent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = ChipValue
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = ChipLabel
            )
        }
    }
}

// ─────────────────────────── ConnectionStatusCard ───────────────────────────

@Composable
fun ConnectionStatusCard(
    state: ObdTransport.ConnectionState,
    isReconnecting: Boolean,
    attempts: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                ObdTransport.ConnectionState.CONNECTED -> ChipBg
                ObdTransport.ConnectionState.CONNECTING -> Color(0xFF1E2A3A)
                ObdTransport.ConnectionState.DISCONNECTING -> Color(0xFF2A1E1E)
                ObdTransport.ConnectionState.DISCONNECTED -> Color(0xFF2A1B1B)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon: ImageVector, tint: Color) = when (state) {
                ObdTransport.ConnectionState.CONNECTED -> Icons.Default.Link to ChipGood
                ObdTransport.ConnectionState.CONNECTING -> Icons.Default.Sync to ChipWarn
                ObdTransport.ConnectionState.DISCONNECTING -> Icons.Default.SyncDisabled to ChipWarn
                ObdTransport.ConnectionState.DISCONNECTED -> Icons.Default.LinkOff to ChipBad
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = tint
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isReconnecting -> "Reconnecting (attempt $attempts/5)…"
                        else -> when (state) {
                            ObdTransport.ConnectionState.CONNECTED -> "Connected"
                            ObdTransport.ConnectionState.CONNECTING -> "Connecting…"
                            ObdTransport.ConnectionState.DISCONNECTING -> "Disconnecting…"
                            ObdTransport.ConnectionState.DISCONNECTED -> "Disconnected"
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ChipValue
                )
                if (isReconnecting) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ChipWarn,
                        trackColor = ChipBg
                    )
                }
            }
        }
    }
}

// ─────────────────────────── ScannerSection ───────────────────────────

@Composable
fun ScannerSection(
    viewModel: VehicleViewModel,
    devices: List<BluetoothDevice>,
    scanError: String?
) {
    Column {
        Text(
            "Bluetooth Scanner",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ChipValue
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { viewModel.startScan() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan for OBD-II Adapters")
        }

        // Error banner
        if (scanError != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E1C1C)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = ChipBad)
                    Spacer(Modifier.width(8.dp))
                    Text(scanError, style = MaterialTheme.typography.bodySmall, color = ChipBad)
                }
            }
        }

        // Device list
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Found ${devices.size} device${if (devices.size != 1) "s" else ""}:",
                style = MaterialTheme.typography.bodySmall,
                color = ChipLabel
            )
            Spacer(Modifier.height(8.dp))
            devices.forEach { device ->
                DeviceListItem(device) { viewModel.connect(device) }
            }
        }
    }
}

@Composable
fun DeviceListItem(device: BluetoothDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onConnect,
        colors = CardDefaults.cardColors(containerColor = ChipBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = ChipAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChipValue,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChipLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Connect",
                tint = ChipAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────── TelemetryDashboard ───────────────────────────
// Chip-style widgets inspired by the IONIQ 5 Companion (theburl.com)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryDashboard(telemetry: VehicleTelemetry?) {
    if (telemetry == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = ChipAccent)
                Spacer(Modifier.height(12.dp))
                Text("Waiting for first reading…", style = MaterialTheme.typography.bodyMedium, color = ChipLabel)
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
    ) {
        // ── SOC hero card ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ChipBg),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "State of Charge",
                        style = MaterialTheme.typography.labelMedium,
                        color = ChipLabel
                    )
                    Text(
                        "${telemetry.soc?.toInt() ?: 0}%",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = socColor(telemetry.soc)
                    )
                    Spacer(Modifier.height(8.dp))
                    // Charging state chip
                    val (chipLabel, chipColor, chipIcon) = chargingChip(telemetry.chargingState)
                    AssistChip(
                        onClick = { },
                        label = { Text(chipLabel, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(chipIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = chipColor.copy(alpha = 0.15f),
                            labelColor = chipColor,
                            leadingIconContentColor = chipColor
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = chipColor.copy(alpha = 0.3f)
                        )
                    )
                    // Timestamp
                    Spacer(Modifier.height(8.dp))
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    Text(
                        "Updated ${timeFormat.format(Date(telemetry.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChipLabel.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ── Chip grid: battery metrics ──
        item {
            SectionLabel("Battery")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryChip(
                    icon = Icons.Default.Bolt,
                    label = "Voltage",
                    value = telemetry.batteryVoltage?.let { "%.1f V".format(it) } ?: "—",
                    tint = ChipAccent
                )
                TelemetryChip(
                    icon = Icons.Default.Speed,
                    label = "Current",
                    value = telemetry.batteryCurrent?.let { "%.1f A".format(it) } ?: "—",
                    tint = ChipAccent
                )
                TelemetryChip(
                    icon = Icons.Default.Power,
                    label = "Power",
                    value = telemetry.chargePower?.let { "%.1f kW".format(it) } ?: "—",
                    tint = if ((telemetry.chargePower ?: 0f) > 0f) ChipGood else ChipNeutral
                )
            }
        }

        // ── Chip grid: temperatures ──
        item {
            SectionLabel("Temperature")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryChip(
                    icon = Icons.Default.Thermostat,
                    label = "Battery",
                    value = telemetry.batteryTempMax?.let { "%.0f °C".format(it) } ?: "—",
                    tint = tempColor(telemetry.batteryTempMax)
                )
                TelemetryChip(
                    icon = Icons.Default.Thermostat,
                    label = "Inlet",
                    value = telemetry.inletTemp?.let { "%.0f °C".format(it) } ?: "—",
                    tint = tempColor(telemetry.inletTemp)
                )
                TelemetryChip(
                    icon = Icons.Default.Thermostat,
                    label = "Ambient",
                    value = telemetry.ambientTemp?.let { "%.0f °C".format(it) } ?: "—",
                    tint = ChipNeutral
                )
            }
        }

        // ── Chip row: cell voltages ──
        item {
            SectionLabel("Cell Voltages")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryChip(
                    icon = Icons.Default.Numbers,
                    label = "Min",
                    value = telemetry.cellVoltageMin?.let { "${it} mV" } ?: "—",
                    tint = cellColor(telemetry.cellVoltageMin)
                )
                TelemetryChip(
                    icon = Icons.Default.Numbers,
                    label = "Max",
                    value = telemetry.cellVoltageMax?.let { "${it} mV" } ?: "—",
                    tint = cellColor(telemetry.cellVoltageMax)
                )
                // Cell delta chip
                val min = telemetry.cellVoltageMin
                val max = telemetry.cellVoltageMax
                if (min != null && max != null) {
                    val delta = max - min
                    TelemetryChip(
                        icon = Icons.Default.Warning,
                        label = "Delta",
                        value = "$delta mV",
                        tint = if (delta < 50) ChipGood else if (delta < 100) ChipWarn else ChipBad
                    )
                }
            }
        }

        // ── Chip row: energy totals ──
        item {
            SectionLabel("Cumulative Energy")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryChip(
                    icon = Icons.Default.BatteryChargingFull,
                    label = "Charged",
                    value = telemetry.cumulativeEnergyCharged?.let { "%.1f kWh".format(it) } ?: "—",
                    tint = ChipGood
                )
                TelemetryChip(
                    icon = Icons.Default.ElectricBolt,
                    label = "Discharged",
                    value = telemetry.cumulativeEnergyDischarged?.let { "%.1f kWh".format(it) } ?: "—",
                    tint = ChipWarn
                )
            }
        }
    }
}

// ─────────────────────────── Chip Widget ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryChip(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ChipBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChipLabel,
                    fontSize = 10.sp
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChipValue
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = ChipLabel,
        letterSpacing = 1.2.sp
    )
}

// ─────────────────────────── Color helpers ───────────────────────────

private fun socColor(soc: Float?): Color = when {
    soc == null -> ChipNeutral
    soc >= 60f -> ChipGood
    soc >= 30f -> ChipWarn
    else -> ChipBad
}

private fun tempColor(temp: Float?): Color = when {
    temp == null -> ChipNeutral
    temp > 45f -> ChipBad
    temp > 35f -> ChipWarn
    temp < 0f -> ChipAccent
    else -> ChipGood
}

private fun cellColor(mv: Int?): Color = when {
    mv == null -> ChipNeutral
    mv >= 3500 -> ChipGood
    mv >= 3000 -> ChipWarn
    else -> ChipBad
}

private data class ChargingChipInfo(val label: String, val color: Color, val icon: ImageVector)

private fun chargingChip(state: ChargingState): ChargingChipInfo = when (state) {
    ChargingState.CHARGING_AC -> ChargingChipInfo("AC Charging", ChipAccent, Icons.Default.Bolt)
    ChargingState.CHARGING_DC -> ChargingChipInfo("DC Fast Charge", ChipGood, Icons.Default.ElectricBolt)
    ChargingState.NOT_CHARGING -> ChargingChipInfo("Not Charging", ChipNeutral, Icons.Default.Power)
    ChargingState.CHARGING_COMPLETE -> ChargingChipInfo("Charge Complete", ChipGood, Icons.Default.CheckCircle)
}
