package com.ioniq.diag

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StrictMode
import com.ioniq.BuildConfig
import com.ioniq.data.model.VehicleTelemetry
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Collects all diagnostic data for a support email:
 * - Recent in-app log buffer
 * - Previous run's logs (from disk — survives crashes)
 * - logcat dump (app-only, no root required)
 * - Device snapshot (model, OS, BT state, battery, connectivity)
 * - Latest telemetry snapshot
 * - App version & build info
 */
object SupportEmailCollector {

    fun collect(context: Context, telemetry: VehicleTelemetry? = null): String {
        val sb = StringBuilder(8192)

        sb.appendSection("APP INFO") {
            // Read version from PackageManager at runtime instead of BuildConfig.VERSION_NAME.
            // BuildConfig constants are static final Strings — the Kotlin compiler inlines them
            // at compile time, and incremental compilation can skip recompiling unchanged source
            // files even when the BuildConfig value changes, leaving a stale version baked in.
            // PackageManager reads from the APK manifest at runtime, always current.
            val pkgInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            line("Version", "${pkgInfo?.versionName ?: "unknown"} (${pkgInfo?.longVersionCode ?: BuildConfig.VERSION_CODE})")
            line("Build type", BuildConfig.BUILD_TYPE)
            line("Package", context.packageName)
        }

        sb.appendSection("DEVICE INFO") {
            line("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            line("Locale", java.util.Locale.getDefault().toString())
            line("Screen", "${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (battery != null) {
                line("Battery", "${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
                line("Charging", "${battery.isCharging}")
            }
        }

        sb.appendSection("BLUETOOTH STATE") {
            try {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                line("Supported", "${btManager != null}")
                line("Enabled", "${adapter?.isEnabled == true}")
                line("Name", adapter?.name ?: "N/A")
                val bondedCount = try { adapter?.bondedDevices?.size ?: 0 } catch (e: SecurityException) { -1 }
                line("Bonded devices", "$bondedCount${if (bondedCount >= 0) "" else " (no permission)"}")
                if (bondedCount > 0) {
                    try {
                        adapter!!.bondedDevices.forEach { d ->
                            line("  - ", "${d.name ?: "Unknown"} (${d.address}) type=${d.type}")
                        }
                    } catch (_: SecurityException) { /* perms */ }
                }
            } catch (e: Exception) {
                line("Error", e.message ?: "unknown")
            }
        }

        sb.appendSection("CONNECTIVITY") {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = cm?.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                line("Active network", "${network != null}")
                line("WiFi", "${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}")
                line("Cellular", "${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true}")
            } catch (e: Exception) {
                line("Error", e.message ?: "unknown")
            }
        }

        if (telemetry != null) {
            sb.appendSection("LATEST TELEMETRY") {
                line("Timestamp", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(telemetry.timestamp)))
                line("SOC", "${telemetry.soc?.let { "%.1f%%".format(it) } ?: "N/A"}")
                line("SOH", "${telemetry.soh?.let { "%.1f%%".format(it) } ?: "N/A"}")
                line("Battery voltage", "${telemetry.batteryVoltage?.let { "%.1fV".format(it) } ?: "N/A"}")
                line("Battery current", "${telemetry.batteryCurrent?.let { "%.1fA".format(it) } ?: "N/A"}")
                line("Temp min/max", "${telemetry.batteryTempMin ?: "?"}°C / ${telemetry.batteryTempMax ?: "?"}°C")
                line("Cell mV min/max", "${telemetry.cellVoltageMin ?: "?"}/${telemetry.cellVoltageMax ?: "?"}")
                line("Odometer", "${telemetry.odometer?.let { "%.0f km".format(it) } ?: "N/A"}")
                line("Charging", telemetry.chargingState.name)
                line("Charge power", "${telemetry.chargePower?.let { "%.1f kW".format(it) } ?: "N/A"}")
            }
        }

        sb.appendSection("APP LOG BUFFER (last ${LogBuffer.drain().size} lines)") {
            LogBuffer.drain().forEach { line(it, "") }
        }

        // Include previous run's logs so we can see what happened before a crash
        val previousLogs = LogBuffer.getPreviousRunLogs(context)
        if (previousLogs != null) {
            sb.appendSection("PREVIOUS RUN LOGS (${previousLogs.size} lines)") {
                line("--- Previous session logs ---", "")
                previousLogs?.forEach { line(it, "") }
            }
        }

        sb.appendSection("LOGCAT (last 100 lines)") {
            try {
                // logcat -d : dump and exit, :*:I  verbose filter, our app PID only
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec("logcat -d -v threadtime --pid=$pid")
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val lines = mutableListOf<String>()
                reader.forEachLine { lines.add(it) }
                proc.waitFor()
                reader.close()
                // Take last 100
                lines.takeLast(100).forEach { line(it, "") }
            } catch (e: Exception) {
                line("Error capturing logcat", e.message ?: "unknown")
            }
        }

        return sb.toString()
    }

    private fun StringBuilder.appendSection(title: String, block: SectionBuilder.() -> Unit) {
        append("\n═══ $title ═══\n")
        block(SectionBuilder(this))
        append("\n")
    }

    class SectionBuilder(private val sb: StringBuilder) {
        fun line(key: String, value: String) {
            sb.append("$key: $value\n")
        }
    }
}
