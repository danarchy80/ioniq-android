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

    // ---- UDS DID parsers (OBDb signal set formats) ----

    /**
     * One 32-cell voltage block (DIDs 0102/0103/0104/010A/010B/010C).
     * Message: 62 <DID hi> <DID lo> <filler> then 32 × 8-bit cells, V = byte/50.
     * Returns cell voltages in mV (zero/absent cells dropped).
     */
    fun parseCellBlock(raw: String?): List<Int> {
        if (isBad(raw)) return emptyList()
        val cleaned = cleanResponse(raw!!)
        // Strip service+DID header (62 01 02 = 3 bytes)
        val hex = cleaned.replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        val bytes = toIntList(hex)
        return bytes.drop(1).take(32)
            .map { it * 20 }            // byte/50 volts → mV = byte*20
            .filter { it > 0 }
    }

    data class AuxBattery(
        val voltage: Float?,           // V
        val current: Float?,           // A (signed, + = charging)
        val soc: Float?,               // %
        val temp: Float?               // °C
    )

    /**
     * 12V aux battery block (DID E011, hdr 7E5).
     * Data offsets (after 62 E0 11): current @15 (s16/100), SOC @17 (u8),
     * voltage @18 (u16/1000), temp @20 (u16/2 − 40).
     */
    fun parseAuxBattery(raw: String?): AuxBattery? {
        if (isBad(raw)) return null
        val cleaned = cleanResponse(raw!!)
        val hex = cleaned.replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        val d = toIntList(hex)
        if (d.size < 22) return null
        val curRaw = (d[15] shl 8) or d[16]
        val cur = (if (curRaw > 32767) curRaw - 65536 else curRaw) / 100f
        val soc = d[17].toFloat()
        val volt = ((d[18] shl 8) or d[19]) / 1000f
        val temp = ((d[20] shl 8) or d[21]) / 2f - 40f
        return AuxBattery(voltage = volt, current = cur, soc = soc, temp = temp)
    }

    data class Tpms(
        val flPsi: Float, val frPsi: Float, val rlPsi: Float, val rrPsi: Float,
        val flTemp: Float, val frTemp: Float, val rlTemp: Float, val rrTemp: Float
    )

    /**
     * TPMS block (DID C00B, hdr 7A0).
     * Data offsets (after 62 C0 0B): FL p@1 t@2, FR p@5 t@6, RL p@9 t@10, RR p@13 t@14.
     * Pressure psi = byte/5; temp °C = byte − 40.
     */
    fun parseTpms(raw: String?): Tpms? {
        if (isBad(raw)) return null
        val cleaned = cleanResponse(raw!!)
        val hex = cleaned.replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        val d = toIntList(hex)
        if (d.size < 15) return null
        return Tpms(
            flPsi = d[1] / 5f,  flTemp = d[2] - 40f,
            frPsi = d[5] / 5f,  frTemp = d[6] - 40f,
            rlPsi = d[9] / 5f,  rlTemp = d[10] - 40f,
            rrPsi = d[13] / 5f, rrTemp = d[14] - 40f
        )
    }

    // ---- Charging session DIDs (E001–E003, hdr 744 → rax 74C) ----

    data class ChargeSession(
        val obcDcVoltage: Float?,     // V (on-board charger DC out)
        val obcDcCurrent: Float?,     // A
        val estimatedPowerKw: Float?  // kW (signed; negative = discharging)
    )

    /** E001: OBC DC voltage @data16 (/10), current @data18 (/10), est. power @data25 (*10 W, signed). */
    fun parseChargeController(raw: String?): ChargeSession? {
        val d = udsData(raw) ?: return null
        if (d.size < 27) return null
        val estRaw = (d[25] shl 8) or d[26]
        val estSigned = if (estRaw > 32767) estRaw - 65536 else estRaw
        return ChargeSession(
            obcDcVoltage = ((d[16] shl 8) or d[17]) / 10f,
            obcDcCurrent = ((d[18] shl 8) or d[19]) / 10f,
            estimatedPowerKw = estSigned * 10f / 1000f
        )
    }

    data class EvseInfo(
        val maxVoltage: Float?,       // V the EVSE offered
        val maxCurrent: Float?,       // A the EVSE offered
        val maxPowerKw: Float?,       // kW the EVSE offered
        val sessionEnergyKwh: Float?  // kWh delivered this session
    )

    /** E002: EVSE max V @data6 (/10), max A @data8 (/10), delivered kWh @data20 (/100), max W @data22 (*10). */
    fun parseEvseInfo(raw: String?): EvseInfo? {
        val d = udsData(raw) ?: return null
        if (d.size < 24) return null
        return EvseInfo(
            maxVoltage = ((d[6] shl 8) or d[7]) / 10f,
            maxCurrent = ((d[8] shl 8) or d[9]) / 10f,
            maxPowerKw = ((d[22] shl 8) or d[23]) * 10f / 1000f,
            sessionEnergyKwh = ((d[20] shl 8) or d[21]) / 100f
        )
    }

    data class ChargeTargets(
        val hvTargetVoltage: Float?,  // V the BMS is requesting
        val hvTargetCurrent: Float?,  // A the BMS is requesting
        val acTimeRemainingMin: Int,  // min after plug-in (AC)
        val dcTimeRemainingMin: Int   // min after plug-in (DC)
    )

    /** E003: BMS target V @data21 (/10), target A @data23 (/10), AC time @data39, DC time @data41 (min). */
    fun parseChargeTargets(raw: String?): ChargeTargets? {
        val d = udsData(raw) ?: return null
        if (d.size < 43) return null
        return ChargeTargets(
            hvTargetVoltage = ((d[21] shl 8) or d[22]) / 10f,
            hvTargetCurrent = ((d[23] shl 8) or d[24]) / 10f,
            acTimeRemainingMin = (d[39] shl 8) or d[40],
            dcTimeRemainingMin = (d[41] shl 8) or d[42]
        )
    }

    // ---- Battery health / lifetime DIDs (0101, 0111 — hdr 7E4) ----

    /** 0101 @7E4: HV isolation resistance @data54 (u16, kΩ). */
    fun parseIsolationResistance(raw: String?): Int? {
        val d = udsData(raw) ?: return null
        if (d.size < 56) return null
        return (d[54] shl 8) or d[55]
    }

    data class LifetimeCharging(
        val acCount: Long, val dcCount: Long,
        val acEnergyKwh: Long, val dcEnergyKwh: Long
    )

    /** 0111 @7E4: AC count @data1, DC count @data5, AC kWh @data9, DC kWh @data13 (all u32). */
    fun parseLifetimeCharging(raw: String?): LifetimeCharging? {
        val d = udsData(raw) ?: return null
        if (d.size < 17) return null
        fun u32(i: Int) = ((d[i].toLong() shl 24) or (d[i + 1].toLong() shl 16) or
            (d[i + 2].toLong() shl 8) or d[i + 3].toLong())
        return LifetimeCharging(
            acCount = u32(1), dcCount = u32(5),
            acEnergyKwh = u32(9), dcEnergyKwh = u32(13)
        )
    }

    /**
     * VIN from a UDS response (e.g. DID F190 @ 7E2): strips the 62 <DID>
     * header, decodes payload bytes as ASCII, keeps only alphanumerics.
     */
    fun extractVin(raw: String?): String? {
        val d = udsData(raw) ?: return null
        val s = d.map { it.toChar() }.joinToString("").filter { it in 'A'..'Z' || it in '0'..'9' }
        return s.takeIf { it.isNotEmpty() }
    }

    /** Strip the 62 <DID hi> <DID lo> header, returning the payload bytes. */
    private fun udsData(raw: String?): List<Int>? {
        if (isBad(raw)) return null
        val hex = cleanResponse(raw!!).replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        return toIntList(hex)
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
            .replace(">", "").replace(Regex("\\d+:"), "").trim()

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
