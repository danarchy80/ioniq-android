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
    const val BATTERY_VOLTAGE  = "0142"    // Standard OBD control module voltage (alias of CONTROL_MODULE_VOLTAGE)
    const val RUNTIME          = "011F"

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

    // ---- UDS DID blocks from OBDb/Hyundai-IONIQ-5 signal set ----
    // These DIDs live on specific ECUs and need the send header switched
    // via ATSH before the 22 <DID> request. Response = 62 <DID> + data.

    /** Full cell voltages: 6 blocks × 32 cells, 8-bit, V = byte/50 (hdr 7E4 → rax 7EC) */
    const val CELL_BLOCK_1 = "220102"   // cells 1–32
    const val CELL_BLOCK_2 = "220103"   // cells 33–64
    const val CELL_BLOCK_3 = "220104"   // cells 65–96
    const val CELL_BLOCK_4 = "22010A"   // cells 97–128
    const val CELL_BLOCK_5 = "22010B"   // cells 129–160
    const val CELL_BLOCK_6 = "22010C"   // cells 161–192

    /** 12V aux battery + DC-DC converter block (hdr 7E5 → rax 7ED) */
    const val AUX_BATTERY = "22E011"

    /** TPMS: 4× pressure (psi = byte/5) + 4× temp (°C = byte−40) (hdr 7A0 → rax 7A8) */
    const val TPMS = "22C00B"

    // ELM327 send-header switches for the ECUs above.
    const val HDR_BMS = "ATSH7E4"
    const val HDR_LDC = "ATSH7E5"
    const val HDR_TPMS = "ATSH7A0"
    const val HDR_DEFAULT = "ATSH7DF"

    // NOTE: parseStandardOBD() and parseSoc() were removed — they were dead code
    // (never called). All OBD parsing flows through ObdParser.parseResponse() /
    // ObdParser.parseSoc() which use safe mapNotNull + runCatching for hex parsing.
    // The removed parseStandardOBD had an unsafe .map { it.toInt(16) } that would
    // crash on malformed ELM327 responses (spaces, '>', '?', partial bytes).
}
