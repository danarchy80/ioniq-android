package com.ioniq.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ui.VehicleViewModel
import com.ioniq.ui.theme.IoniqBg
import com.ioniq.ui.theme.IoniqCrimson
import com.ioniq.ui.theme.IoniqSurfaceHigh
import com.ioniq.ui.theme.JetBrainsMono

// ─────────────────────────── BluetoothSetupCard ───────────────────────────

@Composable
fun BluetoothSetupCard(onRequestPermissions: () -> Unit, context: android.content.Context) {
    IoniqCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = ChipAccent.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = ChipAccent
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Bluetooth Setup Required",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ChipValue
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enable Bluetooth and grant permissions to scan for OBD-II adapters.",
                style = MaterialTheme.typography.bodyMedium,
                color = ChipLabel
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ChipAccent, contentColor = IoniqBg)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Grant Permissions", fontWeight = FontWeight.Bold)
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(999.dp)
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
        SectionHeader("My Paired OBD Adapters", trailing = {
            Text(
                "${paired.size} SAVED",
                style = MaterialTheme.typography.labelSmall,
                color = ChipNeutral
            )
        })
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { viewModel.refreshPairedDevices() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh paired list")
        }

        if (paired.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            IoniqCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(999.dp)
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

    IoniqCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onConnect)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    style = MaterialTheme.typography.labelSmall,
                    color = ChipNeutral,
                    fontFamily = JetBrainsMono
                )
            }
            StatusPill(text = "Reconnect", color = if (isBonded) ChipGood else ChipAccent)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Connect",
                tint = ChipAccent,
                modifier = Modifier.size(18.dp)
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
        // Radar hero
        IoniqCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RadarSweep(size = 200)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (devices.isEmpty()) "SCANNING FOR OBD-II ADAPTERS…" else "SCAN COMPLETE",
                    style = MaterialTheme.typography.labelMedium,
                    color = ChipLabel,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.startScan() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChipAccent, contentColor = IoniqBg)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan for OBD-II Adapters", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Error banner
        if (scanError != null) {
            Spacer(Modifier.height(12.dp))
            IoniqCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = IoniqSurfaceHigh,
                borderColor = IoniqCrimson.copy(alpha = 0.4f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = ChipBad)
                    Spacer(Modifier.width(8.dp))
                    Text(scanError, style = MaterialTheme.typography.bodySmall, color = ChipBad)
                }
            }
        }

        // Device list
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Nearby Adapters", trailing = {
                Text(
                    "${devices.size} FOUND",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChipNeutral
                )
            })
            Spacer(Modifier.height(8.dp))
            devices.forEach { device ->
                DeviceListItem(device) { viewModel.connect(device) }
            }
        }
    }
}

@Composable
fun DeviceListItem(device: BluetoothDevice, onConnect: () -> Unit) {
    IoniqCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onConnect),
        borderColor = ChipAccent.copy(alpha = 0.25f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    style = MaterialTheme.typography.labelSmall,
                    color = ChipNeutral,
                    fontFamily = JetBrainsMono
                )
            }
            StatusPill(text = "Connect", color = ChipAccent)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Connect",
                tint = ChipAccent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
