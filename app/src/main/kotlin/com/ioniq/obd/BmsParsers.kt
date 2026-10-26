package com.ioniq.obd

import timber.log.Timber

/**
 * Parsers for the Battery Management System ECUs (hdr 7E4 → rx 7EC).
 *
 * Byte offsets are payload-relative: they index the message AFTER the
 * 3-byte `62 <DID hi> <DID lo>` header is stripped. All multi-byte values
 * are big-endian unless noted.
 */
object BmsParsers {

    // ---- BMS Main (DID 0101 @ 7E4) ----

    data class BmsMain(
        val socBms: Float?,                 // % (byte 4 ÷ 2)
        val requestedChargeCurrentA: Float?,// A (bytes 7-8 ÷ 10)
        val statusFlags: Int?,              // raw flags (byte 9)
        val currentA: Float?,               // A signed, negative = charging (bytes 10-11 ÷ 10)
        val voltageV: Float?,               // V (bytes 12-13 ÷ 10)
        val tempMaxC: Float?,               // °C (byte 14, signed)
        val tempMinC: Float?,               // °C (byte 15, signed)
        val packTempsC: List<Float>,        // T01–T05 (bytes 16-20, signed)
        val cellMaxV: Float?,               // V (byte 23 ÷ 50)
        val cellMaxNumber: Int?,            // byte 24
        val cellMinV: Float?,               // V (byte 25 ÷ 50)
        val cellMinNumber: Int?,            // byte 26
        val cellCount: Int?,                // byte 28 (valid range 136–215)
        val auxVoltage12V: Float?,          // V (byte 29 × 0.1)
        val cumulativeChargeAh: Float?,     // bytes 30-33 ÷ 10
        val cumulativeDischargeAh: Float?,  // bytes 34-37 ÷ 10
        val cumulativeChargeKwh: Float?,    // bytes 38-41 ÷ 10
        val cumulativeDischargeKwh: Float?, // bytes 42-45 ÷ 10
        val operatingTimeHours: Float?,     // bytes 46-49 ÷ 3600
        val capVoltage: Int?,               // bytes 51-52 (u16)
        val rpmRear: Int?,                  // bytes 53-54 (i16)
        val rpmFront: Int?,                 // bytes 55-56 (i16)
        val insulationResistanceKohm: Int?  // bytes 57-58 (u16, kΩ)
    )

    /** DID 0101 @ 7E4 — main battery block. */
    fun parseMain(raw: String?): BmsMain? {
        val d = udsData(raw) ?: return null
        if (d.size < 59) {
            Timber.d("BMS main response too short: %d bytes", d.size)
            return null
        }
        val cellCount = d[28].takeIf { it in 136..215 }
        return BmsMain(
            socBms = d[4] / 2f,
            requestedChargeCurrentA = getUint16(d, 7) / 10f,
            statusFlags = d[9],
            currentA = getInt16(d, 10) / 10f,
            voltageV = getUint16(d, 12) / 10f,
            tempMaxC = getSignedByte(d, 14),
            tempMinC = getSignedByte(d, 15),
            packTempsC = (16..20).map { getSignedByte(d, it) },
            cellMaxV = d[23] / 50f,
            cellMaxNumber = d[24],
            cellMinV = d[25] / 50f,
            cellMinNumber = d[26],
            cellCount = cellCount,
            auxVoltage12V = d[29] * 0.1f,
            cumulativeChargeAh = getUint32(d, 30) / 10f,
            cumulativeDischargeAh = getUint32(d, 34) / 10f,
            cumulativeChargeKwh = getUint32(d, 38) / 10f,
            cumulativeDischargeKwh = getUint32(d, 42) / 10f,
            operatingTimeHours = getUint32(d, 46) / 3600f,
            capVoltage = getUint16(d, 51),
            rpmRear = getInt16(d, 53),
            rpmFront = getInt16(d, 55),
            insulationResistanceKohm = getUint16(d, 57)
        )
    }

    // ---- BMS Status (DID 0105 @ 7E4) ----

    data class BmsStatus(
        val moduleTempC: Float?,            // byte 9 (signed)
        val availableChargePowerKw: Float?, // bytes 10-11 ÷ 10
        val maxChargePowerKw: Float?,       // bytes 16-17 ÷ 100
        val highTempMinutes: Int?,          // bytes 17-18 window (u16)
        val maxDischargePowerKw: Float?,    // bytes 18-19 ÷ 100
        val heaterTempC: Float?,            // byte 23 (signed)
        val soh: Float?,                    // bytes 25-26 ÷ 10 (%)
        val remainingEnergyKwh: Float?,     // bytes 28-29 × 2 ÷ 1000
        val displayedSoc: Float?,           // byte 31 ÷ 2 (%)
        val heaterPowerKw: Float?,          // bytes 36-37 ÷ 10
        val heaterStatus: Int?,             // byte 38
        val moduleTemp2C: Float?,           // byte 39 (signed)
        val fanStatus: Int?,                // byte 43
        val fanSpeed: Int?                  // byte 44
    )

    /** DID 0105 @ 7E4 — battery status / power limits / thermal management. */
    fun parseStatus(raw: String?): BmsStatus? {
        val d = udsData(raw) ?: return null
        if (d.size < 45) {
            Timber.d("BMS status response too short: %d bytes", d.size)
            return null
        }
        return BmsStatus(
            moduleTempC = getSignedByte(d, 9),
            availableChargePowerKw = getUint16(d, 10) / 10f,
            maxChargePowerKw = getUint16(d, 16) / 100f,
            highTempMinutes = getUint16(d, 17),
            maxDischargePowerKw = getUint16(d, 18) / 100f,
            heaterTempC = getSignedByte(d, 23),
            soh = getUint16(d, 25) / 10f,
            remainingEnergyKwh = getUint16(d, 28) * 2f / 1000f,
            displayedSoc = d[31] / 2f,
            heaterPowerKw = getUint16(d, 36) / 10f,
            heaterStatus = d[38],
            moduleTemp2C = getSignedByte(d, 39),
            fanStatus = d[43],
            fanSpeed = d[44]
        )
    }

    // ---- Shared byte helpers (payload-relative, big-endian) ----

    internal fun getUint16(bytes: List<Int>, i: Int): Int =
        (bytes[i] shl 8) or bytes[i + 1]

    internal fun getInt16(bytes: List<Int>, i: Int): Int {
        val raw = getUint16(bytes, i)
        return if (raw > 32767) raw - 65536 else raw
    }

    /** Little-endian u16 — used only by the ESC parser. */
    internal fun getUint16le(bytes: List<Int>, i: Int): Int =
        (bytes[i + 1] shl 8) or bytes[i]

    internal fun getUint32(bytes: List<Int>, i: Int): Long =
        (bytes[i].toLong() shl 24) or (bytes[i + 1].toLong() shl 16) or
            (bytes[i + 2].toLong() shl 8) or bytes[i + 3].toLong()

    internal fun getSignedByte(bytes: List<Int>, i: Int): Float =
        (if (bytes[i] > 127) bytes[i] - 256 else bytes[i]).toFloat()

    /** Strip the `62 <DID hi> <DID lo>` header, returning payload bytes. */
    internal fun udsData(raw: String?): List<Int>? {
        if (isBad(raw)) return null
        val hex = cleanResponse(raw!!).replace(Regex("^[0-9A-Fa-f]{6}\\s*"), "")
        return toIntList(hex)
    }

    private fun isBad(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val trimmed = raw.trim()
        val errorStrings = arrayOf(
            "SEARCHING", "BUS INIT", "BUS ERROR", "UNABLE TO CONNECT",
            "NO DATA", "?", "ERROR", "STOPPED"
        )
        if (errorStrings.any { trimmed.uppercase().contains(it) }) return true
        if (!Regex("[0-9A-Fa-f]{2}").containsMatchIn(cleanResponse(raw).replace(" ", ""))) return true
        return false
    }

    private fun cleanResponse(raw: String): String =
        raw.trim().replace("\r", " ").replace("\n", " ")
            .replace(">", "").replace(Regex("\\d+:"), "").trim()

    private fun toIntList(hex: String): List<Int> =
        hex.replace(" ", "").chunked(2).mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
}
