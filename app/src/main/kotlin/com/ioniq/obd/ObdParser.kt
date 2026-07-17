package com.ioniq.obd

import com.ioniq.data.model.CellReading
import com.ioniq.data.model.ChargingState
import timber.log.Timber

/**
 * Parses ELM327 AT command responses into structured telemetry values.
 *
 * All parse methods accept nullable String and return null on empty/error input.
 * Internal byte arrays are List<Int> (0..255) for safe bitwise operations.
 */
object ObdParser {

    data class PidResponse(
        val pid: String,
        val rawHex: String,
        val value: Float? = null,
        val error: String? = null
    )

    fun parseResponse(pid: String, raw: String): PidResponse {
        val cleaned = cleanResponse(raw)
        if (isError(cleaned)) return PidResponse(pid, raw, error = cleaned)
        val pidHex = pid.replace(" ", "").uppercase()
        val dataPart = stripEcho(cleaned, pidHex)
        val hexBytes = toIntList(dataPart)
        val value = parsePidValue(pid, hexBytes)
        return PidResponse(pid, raw, value = value)
    }

    // ---- Public parse methods (nullable-safe) ----

    fun parseSoc(raw: String?): Float? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "220105"))
        return twoByteTenth(bytes, 0)
    }

    fun parseBatteryVoltage(raw: String?): Float? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "22010C"))
        return twoByteTenth(bytes, 0)
    }

    fun parseBatteryCurrent(raw: String?): Float? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "22010D"))
        return twoByteSignedTenth(bytes, 0)
    }

    fun parseBatteryTemp(raw: String?): List<Float> {
        if (isBad(raw)) return emptyList()
        val bytes = toIntList(extractData(raw!!, "220110"))
        return bytes.map { (it - 40).toFloat() }
    }

    fun parseInletTemp(raw: String?): Float? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "220112"))
        return bytes.firstOrNull()?.let { it - 40f }
    }

    fun parseAmbientTemp(raw: String?): Float? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "220113"))
        return bytes.firstOrNull()?.let { it - 40f }
    }

    fun parseChargingState(raw: String?): ChargingState {
        if (isBad(raw)) return ChargingState.NOT_CHARGING
        val bytes = toIntList(extractData(raw!!, "220111"))
        return when (bytes.firstOrNull()) {
            1 -> ChargingState.CHARGING_AC
            2 -> ChargingState.CHARGING_DC
            3 -> ChargingState.CHARGING_COMPLETE
            else -> ChargingState.NOT_CHARGING
        }
    }

    fun parseCumulativeEnergy(raw: String?): Float? {
        if (isBad(raw)) return null
        val cleaned = cleanResponse(raw!!)
        // Strip service+PID header (e.g. "62B00A " or "62B00B ")
        val hex = cleaned.replace(Regex("^[0-9A-Fa-f]{4,6}\\s*"), "")
        val bytes = toIntList(hex)
        return twoByteTenth(bytes, 0)
    }

    fun parseCellVoltages(timestamp: Long, telemetryId: Long, raw: String?): List<CellReading> {
        if (isBad(raw)) return emptyList()
        val cleaned = cleanResponse(raw!!)
        val hex = cleaned.replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        val bytes = toIntList(hex)
        return bytes.chunked(2).mapIndexedNotNull { index, pair ->
            if (pair.size < 2) return@mapIndexedNotNull null
            val voltage = ((pair[0] shl 8) or pair[1]).toFloat()
            if (voltage > 0f) CellReading(telemetryId = telemetryId, cellIndex = index, voltage = voltage) else null
        }
    }

    fun parseCellVoltageMin(raw: String?): Int? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "22010E"))
        if (bytes.size < 2) return null
        return (bytes[0] shl 8) or bytes[1]
    }

    fun parseCellVoltageMax(raw: String?): Int? {
        if (isBad(raw)) return null
        val bytes = toIntList(extractData(raw!!, "22010F"))
        if (bytes.size < 2) return null
        return (bytes[0] shl 8) or bytes[1]
    }

    // ---- Private helpers ----

    private fun isBad(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val trimmed = raw.trim()
        // Well-known ELM327 error strings — these must never reach the parsers
        val errorStrings = arrayOf(
            "SEARCHING", "BUS INIT", "BUS ERROR", "UNABLE TO CONNECT",
            "NO DATA", "?", "ERROR", "STOPPED"
        )
        if (errorStrings.any { trimmed.uppercase().contains(it) }) return true
        // Reject responses that carry no hex bytes at all (pure textual noise)
        if (!containsHexBytes(cleanResponse(raw))) return true
        return false
    }

    private fun cleanResponse(raw: String): String =
        raw.trim().replace("\r", " ").replace("\n", " ")
            .replace(">", "").replace(Regex("\\d:"), "").trim()

    private fun isError(cleaned: String): Boolean {
        val upper = cleaned.uppercase()
        val errorStrings = arrayOf(
            "SEARCHING", "BUS INIT", "BUS ERROR", "UNABLE TO CONNECT",
            "NO DATA", "?", "ERROR", "STOPPED"
        )
        if (errorStrings.any { upper.contains(it) }) return true
        if (!containsHexBytes(cleaned)) return true
        return false
    }

    /** True if the string contains at least one pair of hex digits (a byte). */
    private fun containsHexBytes(s: String): Boolean =
        Regex("[0-9A-Fa-f]{2}").containsMatchIn(s.replace(" ", ""))

    private fun stripEcho(cleaned: String, pidHex: String): String =
        if (cleaned.uppercase().startsWith(pidHex)) cleaned.substring(pidHex.length).trim() else cleaned

    private fun extractData(raw: String, pid: String): String {
        val cleaned = cleanResponse(raw)
        if (isError(cleaned)) return ""
        val pidHex = pid.replace(" ", "").uppercase()
        return stripEcho(cleaned, pidHex)
    }

    /** Convert hex string to List<Int> (each byte 0..255) */
    private fun toIntList(hex: String): List<Int> =
        hex.replace(" ", "").chunked(2).mapNotNull { runCatching { it.toInt(16) }.getOrNull() }

    private fun parsePidValue(pid: String, bytes: List<Int>): Float? = when (pid.uppercase()) {
        "010D" -> bytes.getOrNull(0)?.toFloat()
        "0105" -> bytes.getOrNull(0)?.let { it - 40f }
        "010F" -> bytes.getOrNull(0)?.let { it - 40f }
        "0142" -> if (bytes.size >= 2) ((bytes[0] shl 8) or bytes[1]) / 1000f else null
        "0104" -> bytes.getOrNull(0)?.let { it * 100f / 255f }
        "220105", "220106", "220108", "220109", "22010A", "22010C" -> twoByteTenth(bytes, 0)
        "22010B", "22010D", "220114" -> twoByteSignedTenth(bytes, 0)
        "22010E", "22010F" -> {
            if (bytes.size < 2) null else ((bytes[0] shl 8) or bytes[1]) * 1f
        }
        "220110" -> bytes.firstOrNull()?.let { it - 40f }
        "22B002" -> { if (bytes.size >= 2) ((bytes[0] shl 8) or bytes[1]).toFloat() else null }
        "220111" -> bytes.firstOrNull()?.toFloat()
        else -> { Timber.d("Unknown PID: $pid"); null }
    }

    private fun twoByteTenth(bytes: List<Int>, offset: Int): Float? {
        if (bytes.size < offset + 2) return null
        return ((bytes[offset] shl 8) or bytes[offset + 1]) / 10f
    }

    private fun twoByteSignedTenth(bytes: List<Int>, offset: Int): Float? {
        if (bytes.size < offset + 2) return null
        val raw = (bytes[offset] shl 8) or bytes[offset + 1]
        val signed = if (raw > 32767) raw - 65536 else raw
        return signed / 10f
    }

    fun initializationCommands(): List<String> = listOf(
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "0100"
    )
}
