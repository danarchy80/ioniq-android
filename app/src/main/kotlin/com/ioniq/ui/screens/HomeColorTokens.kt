package com.ioniq.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ioniq.data.model.ChargingState

// ─────────────────────────── Chip color tokens ───────────────────────────

internal val ChipBg = Color(0xFF162235)
internal val ChipLabel = Color(0xFF90A4AE)
internal val ChipValue = Color(0xFFE0E0E0)
internal val ChipGood = Color(0xFF4CAF50)
internal val ChipWarn = Color(0xFFFFA726)
internal val ChipBad = Color(0xFFEF5350)
internal val ChipAccent = Color(0xFF00B4E6)
internal val ChipNeutral = Color(0xFF78909C)

// ─────────────────────────── Format helpers ───────────────────────────

internal fun fmtPct(v: Float?) = v?.let { String.format("%.1f%%", it) } ?: "—"
internal fun fmtVolt(v: Float?) = v?.let { String.format("%.1f V", it) } ?: "—"
internal fun fmtAmp(v: Float?) = v?.let { String.format("%.1f A", it) } ?: "—"
internal fun fmtKw(v: Float?) = v?.let { String.format("%.2f kW", it) } ?: "—"
internal fun fmtTemp(v: Float?) = v?.let { String.format("%.1f °C", it) } ?: "—"
internal fun fmtMv(v: Int?) = v?.let { String.format("%d mV", it) } ?: "—"
internal fun fmtKm(v: Float?) = v?.let { String.format("%.0f km", it) } ?: "—"
internal fun fmtKwh(v: Float?) = v?.let { String.format("%.2f kWh", it) } ?: "—"

// ─────────────────────────── Color helpers ───────────────────────────

internal fun socColor(soc: Float?): Color = when {
    soc == null -> ChipNeutral
    soc >= 60f -> ChipGood
    soc >= 30f -> ChipWarn
    else -> ChipBad
}

internal fun tempColor(temp: Float?): Color = when {
    temp == null -> ChipNeutral
    temp > 45f -> ChipBad
    temp > 35f -> ChipWarn
    temp < 0f -> ChipAccent
    else -> ChipGood
}

internal fun cellColor(mv: Int?): Color = when {
    mv == null -> ChipNeutral
    mv >= 3500 -> ChipGood
    mv >= 3000 -> ChipWarn
    else -> ChipBad
}

internal data class ChargingChipInfo(val label: String, val color: Color, val icon: ImageVector)

internal fun chargingChip(state: ChargingState): ChargingChipInfo = when (state) {
    ChargingState.CHARGING_AC -> ChargingChipInfo("AC Charging", ChipAccent, Icons.Default.Bolt)
    ChargingState.CHARGING_DC -> ChargingChipInfo("DC Fast Charge", ChipGood, Icons.Default.ElectricBolt)
    ChargingState.NOT_CHARGING -> ChargingChipInfo("Not Charging", ChipNeutral, Icons.Default.Power)
    ChargingState.CHARGING_COMPLETE -> ChargingChipInfo("Charge Complete", ChipGood, Icons.Default.CheckCircle)
}
