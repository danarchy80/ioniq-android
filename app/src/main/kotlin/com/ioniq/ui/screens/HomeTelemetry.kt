package com.ioniq.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Power
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
import com.ioniq.data.model.ChargingState
import com.ioniq.data.model.VehicleTelemetry
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.theme.IoniqBorder
import com.ioniq.ui.theme.IoniqSurfaceHigh
import com.ioniq.ui.theme.JetBrainsMono
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
    val (pillText, pillColor) = when {
        isReconnecting -> "Reconnecting $attempts/5" to ChipWarn
        else -> when (state) {
            ObdTransport.ConnectionState.CONNECTED -> "Live" to ChipGood
            ObdTransport.ConnectionState.CONNECTING -> "Connecting" to ChipWarn
            ObdTransport.ConnectionState.DISCONNECTING -> "Disconnecting" to ChipWarn
            ObdTransport.ConnectionState.DISCONNECTED -> "Disconnected" to ChipBad
        }
    }
    IoniqCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = pillText,
                    color = pillColor,
                    pulsing = state == ObdTransport.ConnectionState.CONNECTED && !isReconnecting
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when (state) {
                        ObdTransport.ConnectionState.CONNECTED -> "OBD-II BUS STREAM"
                        else -> "ADAPTER LINK"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ChipNeutral,
                    letterSpacing = 1.5.sp
                )
            }
            if (isReconnecting) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = ChipWarn,
                    trackColor = IoniqBorder
                )
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
    val accent: Color
    val icon: ImageVector
    val title: String
    val subtitle: String
    when (status) {
        VehicleRepository.PollStatus.POLLING -> return
        VehicleRepository.PollStatus.VEHICLE_OFF -> {
            accent = ChipWarn; icon = Icons.Default.Power
            title = "Vehicle asleep"
            subtitle = "Polling at reduced cadence · failures: $failCount"
        }
        VehicleRepository.PollStatus.ECU_UNREACHABLE -> {
            accent = ChipBad; icon = Icons.Default.LinkOff
            title = "ECU unreachable"
            subtitle = "Retrying — no hard disconnect · failures: $failCount"
        }
    }
    IoniqCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = IoniqSurfaceHigh,
        borderColor = accent.copy(alpha = 0.4f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = accent
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ChipValue, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, color = accent.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─────────────────────────── TelemetryDashboard ───────────────────────────

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
        // ── Hero: SOC ring + charging state ──
        item {
            IoniqCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SocRing(soc = telemetry.soc, ringColor = socColor(telemetry.soc), size = 210)
                    Spacer(Modifier.height(14.dp))
                    val (chipLabel, chipColor, chipIcon) = chargingChip(telemetry.chargingState)
                    StatusPill(text = chipLabel, color = chipColor, icon = chipIcon)
                    Spacer(Modifier.height(10.dp))
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    Text(
                        "UPDATED ${timeFormat.format(Date(telemetry.timestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChipNeutral
                    )
                }
            }
        }

        // ── Real-time vitals grid (2 columns) ──
        item { SectionHeader("Real-Time Vitals") }
        item {
            val v = telemetry.batteryVoltage
            val a = telemetry.batteryCurrent
            val kw = telemetry.chargePower
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile(
                        label = "Voltage", value = v?.let { "%.1f".format(it) } ?: "—", unit = "V",
                        tint = ChipAccent, modifier = Modifier.weight(1f),
                        fraction = v?.let { it / 420f }
                    )
                    MetricTile(
                        label = "Current", value = a?.let { "%.1f".format(it) } ?: "—", unit = "A",
                        tint = if ((a ?: 0f) >= 0f) ChipAccent else ChipGood, modifier = Modifier.weight(1f),
                        fraction = a?.let { kotlin.math.abs(it) / 250f }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile(
                        label = "Power", value = kw?.let { "%.1f".format(it) } ?: "—", unit = "kW",
                        tint = if ((kw ?: 0f) > 0f) ChipGood else ChipNeutral, modifier = Modifier.weight(1f),
                        fraction = kw?.let { kotlin.math.abs(it) / 240f }
                    )
                    MetricTile(
                        label = "Battery", value = telemetry.batteryTempMax?.let { "%.0f".format(it) } ?: "—", unit = "°C",
                        tint = tempColor(telemetry.batteryTempMax), modifier = Modifier.weight(1f),
                        fraction = telemetry.batteryTempMax?.let { (it + 20f) / 80f }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile(
                        label = "Inlet", value = telemetry.inletTemp?.let { "%.0f".format(it) } ?: "—", unit = "°C",
                        tint = tempColor(telemetry.inletTemp), modifier = Modifier.weight(1f),
                        fraction = telemetry.inletTemp?.let { (it + 20f) / 80f }
                    )
                    MetricTile(
                        label = "Ambient", value = telemetry.ambientTemp?.let { "%.0f".format(it) } ?: "—", unit = "°C",
                        tint = ChipNeutral, modifier = Modifier.weight(1f),
                        fraction = telemetry.ambientTemp?.let { (it + 20f) / 60f }
                    )
                }
            }
        }

        // ── Charging session (UDS E001–E003, while plugged in) ──
        if (telemetry.chargingState == ChargingState.CHARGING_AC ||
            telemetry.chargingState == ChargingState.CHARGING_DC
        ) {
            item {
                val evseKw = telemetry.evseMaxPowerKw
                val bmsKw = if (telemetry.bmsTargetVoltage != null && telemetry.bmsTargetCurrent != null)
                    telemetry.bmsTargetVoltage!! * telemetry.bmsTargetCurrent!! / 1000f else null
                SectionHeader("Charging Session") {
                    when {
                        evseKw != null && bmsKw != null && evseKw < bmsKw * 0.95f ->
                            StatusPill(text = "EVSE-limited", color = ChipWarn)
                        evseKw != null && bmsKw != null ->
                            StatusPill(text = "BMS-limited", color = ChipAccent)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "EVSE Max",
                            value = telemetry.evseMaxPowerKw?.let { "%.0f".format(it) } ?: "—",
                            unit = "kW",
                            tint = ChipWarn,
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.evseMaxPowerKw?.let { (it / 350f).coerceIn(0f, 1f) }
                        )
                        MetricTile(
                            label = "BMS Target",
                            value = (telemetry.bmsTargetVoltage?.let { v ->
                                telemetry.bmsTargetCurrent?.let { a -> "%.0f".format(v * a / 1000f) }
                            }) ?: "—",
                            unit = "kW",
                            tint = ChipAccent,
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.bmsTargetCurrent?.let { (it / 400f).coerceIn(0f, 1f) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "Time Left",
                            value = telemetry.chargeTimeRemainingMin?.let {
                                if (it >= 60) "${it / 60}h %02dm".format(it % 60) else "${it}m"
                            } ?: "—",
                            unit = "",
                            tint = ChipGood,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Session Energy",
                            value = telemetry.evseSessionEnergyKwh?.let { "%.1f".format(it) } ?: "—",
                            unit = "kWh",
                            tint = ChipGood,
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.evseSessionEnergyKwh?.let { (it / 100f).coerceIn(0f, 1f) }
                        )
                    }
                    if (telemetry.evseMaxVoltage != null && telemetry.evseMaxCurrent != null) {
                        Text(
                            "EVSE HANDSHAKE ${"%.0f".format(telemetry.evseMaxVoltage)} V · ${"%.0f".format(telemetry.evseMaxCurrent)} A",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChipNeutral,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }
        }

        // ── Cell voltages + equilibrium ──
        item {
            val min = telemetry.cellVoltageMin
            val max = telemetry.cellVoltageMax
            val delta = if (min != null && max != null) max - min else null
            SectionHeader("Cell Voltages") {
                if (delta != null) {
                    StatusPill(
                        text = "$delta mV Δ ${if (delta < 50) "Healthy" else if (delta < 100) "Watch" else "Alert"}",
                        color = if (delta < 50) ChipGood else if (delta < 100) ChipWarn else ChipBad
                    )
                }
            }
        }
        item {
            IoniqCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("MIN CELL", style = MaterialTheme.typography.labelSmall, color = ChipLabel, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                fmtMv(telemetry.cellVoltageMin),
                                fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                color = cellColor(telemetry.cellVoltageMin)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("MAX CELL", style = MaterialTheme.typography.labelSmall, color = ChipLabel, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                fmtMv(telemetry.cellVoltageMax),
                                fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                color = cellColor(telemetry.cellVoltageMax)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    CellBalanceBar(minMv = telemetry.cellVoltageMin, maxMv = telemetry.cellVoltageMax)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(fmtMv(telemetry.cellVoltageMin), style = MaterialTheme.typography.labelSmall, color = ChipNeutral)
                        Text(fmtMv(telemetry.cellVoltageMax), style = MaterialTheme.typography.labelSmall, color = ChipNeutral)
                    }
                }
            }
        }

        // ── Full cell heatmap (UDS 192-cell block) ──
        val fullCells = telemetry.cellVoltagesCsv
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (fullCells.isNotEmpty()) {
            item {
                SectionHeader("Cell Heatmap") {
                    Text(
                        "${fullCells.size} CELLS",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChipNeutral
                    )
                }
            }
            item {
                IoniqCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        CellHeatmap(cells = fullCells)
                    }
                }
            }
        }

        // ── Battery health: HV isolation ──
        telemetry.isolationResistanceKohm?.let { iso ->
            item {
                SectionHeader("Battery Health") {
                    StatusPill(
                        text = if (iso >= 500) "Isolation OK" else if (iso >= 100) "Isolation Watch" else "Isolation Fault",
                        color = if (iso >= 500) ChipGood else if (iso >= 100) ChipWarn else ChipBad
                    )
                }
            }
            item {
                MetricTile(
                    label = "HV Isolation Resistance",
                    value = "%,d".format(iso),
                    unit = "kΩ",
                    tint = if (iso >= 500) ChipGood else if (iso >= 100) ChipWarn else ChipBad,
                    modifier = Modifier.fillMaxWidth(),
                    fraction = (iso / 2000f).coerceIn(0f, 1f)
                )
            }
        }

        // ── 12V auxiliary battery ──
        if (telemetry.auxBatteryVoltage != null) {
            item {
                SectionHeader("12V Auxiliary Battery") {
                    val v = telemetry.auxBatteryVoltage
                    if (v != null && v < 12.2f) StatusPill(text = "Low", color = ChipBad)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "Voltage",
                            value = telemetry.auxBatteryVoltage?.let { "%.2f".format(it) } ?: "—",
                            unit = "V",
                            tint = when {
                                (telemetry.auxBatteryVoltage ?: 99f) < 12.2f -> ChipBad
                                (telemetry.auxBatteryVoltage ?: 99f) < 12.6f -> ChipWarn
                                else -> ChipGood
                            },
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.auxBatteryVoltage?.let { ((it - 11f) / 5f) }
                        )
                        MetricTile(
                            label = "Current",
                            value = telemetry.auxBatteryCurrent?.let { "%.1f".format(it) } ?: "—",
                            unit = "A",
                            tint = if ((telemetry.auxBatteryCurrent ?: 0f) >= 0f) ChipGood else ChipWarn,
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.auxBatteryCurrent?.let { (kotlin.math.abs(it) / 30f) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "Aux SOC",
                            value = telemetry.auxBatterySoc?.let { "%.0f".format(it) } ?: "—",
                            unit = "%",
                            tint = socColor(telemetry.auxBatterySoc),
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.auxBatterySoc?.let { (it / 100f) }
                        )
                        MetricTile(
                            label = "Aux Temp",
                            value = telemetry.auxBatteryTemp?.let { "%.0f".format(it) } ?: "—",
                            unit = "°C",
                            tint = tempColor(telemetry.auxBatteryTemp),
                            modifier = Modifier.weight(1f),
                            fraction = telemetry.auxBatteryTemp?.let { ((it + 20f) / 80f) }
                        )
                    }
                }
            }
        }

        // ── TPMS ──
        if (telemetry.tpmsFlPsi != null) {
            item { SectionHeader("Tires") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        TireTile("FL", telemetry.tpmsFlPsi, telemetry.tpmsFlTemp, Modifier.weight(1f))
                        TireTile("FR", telemetry.tpmsFrPsi, telemetry.tpmsFrTemp, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        TireTile("RL", telemetry.tpmsRlPsi, telemetry.tpmsRlTemp, Modifier.weight(1f))
                        TireTile("RR", telemetry.tpmsRrPsi, telemetry.tpmsRrTemp, Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Lifetime charging stats (UDS 0111) ──
        if (telemetry.acChargeCount != null || telemetry.dcChargeCount != null) {
            item { SectionHeader("Lifetime Charging") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "AC Sessions",
                            value = telemetry.acChargeCount?.toString() ?: "—",
                            unit = "",
                            tint = ChipAccent,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "AC Energy",
                            value = telemetry.acChargeEnergyKwh?.let { "%,d".format(it) } ?: "—",
                            unit = "kWh",
                            tint = ChipAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricTile(
                            label = "DC Sessions",
                            value = telemetry.dcChargeCount?.toString() ?: "—",
                            unit = "",
                            tint = ChipWarn,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "DC Energy",
                            value = telemetry.dcChargeEnergyKwh?.let { "%,d".format(it) } ?: "—",
                            unit = "kWh",
                            tint = ChipWarn,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── Cumulative energy ──
        item { SectionHeader("Cumulative Energy") }
        item {
            val charged = telemetry.cumulativeEnergyCharged
            val discharged = telemetry.cumulativeEnergyDischarged
            val scale = maxOf(charged ?: 0f, discharged ?: 0f, 1f)
            IoniqCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    EnergyRow(
                        label = "Charged",
                        value = fmtKwh(charged),
                        tint = ChipGood,
                        fraction = (charged ?: 0f) / scale
                    )
                    EnergyRow(
                        label = "Discharged",
                        value = fmtKwh(discharged),
                        tint = ChipWarn,
                        fraction = (discharged ?: 0f) / scale
                    )
                }
            }
        }
    }
}
