package com.ioniq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
