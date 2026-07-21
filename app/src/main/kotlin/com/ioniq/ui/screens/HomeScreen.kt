package com.ioniq.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ioniq.ble.ObdTransport
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.VehicleViewModel
import kotlinx.coroutines.flow.StateFlow

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
    val pollStatus by viewModel.pollStatus.collectAsState()
    val pollFailCount by viewModel.pollFailCount.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
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
                // Poll status banner (subtle, only shows when not POLLING)
                if (pollStatus != VehicleRepository.PollStatus.POLLING) {
                    item {
                        PollStatusBanner(pollStatus, pollFailCount)
                    }
                }
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
