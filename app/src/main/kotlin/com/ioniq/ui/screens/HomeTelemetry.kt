package com.ioniq.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ble.ObdTransport
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.data.repository.VehicleRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

// ─────────────────────────── PollStatus banner ───────────────────────────

@Composable
fun PollStatusBanner(
    status: VehicleRepository.PollStatus,
    failCount: Int
) {
    if (status == VehicleRepository.PollStatus.POLLING) return
    val bg: Color
    val accent: Color
    val icon: ImageVector
    val title: String
    val subtitle: String
    when (status) {
        VehicleRepository.PollStatus.POLLING -> return
        VehicleRepository.PollStatus.VEHICLE_OFF -> {
            bg = Color(0xFF2A2416); accent = ChipWarn; icon = Icons.Default.Power
            title = "Vehicle asleep"
            subtitle = "Polling at reduced cadence · failures: $failCount"
        }
        VehicleRepository.PollStatus.ECU_UNREACHABLE -> {
            bg = Color(0xFF2A1B1B); accent = ChipBad; icon = Icons.Default.LinkOff
            title = "ECU unreachable"
            subtitle = "Retrying — no hard disconnect · failures: $failCount"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = accent
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ChipValue, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = accent.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─────────────────────────── TelemetryDashboard ───────────────────────────

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
        contentPadding = PaddingValues(bottom = 16.dp)
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
