package com.ioniq.obd

import timber.log.Timber

/**
 * Parsers for the non-battery vehicle ECUs: VCU, Motor, HVAC, Cluster,
 * Body and ESC.
 *
 * Byte offsets are payload-relative: they index the message AFTER the
 * 3-byte `62 <DID hi> <DID lo>` header is stripped. All multi-byte values
 * are big-endian EXCEPT the ESC block, which is little-endian.
 */
object VehicleParsers {

    // ---- VCU drive status (DID 0101 @ 7E2) ----

    data class VcuStatus(
        val gear: Int?,                 // byte 0: 0=P, 1=R, 2=N, 3=D
        val ready: Boolean?,            // byte 1: 0=off, 1=ready
        val speedKmh: Float?,           // bytes 2-3 ÷ 10
        val accelPedalPct: Float?,      // byte 4 ÷ 2
        val brakePedal: Boolean?,       // byte 5: 0/1
        val torqueRequestNm: Float?,    // bytes 6-7 ÷ 10 (signed)
        val torqueOutputNm: Float?,     // bytes 8-9 ÷ 10 (signed)
        val motorRpm: Int?,             // bytes 10-11 (signed)
        val maxDrivePowerKw: Float?,    // bytes 12-13 ÷ 10
        val maxRegenPowerKw: Float?     // bytes 14-15 ÷ 10
    )

    fun parseVcu(raw: String?): VcuStatus? {
        val d = udsData(raw) ?: return null
        if (d.size < 16) {
            Timber.d("VCU response too short: %d bytes", d.size)
            return null
        }
        return VcuStatus(
            gear = d[0],
            ready = d[1] == 1,
            speedKmh = getUint16(d, 2) / 10f,
            accelPedalPct = d[4] / 2f,
            brakePedal = d[5] != 0,
            torqueRequestNm = getInt16(d, 6) / 10f,
            torqueOutputNm = getInt16(d, 8) / 10f,
            motorRpm = getInt16(d, 10),
            maxDrivePowerKw = getUint16(d, 12) / 10f,
            maxRegenPowerKw = getUint16(d, 14) / 10f
        )
    }

    // ---- Motor data (DID 0101 @ 7E3) ----

    data class MotorData(
        val rpm: Int?,                  // bytes 0-1 (signed)
        val statorTempC: Float?,        // byte 2 (signed)
        val inverterTempC: Float?,      // byte 3 (signed)
        val torqueNm: Float?,           // bytes 4-5 ÷ 10 (signed)
        val dcLinkVoltageV: Float?,     // bytes 6-7 ÷ 10
        val dcLinkCurrentA: Float?,     // bytes 8-9 ÷ 10 (signed)
        val phaseCurrentA: Float?       // bytes 10-11 ÷ 10
    )

    fun parseMotor(raw: String?): MotorData? {
        val d = udsData(raw) ?: return null
        if (d.size < 12) {
            Timber.d("Motor response too short: %d bytes", d.size)
            return null
        }
        return MotorData(
            rpm = getInt16(d, 0),
            statorTempC = getSignedByte(d, 2),
            inverterTempC = getSignedByte(d, 3),
            torqueNm = getInt16(d, 4) / 10f,
            dcLinkVoltageV = getUint16(d, 6) / 10f,
            dcLinkCurrentA = getInt16(d, 8) / 10f,
            phaseCurrentA = getUint16(d, 10) / 10f
        )
    }

    // ---- HVAC climate (DID 0100 @ 7B3) ----

    data class HvacData(
        val compressorRpm: Int?,            // bytes 3-4 (u16)
        val cabinTempC: Float?,             // byte 5 ÷ 2 − 40
        val outsideTempC: Float?,           // byte 6 ÷ 2 − 40
        val evaporatorTempC: Float?,        // byte 7 ÷ 2 − 40
        val ventPassengerTempC: Float?,     // byte 8 ÷ 2 − 40
        val coolantInTempC: Float?,         // byte 9 ÷ 2 − 40
        val coolantOutTempC: Float?,        // byte 10 ÷ 2 − 40
        val compressorPowerKw: Float?,      // byte 11 × 0.1
        val refrigerantPressureBar: Float?, // byte 12 × 0.1
        val humidityPct: Int?,              // byte 25 (valid 1–99)
        val ventAirTempC: Float?,           // byte 27 ÷ 2 − 40
        val ventLegsTempC: Float?,          // byte 28 ÷ 2 − 40
        val compressorInletTempC: Float?    // byte 29 (signed) − 40
    )

    fun parseHvac(raw: String?): HvacData? {
        val d = udsData(raw) ?: return null
        if (d.size < 30) {
            Timber.d("HVAC response too short: %d bytes", d.size)
            return null
        }
        fun offsetTemp(i: Int) = d[i] / 2f - 40f
        return HvacData(
            compressorRpm = getUint16(d, 3),
            cabinTempC = offsetTemp(5),
            outsideTempC = offsetTemp(6),
            evaporatorTempC = offsetTemp(7),
            ventPassengerTempC = offsetTemp(8),
            coolantInTempC = offsetTemp(9),
            coolantOutTempC = offsetTemp(10),
            compressorPowerKw = d[11] * 0.1f,
            refrigerantPressureBar = d[12] * 0.1f,
            humidityPct = d[25].takeIf { it in 1..99 },
            ventAirTempC = offsetTemp(27),
            ventLegsTempC = offsetTemp(28),
            compressorInletTempC = getSignedByte(d, 29) - 40f
        )
    }

    // ---- Cluster main (DID B002 @ 7C6) ----

    data class ClusterData(
        val displaySpeedKmh: Float?,    // bytes 1-2 ÷ 100
        val displayedSoc: Float?,       // byte 5 ÷ 2
        val odometerKm: Long?,          // bytes 6-8 (u24)
        val odometerMiles: Long?,       // bytes 9-11 (u24)
        val rangeKm: Int?               // bytes 10-11 (u16) — overlaps odometer-miles field
    )

    fun parseCluster(raw: String?): ClusterData? {
        val d = udsData(raw) ?: return null
        if (d.size < 12) {
            Timber.d("Cluster response too short: %d bytes", d.size)
            return null
        }
        return ClusterData(
            displaySpeedKmh = getUint16(d, 1) / 100f,
            displayedSoc = d[5] / 2f,
            odometerKm = getUint24(d, 6),
            odometerMiles = getUint24(d, 9),
            rangeKm = getUint16(d, 10)
        )
    }

    // ---- Cluster trips (DID B00C @ 7C6) ----

    data class ClusterTrips(
        val tripADistanceKm: Float?,    // bytes 0-3 ÷ 10
        val tripBDistanceKm: Float?,    // bytes 4-7 ÷ 10
        val tripAConsumption: Float?,   // bytes 8-9 ÷ 10 (kWh/100km)
        val tripBConsumption: Float?    // bytes 10-11 ÷ 10 (kWh/100km)
    )

    fun parseClusterTrips(raw: String?): ClusterTrips? {
        val d = udsData(raw) ?: return null
        if (d.size < 12) {
            Timber.d("Cluster trips response too short: %d bytes", d.size)
            return null
        }
        return ClusterTrips(
            tripADistanceKm = getUint32(d, 0) / 10f,
            tripBDistanceKm = getUint32(d, 4) / 10f,
            tripAConsumption = getUint16(d, 8) / 10f,
            tripBConsumption = getUint16(d, 10) / 10f
        )
    }

    // ---- Body doors (DID BC03 @ 770) ----

    data class DoorState(
        val driverOpen: Boolean,
        val passengerOpen: Boolean,
        val rearLeftOpen: Boolean,
        val rearRightOpen: Boolean,
        val tailgateOpen: Boolean,
        val hoodOpen: Boolean
    )

    fun parseDoors(raw: String?): DoorState? {
        val d = udsData(raw) ?: return null
        if (d.size < 6) {
            Timber.d("Doors response too short: %d bytes", d.size)
            return null
        }
        val flags = d[4]
        return DoorState(
            driverOpen = flags bit 5,
            passengerOpen = flags bit 4,
            rearLeftOpen = flags bit 0,
            rearRightOpen = flags bit 2,
            tailgateOpen = flags bit 7,
            hoodOpen = d[5] bit 0
        )
    }

    // ---- Body lights (DID BC06 @ 770) ----

    data class LightState(
        val brakeOn: Boolean,
        val headlampOn: Boolean,
        val fogOn: Boolean,
        val highBeamOn: Boolean
    )

    fun parseLights(raw: String?): LightState? {
        val d = udsData(raw) ?: return null
        if (d.size < 8) {
            Timber.d("Lights response too short: %d bytes", d.size)
            return null
        }
        return LightState(
            brakeOn = d[4] != 0,
            headlampOn = d[6] bit 5,
            fogOn = d[6] bit 5,   // per spec: byte 6 bit5 = headlamp, bit5 = fog
            highBeamOn = (d[7] and 0x03) != 0
        )
    }

    // ---- ESC dynamics (DID 0101 @ 730) — LITTLE-ENDIAN u16 ----

    data class EscData(
        val wheelFrKmh: Float?,         // bytes 4-5 ÷ 32
        val wheelFlKmh: Float?,         // bytes 6-7 ÷ 32
        val wheelRrKmh: Float?,         // bytes 8-9 ÷ 32
        val wheelRlKmh: Float?,         // bytes 10-11 ÷ 32
        val steeringAngleDeg: Float?,   // bytes 12-13 (i16 LE ÷ 10, negated)
        val longitudinalAccelG: Float?, // bytes 14-15 (u16 LE, (v×8.354/65535)−4.177)
        val lateralAccelG: Float?       // bytes 16-17 (u16 LE, same formula)
    )

    fun parseEsc(raw: String?): EscData? {
        val d = udsData(raw) ?: return null
        if (d.size < 18) {
            Timber.d("ESC response too short: %d bytes", d.size)
            return null
        }
        fun accel(i: Int): Float = getUint16le(d, i) * 8.354f / 65535f - 4.177f
        return EscData(
            wheelFrKmh = getUint16le(d, 4) / 32f,
            wheelFlKmh = getUint16le(d, 6) / 32f,
            wheelRrKmh = getUint16le(d, 8) / 32f,
            wheelRlKmh = getUint16le(d, 10) / 32f,
            steeringAngleDeg = -getInt16le(d, 12) / 10f,
            longitudinalAccelG = accel(14),
            lateralAccelG = accel(16)
        )
    }

    // ---- Byte helpers ----

    private infix fun Int.bit(n: Int): Boolean = (this shr n) and 1 == 1

    private fun getUint16(bytes: List<Int>, i: Int): Int =
        (bytes[i] shl 8) or bytes[i + 1]

    private fun getInt16(bytes: List<Int>, i: Int): Int {
        val raw = getUint16(bytes, i)
        return if (raw > 32767) raw - 65536 else raw
    }

    private fun getUint16le(bytes: List<Int>, i: Int): Int =
        (bytes[i + 1] shl 8) or bytes[i]

    private fun getInt16le(bytes: List<Int>, i: Int): Int {
        val raw = getUint16le(bytes, i)
        return if (raw > 32767) raw - 65536 else raw
    }

    private fun getUint24(bytes: List<Int>, i: Int): Long =
        (bytes[i].toLong() shl 16) or (bytes[i + 1].toLong() shl 8) or bytes[i + 2].toLong()

    private fun getUint32(bytes: List<Int>, i: Int): Long =
        (bytes[i].toLong() shl 24) or (bytes[i + 1].toLong() shl 16) or
            (bytes[i + 2].toLong() shl 8) or bytes[i + 3].toLong()

    private fun getSignedByte(bytes: List<Int>, i: Int): Float =
        (if (bytes[i] > 127) bytes[i] - 256 else bytes[i]).toFloat()

    /** Strip the `62 <DID hi> <DID lo>` header, returning payload bytes. */
    private fun udsData(raw: String?): List<Int>? {
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
