package com.ioniq.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ble.ObdTransport
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.VehicleViewModel
import com.ioniq.ui.theme.IoniqBg
import com.ioniq.ui.theme.IoniqBorder
import com.ioniq.ui.theme.IoniqCyan
import com.ioniq.ui.theme.SpaceGrotesk
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────── HomeScreen ───────────────────────────

private const val TAB_TELEMETRY = 0
private const val TAB_DEVICES = 1
private const val TAB_SETTINGS = 2

@Composable
fun HomeScreen(
    viewModel: VehicleViewModel,
    bluetoothReady: StateFlow<Boolean>,
    onRequestPermissions: () -> Unit
) {
    var tab by remember { mutableIntStateOf(TAB_TELEMETRY) }
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

    val isConnected = connectionState == ObdTransport.ConnectionState.CONNECTED

    // Auto-focus the relevant tab when the link state changes (unless user is in Settings).
    LaunchedEffect(connectionState, btReady) {
        if (tab == TAB_SETTINGS) return@LaunchedEffect
        tab = when {
            isConnected -> TAB_TELEMETRY
            else -> TAB_DEVICES
        }
    }

    Scaffold(
        containerColor = IoniqBg,
        topBar = {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Ioniq Telemetry",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = ChipValue
                        )
                        Text(
                            "HYUNDAI IONIQ 5 · E-GMP 800V",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChipNeutral
                        )
                    }
                    if (btReady) {
                        StatusPill(
                            text = when {
                                isReconnecting -> "Reconnecting"
                                isConnected -> "Live"
                                connectionState == ObdTransport.ConnectionState.CONNECTING -> "Connecting"
                                else -> "Disconnected"
                            },
                            color = when {
                                isConnected && !isReconnecting -> ChipGood
                                connectionState == ObdTransport.ConnectionState.CONNECTING || isReconnecting -> ChipWarn
                                else -> ChipBad
                            },
                            pulsing = isConnected && !isReconnecting
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    IconPillButton(
                        icon = Icons.Default.Settings,
                        contentDescription = "Settings",
                        onClick = { tab = TAB_SETTINGS }
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = IoniqBg, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == TAB_TELEMETRY,
                    onClick = { tab = TAB_TELEMETRY },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = { Text("Telemetry", style = MaterialTheme.typography.labelSmall) },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = tab == TAB_DEVICES,
                    onClick = { tab = TAB_DEVICES },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = { Text("Devices", style = MaterialTheme.typography.labelSmall) },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
                    colors = navItemColors()
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                TAB_TELEMETRY -> TelemetryTab(
                    isConnected = isConnected,
                    btReady = btReady,
                    connectionState = connectionState,
                    pollStatus = pollStatus,
                    pollFailCount = pollFailCount,
                    vehicleState = vehicleState,
                    onGoToDevices = { tab = TAB_DEVICES }
                )
                TAB_DEVICES -> DevicesTab(
                    viewModel = viewModel,
                    btReady = btReady,
                    isConnected = isConnected,
                    connectionState = connectionState,
                    isReconnecting = isReconnecting,
                    reconnectAttempts = reconnectAttempts,
                    scanResults = scanResults,
                    scanError = scanError,
                    onRequestPermissions = onRequestPermissions,
                    context = context
                )
                TAB_SETTINGS -> SettingsOverlay(onDismiss = { tab = TAB_TELEMETRY })
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = IoniqCyan,
    selectedTextColor = IoniqCyan,
    indicatorColor = IoniqCyan.copy(alpha = 0.12f),
    unselectedIconColor = ChipNeutral,
    unselectedTextColor = ChipNeutral
)

// ─────────────────────────── Telemetry tab ───────────────────────────

@Composable
private fun TelemetryTab(
    isConnected: Boolean,
    btReady: Boolean,
    connectionState: ObdTransport.ConnectionState,
    pollStatus: VehicleRepository.PollStatus,
    pollFailCount: Int,
    vehicleState: com.ioniq.data.model.VehicleTelemetry?,
    onGoToDevices: () -> Unit
) {
    when {
        isConnected -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (pollStatus != VehicleRepository.PollStatus.POLLING) {
                item { PollStatusBanner(pollStatus, pollFailCount) }
            }
            item { TelemetryDashboard(vehicleState) }
        }
        btReady && connectionState != ObdTransport.ConnectionState.DISCONNECTED -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = IoniqCyan)
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to adapter…", style = MaterialTheme.typography.bodyMedium, color = ChipLabel)
                }
            }
        }
        else -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            IoniqCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "NO ADAPTER CONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        color = ChipLabel,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect your OBD-II adapter to start streaming telemetry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ChipNeutral
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.Button(
                        onClick = onGoToDevices,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = IoniqCyan, contentColor = IoniqBg
                        )
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Devices", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Devices tab ───────────────────────────

@Composable
private fun DevicesTab(
    viewModel: VehicleViewModel,
    btReady: Boolean,
    isConnected: Boolean,
    connectionState: ObdTransport.ConnectionState,
    isReconnecting: Boolean,
    reconnectAttempts: Int,
    scanResults: List<android.bluetooth.BluetoothDevice>,
    scanError: String?,
    onRequestPermissions: () -> Unit,
    context: android.content.Context
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (btReady) {
            item { ConnectionStatusCard(connectionState, isReconnecting, reconnectAttempts) }
        }
        when {
            !btReady -> {
                item { BluetoothSetupCard(onRequestPermissions, context) }
                item { GettingStartedCard() }
            }
            isConnected -> {
                item { PairedDevicesSection(viewModel) }
            }
            else -> {
                item { ScannerSection(viewModel, scanResults, scanError) }
                item { PairedDevicesSection(viewModel) }
                item { GettingStartedCard() }
            }
        }
    }
}
