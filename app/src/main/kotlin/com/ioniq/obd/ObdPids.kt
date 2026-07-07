package com.ioniq.obd

/**
 * Ioniq 5 / Hyundai OBD-II PIDs commonly available over ELM327/CAN.
 *
 * Standard OBD mode 01 (current data) + manufacturer PIDs for
 * battery/EV-specific readings. Hyundai Ioniq 5 uses Mode 22
 * (manufacturer-specific) for many EV parameters.
 *
 * Format: mode(2 hex) + pid(2-4 hex)
 * Response: mode+0x40 prefix + pid + data bytes
 */
object ObdPids {

    // ---- Standard OBD Mode 01 ----
    const val ENGINE_LOAD      = "0104"
    const val COOLANT_TEMP     = "0105"
    const val INTAKE_AIR_TEMP  = "010F"
    const val VEHICLE_SPEED    = "010D"
    const val BATTERY_VOLTAGE  = "0142"    // Standard OBD control module voltage
    const val RUNTIME          = "011F"
    const val CONTROL_MODULE_VOLTAGE = "0142"

    // ---- Hyundai / Ioniq EV-specific (Mode 22) ----
    // Service 22 = manufacturer-specific data request
    // Response: Service 62 + PID + data

    /** Battery state of charge (displayed %) */
    const val SOC_DISPLAY      = "220105"

    /** Battery state of charge (BMS raw, more precise) */
    const val SOC_BMS          = "220106"

    /** State of health (SOH) */
    const val SOH              = "220108"

    /** DC bus voltage */
    const val DC_BUS_VOLTAGE   = "22010A"

    /** DC bus current */
    const val DC_BUS_CURRENT   = "22010B"

    /** Battery pack voltage */
    const val PACK_VOLTAGE     = "22010C"

    /** Battery pack current */
    const val PACK_CURRENT     = "22010D"

    /** Min cell voltage */
    const val CELL_VOLTAGE_MIN = "22010E"

    /** Max cell voltage */
    const val CELL_VOLTAGE_MAX = "22010F"

    /** Individual cell voltages (array, multi-line response) */
    const val CELL_VOLTAGES    = "220201"

    /** Battery temperatures (pack, module) */
    const val BATTERY_TEMP     = "220110"

    /** Charging inlet temperature */
    const val INLET_TEMP       = "220112"

    /** Ambient temperature */
    const val AMBIENT_TEMP     = "220113"

    /** Odometer */
    const val ODOMETER         = "22B002"

    /** Charging state */
    const val CHARGING_STATE   = "220111"

    /** Charging power (kW) */
    const val CHARGING_POWER   = "220114"

    /** Remaining charging time (minutes) — DC fast charge */
    const val CHARGE_TIME_DC   = "220115"

    /** Battery energy capacity (kWh) — total nominal */
    const val BATTERY_CAPACITY = "220109"

    /** Cumulative energy charged (kWh) */
    const val CUMULATIVE_ENERGY_CHARGED = "22B00A"

    /** Cumulative energy discharged (kWh) */
    const val CUMULATIVE_ENERGY_DISCHARGED = "22B00B"

    /**
     * Parse standard OBD hex response to readable value.
     * Example: "41 0C 0A F0" -> speed in km/h
     */
    fun parseStandardOBD(pid: String, raw: String): Float? {
        val bytes = raw.replace(" ", "")
            .chunked(2)
            .map { it.toInt(16) }
            .drop(2) // strip mode+0x40 and pid echoes

        if (bytes.size < 1) return null

        return when (pid) {
            "010D" -> bytes[0].toFloat()                              // speed km/h
            "0105" -> bytes[0] - 40f                                  // coolant ºC
            "010F" -> bytes[0] - 40f                                  // intake air ºC
            "0142" -> ((bytes[0] shl 8) or bytes[1]) / 1000f          // control module V
            "0104" -> bytes[0] / 2.55f                                // load %
            else -> null
        }
    }

    fun parseSoc(hexBytes: List<Int>): Float? {
        if (hexBytes.size < 2) return null
        return ((hexBytes[0] shl 8) or hexBytes[1]) / 10f
    }
}
