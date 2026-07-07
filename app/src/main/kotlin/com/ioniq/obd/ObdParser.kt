package com.ioniq.obd

import timber.log.Timber

/**
 * Parses ELM327 AT command responses into structured telemetry values.
 *
 * ELM327 protocol:
 * - Send: "ATZ\r" (reset), "ATL0\r" (linefeeds off), "010D\r" (PID request)
 * - Receive: "41 0D 3C" (response: service+0x40, PID, data bytes)
 * - Multi-line responses may be prefixed with "0:" "1:" etc.
 * - ">" prompt means ready for next command
 * - "?" means no data / unsupported PID
 */
object ObdParser {

    data class PidResponse(
        val pid: String,
        val rawHex: String,
        val value: Float? = null,
        val error: String? = null
    )

    /**
     * Parse a raw ELM327 response string into a structured PidResponse.
     * Input example: "41 0D 3C" or "62 01 05 00 5A" or "? "
     */
    fun parseResponse(pid: String, raw: String): PidResponse {
        val cleaned = raw.trim()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", "")
            .replace(Regex("\\d:"), "") // strip multi-line prefixes
            .trim()

        // Error responses
        if (cleaned == "?" || cleaned.contains("NO DATA") || cleaned.contains("ERROR")) {
            return PidResponse(pid, raw, error = cleaned)
        }

        // Strip echo if present (ELM echoes the command back)
        val pidHex = pid.replace(" ", "").uppercase()
        val dataPart = if (cleaned.uppercase().startsWith(pidHex)) {
            cleaned.substring(pidHex.length).trim()
        } else {
            cleaned
        }

        val hexBytes = dataPart.replace(" ", "")
            .chunked(2)
            .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }

        val value = parsePidValue(pid, hexBytes)
        return PidResponse(pid, raw, value = value)
    }

    /**
     * Parse PID-specific hex bytes into engineering value.
     * Returns null if PID is unknown or data is insufficient.
     */
    private fun parsePidValue(pid: String, bytes: List<Int>): Float? {
        return when (pid.uppercase()) {
            // ---- Standard OBD Mode 01 ----
            "010D" -> bytes.getOrNull(2)?.toFloat()                      // Vehicle speed km/h
            "0105" -> bytes.getOrNull(2)?.let { it - 40f }               // Coolant temp °C
            "010F" -> bytes.getOrNull(2)?.let { it - 40f }               // Intake air temp °C
            "0142" -> bytes.drop(2).takeIf { it.size >= 2 }?.let {       // Control module voltage
                ((it[0] shl 8) or it[1]) / 1000f
            }
            "0104" -> bytes.getOrNull(2)?.let { it * 100f / 255f }      // Engine load %

            // ---- Hyundai / Ioniq 5 Mode 22 (manufacturer-specific) ----
            // Responses start with "62 XX XX" then data bytes
            "220105" -> parseTwoByteTenth(bytes, 3)                       // SOC displayed %
            "220106" -> parseTwoByteTenth(bytes, 3)                       // SOC BMS %
            "220108" -> parseTwoByteTenth(bytes, 3)                       // SOH %
            "22010A" -> parseTwoByteTenth(bytes, 3)                       // DC bus voltage V
            "22010B" -> parseTwoByteSignedTenth(bytes, 3)                 // DC bus current A
            "22010C" -> parseTwoByteTenth(bytes, 3)                       // Pack voltage V
            "22010D" -> parseTwoByteSignedTenth(bytes, 3)                 // Pack current A
            "22010E" -> parseSingleByteMillivolt(bytes, 3)                // Min cell mV
            "22010F" -> parseSingleByteMillivolt(bytes, 3)                // Max cell mV
            "220110" -> bytes.drop(3).firstOrNull()?.let { it - 40f }    // Battery temp °C
            "22B002" -> parseTwoByteUint(bytes, 3)?.toFloat()            // Odometer km
            "220111" -> bytes.drop(3).firstOrNull()?.toFloat()           // Charging state code
            "220112" -> parseTwoByteSignedTenth(bytes, 3)                 // Charging power kW
            "220113" -> parseTwoByteUint(bytes, 3)?.toFloat()            // Charge time DC min
            "220109" -> parseTwoByteTenth(bytes, 3)                       // Battery capacity kWh

            else -> {
                Timber.d("Unknown PID: $pid")
                null
            }
        }
    }

    // ---- Helpers ----

    /** Two bytes as unsigned 16-bit, divided by 10 */
    private fun parseTwoByteTenth(bytes: List<Int>, offset: Int): Float? {
        if (bytes.size < offset + 2) return null
        return ((bytes[offset] shl 8) or bytes[offset + 1]) / 10f
    }

    /** Two bytes as signed 16-bit (for current/power), divided by 10 */
    private fun parseTwoByteSignedTenth(bytes: List<Int>, offset: Int): Float? {
        if (bytes.size < offset + 2) return null
        val raw = (bytes[offset] shl 8) or bytes[offset + 1]
        val signed = if (raw > 32767) raw - 65536 else raw
        return signed / 10f
    }

    /** Two bytes as unsigned 16-bit */
    private fun parseTwoByteUint(bytes: List<Int>, offset: Int): Int? {
        if (bytes.size < offset + 2) return null
        return (bytes[offset] shl 8) or bytes[offset + 1]
    }

    /** Single byte interpreted as millivolts (for cell voltages, needs ×10 for actual mV) */
    private fun parseSingleByteMillivolt(bytes: List<Int>, offset: Int): Float? {
        if (bytes.size < offset + 1) return null
        return bytes[offset] * 10f // ELM reports in 10mV increments
    }

    /**
     * Build an ELM327 initialization sequence.
     * These commands configure the adapter before PID polling begins.
     */
    fun initializationCommands(): List<String> = listOf(
        "ATZ",           // Reset adapter
        "ATE0",          // Echo off
        "ATL0",          // Linefeeds off
        "ATS0",          // Spaces off (compact responses)
        "ATH0",          // Headers off
        "ATSP6",         // Set protocol to CAN 11-bit 500k (standard for modern Hyundai)
        "0100"           // Probe supported PIDs to confirm connection
    )
}
