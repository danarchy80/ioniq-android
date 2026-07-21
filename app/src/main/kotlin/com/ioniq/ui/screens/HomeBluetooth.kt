package com.ioniq.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ioniq.ui.VehicleViewModel

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

@Composable
fun PairedDevicesSection(viewModel: VehicleViewModel) {
    val paired by viewModel.pairedClassicDevices.collectAsState()
    val context = LocalContext.current

    // (Re-)load bond list whenever this section is visible.
    LaunchedEffect(Unit) { viewModel.refreshPairedDevices() }

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
                    fontFamily = FontFamily.Monospace
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
                    fontFamily = FontFamily.Monospace
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
