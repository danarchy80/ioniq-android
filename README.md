# Ioniq Android

Android application for monitoring Hyundai Ioniq 5 electric vehicle telemetry via OBD-II BLE adapter.

![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-blue.svg)
![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)

## Features

### Real-time Monitoring
- **State of Charge (SOC)** - Battery percentage display
- **Battery Voltage** - Pack voltage monitoring
- **Battery Current** - Charge/discharge current
- **Battery Temperature** - Max cell temperature
- **Ambient & Inlet Temperature** - Environmental readings
- **Charging State** - AC/DC charging status detection

### Cell Voltage Analysis
- Monitors all 160 battery cells individually
- Tracks min/max cell voltages
- Identifies cell imbalances
- Persists readings to local database

### Data Persistence
- Room database stores historical readings
- Queryable telemetry data
- Automatic cleanup with retention policies

### Background Operation
- Foreground service with persistent notification
- Auto-reconnect with exponential backoff on BLE disconnect
- Dual-cycle polling (2s rapid + 30s background)
- Battery-optimized background scheduling via WorkManager

### Home Assistant Integration
- WebSocket client for real-time data streaming
- Encrypted storage of HA credentials
- Automatic reconnection on network changes
- Streams all telemetry values as HA sensors

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│  HomeScreen (Compose) ← VehicleViewModel ← UI State     │
└─────────────┬───────────────────────────────────────────┘
              │ Observe
┌─────────────▼───────────────────────────────────────────┐
│                      Repository Layer                    │
│  VehicleRepository (orchestrates data flow)             │
└─────────────┬───────────────────────────────────────────┘
              │ Coordinates
    ┌─────────┼─────────┬─────────────┐
    │         │         │             │
┌───▼──┐  ┌──▼──┐  ┌───▼───┐  ┌─────▼──────┐
│  BLE │  │ OBD │  │  DB   │  │    HA      │
│ Mgr  │  │Parse│  │  DAO  │  │  Client    │
└───┬──┘  └──┬──┘  └───┬───┘  └─────┬──────┘
    │        │         │             │
    ▼        ▼         ▼             ▼
  ELM327   CAN Bus   Room DB    Home Assistant
  Adapter            (SQLite)   (WebSocket)
```

## Technology Stack

- **Language**: Kotlin 1.9.22
- **UI**: Jetpack Compose with Material3
- **Async**: Kotlin Coroutines + Flow
- **Dependency Injection**: Koin 3.5.3
- **Database**: Room 2.6.1 with KSP
- **Networking**: OkHttp 4.12.0
- **WebSocket**: Java-WebSocket 1.5.7
- **Reactive Extensions**: RxJava3 3.1.8
- **Security**: AndroidX Security Crypto 1.1.0-alpha06
- **BLE**: Native Android GATT API
- **Background**: WorkManager 2.9.0

## Requirements

- Android 7.0 (API 24) or higher
- Bluetooth Low Energy (BLE) capable device
- ELM327-compatible OBD-II BLE adapter
- Hyundai Ioniq 5 or compatible Hyundai/Kia EV

## Installation

### From GitHub Releases

Download the latest APK from [Releases](https://github.com/danarchy80/ioniq-android/releases) and install on your device.

### Build from Source

```bash
# Clone the repository
git clone https://github.com/danarchy80/ioniq-android.git
cd ioniq-android

# Build debug APK
./gradle_bootstrap.sh assembleDebug

# APK location
ls -lh app/build/outputs/apk/debug/
```

## Usage

### Initial Setup

1. Install the app on your Android device
2. Grant required permissions:
   - Location (for BLE scanning)
   - Bluetooth (for device connection)
   - Notifications (for foreground service)
3. Pair your OBD-II BLE adapter in system Bluetooth settings
4. Launch the app and tap "Connect to Vehicle"

### Monitoring

Once connected, the app will:
- Display real-time vehicle telemetry
- Persist readings to local database
- Show battery cell voltage distribution
- Monitor charging status

### Home Assistant Integration (Optional)

1. Navigate to Settings → Home Assistant
2. Enter your Home Assistant URL (e.g., `http://homeassistant.local:8123`)
3. Generate a long-lived access token in Home Assistant
4. Paste the token in the app
5. Data will automatically stream to HA as sensors

## OBD-II PIDs

The app queries these manufacturer-specific PIDs via CAN bus (Mode 22):

| PID | Description | Cycle |
|-----|-------------|-------|
| 220105 | SOC Display (%) | Rapid |
| 220101 | Battery Voltage (V) | Rapid |
| 220102 | Battery Current (A) | Rapid |
| 220103 | Battery Temp Max (°C) | Rapid |
| 220104 | Ambient Temp (°C) | Rapid |
| 2201D2 | Inlet Temp (°C) | Rapid |
| 220107 | Cell Voltage Min (V) | Slow |
| 220109 | Cell Voltage Max (V) | Slow |
| 220201 | Cell Voltages (96 cells) | Slow |
| 22B002 | Charging State | Rapid |

## Project Structure

```
app/src/main/kotlin/com/ioniq/
├── ble/
│   ├── BleScanner.kt              # BLE device discovery
│   └── ElmBleManager.kt           # BLE GATT connection + auto-reconnect
├── data/
│   ├── db/
│   │   ├── IoniqDatabase.kt       # Room database definition
│   │   ├── TelemetryDao.kt        # Telemetry queries/inserts
│   │   └── CellReadingDao.kt      # Cell voltage queries/inserts
│   ├── model/
│   │   ├── VehicleTelemetry.kt    # Main telemetry entity
│   │   └── CellReading.kt         # Cell voltage entity
│   ├── repository/
│   │   └── VehicleRepository.kt   # Data orchestration layer
│   └── worker/
│       └── SlowPollWorker.kt      # Background data collection
├── ha/
│   └── HomeAssistantClient.kt     # WebSocket client for HA
├── obd/
│   ├── ObdPids.kt                 # OBD-II PID definitions
│   └── ObdParser.kt               # CAN response parser
├── service/
│   └── VehicleMonitorService.kt   # Foreground service
└── ui/
    ├── HomeScreen.kt              # Main Compose UI
    ├── MainActivity.kt            # Activity entry point
    ├── VehicleViewModel.kt        # UI state management
    ├── theme/
    │   └── Theme.kt               # Material3 theming
    └── IoniqApplication.kt        # Application class + Koin setup
```

## Configuration

### Polling Intervals

Adjust in `VehicleRepository.kt`:
- `RAPID_POLL_INTERVAL_MS` - Fast data (default 2000ms)
- `SLOW_POLL_INTERVAL_MS` - Background data (default 30000ms)

### Database Retention

Data auto-cleans after 30 days. Modify in `IoniqDatabase.kt`.

## Development

### Building

```bash
# Debug build
./gradle_bootstrap.sh assembleDebug

# Release build (requires signing config)
./gradle_bootstrap.sh assembleRelease

# Run tests
./gradle_bootstrap.sh test

# Lint check
./gradle_bootstrap.sh lint
```

### Testing on Physical Device

1. Enable USB debugging on your Android device
2. Connect via USB or enable wireless ADB
3. Install APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Viewing Logs

```bash
# All logs
adb logcat | grep Ioniq

# BLE connection logs
adb logcat | grep -E "(ElmBleManager|BleScanner)"

# OBD parsing logs
adb logcat | grep ObdParser

# Service logs
adb logcat | grep VehicleMonitorService
```

## Troubleshooting

### BLE Connection Issues

- Ensure OBD-II adapter is powered and in pairing mode
- Check Bluetooth permissions are granted
- Verify device supports BLE (Bluetooth 4.0+)
- Try unpairing and re-pairing in system settings

### No Data After Connection

- Confirm vehicle ignition is ON (not just accessory)
- Check OBD-II adapter compatibility (must support Hyundai CAN protocol)
- Verify ELM327 firmware version (v1.5+ recommended)

### Home Assistant Integration

- Ensure HA URL is accessible from your device
- Verify long-lived access token is valid
- Check HA logs for WebSocket connection errors
- Confirm device and HA are on same network (or use Nabu Casa)

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Disclaimer

This app is provided as-is for educational and personal use. The author is not affiliated with Hyundai Motor Company. Use at your own risk. Always prioritize safe driving and follow local regulations regarding vehicle modifications and data collection.

## Acknowledgments

- [Nordic Semiconductor](https://www.nordicsemi.com/) for BLE library reference
- [Home Assistant](https://www.home-assistant.io/) community for WebSocket API documentation
- Hyundai Ioniq community for OBD-II PID research and testing
- Open source contributors to ELM327 and OBD-II projects

## Support

For issues, questions, or feature requests:
- Open an [Issue](https://github.com/danarchy80/ioniq-android/issues) on GitHub
- Check [Discussions](https://github.com/danarchy80/ioniq-android/discussions) for community support

---

**Version**: 0.1.0 (Debug)  
**Last Updated**: 2026-07-07  
**Min Android**: 7.0 (API 24)  
**Target Android**: 14 (API 34)
