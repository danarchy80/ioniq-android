package com.ioniq.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    // Read from PackageManager to avoid Kotlin compile-time constant inlining
                    // of BuildConfig.VERSION_NAME (can go stale with incremental compilation)
                    val pkgInfo = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                            null
                        }
                    }
                    StatRow("App", "${pkgInfo?.versionName ?: "unknown"} (${pkgInfo?.longVersionCode ?: com.ioniq.BuildConfig.VERSION_CODE})")
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
                                fontFamily = FontFamily.Monospace
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

// ─────────────────────────── Support Email Button ───────────────────────────

@Composable
fun SupportEmailButton(telemetry: com.ioniq.data.model.VehicleTelemetry?) {
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
