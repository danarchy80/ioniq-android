package com.ioniq.obd

import android.content.SharedPreferences
import timber.log.Timber

/**
 * Full IONIQ 5 DID map (Mode 22) organized by ECU, reverse-engineered from
 * the GJSoft IONIQ 5 OBD2 Monitor app and cross-checked against the OBDb
 * Hyundai-IONIQ-5 signal set.
 *
 * Pair each DID with its ECU's send header (see [EcuConfig] / ObdPids HDR_*).
 */
object IoniqPids {

    /** Battery Management System (hdr 7E4 → rx 7EC) */
    object Bms {
        const val MAIN = "220101"            // SOC, V, A, temps, cells summary, cumulative stats
        const val CELLS_1 = "220102"        // cells 1–32
        const val CELLS_2 = "220103"        // cells 33–64
        const val CELLS_3 = "220104"        // cells 65–96
        const val STATUS = "220105"         // SOH, power limits, thermal management
        const val CHARGE_STATS = "220106"   // charge stats + thermal mode
        const val CELLS_4 = "22010A"        // cells 97–128
        const val CELLS_5 = "22010B"        // cells 129–160
        const val CELLS_6 = "22010C"        // cells 161–192
        const val LIFETIME = "220111"       // lifetime AC/DC session counts + energy
    }

    /** Vehicle Control Unit (hdr 7E2 → rx 7EA) */
    object Vcu {
        const val DRIVE_STATUS = "220101"   // gear, ready, speed, pedals, torque, RPM
    }

    /** Motor / Inverter ECU (hdr 7E3 → rx 7EB) */
    object Motor {
        const val MOTOR_DATA = "220101"     // RPM, stator/inverter temps, torque, DC link
    }

    /** HVAC (hdr 7B3 → rx 7BB) */
    object Hvac {
        const val CLIMATE = "220100"        // compressor, cabin/outside/evap temps, pressures
    }

    /** Instrument Cluster (hdr 7C6 → rx 7CE) */
    object Cluster {
        const val MAIN = "22B002"           // display speed, displayed SOC, odometer
        const val TRIPS = "22B00C"          // trip A/B distance + consumption
    }

    /** Body Gateway / Lighting (hdr 770 → rx 778, extended session) */
    object Body {
        const val DOORS = "22BC03"          // door/hood/tailgate bit flags
        const val LIGHTS_BODY = "22BC06"    // brake lights, headlamps, fog
        const val LIGHTS = "22BC09"         // additional lighting state
    }

    /** Stability Control / Dynamics (hdr 730 → rx 738) — little-endian payload */
    object Esc {
        const val DYNAMICS = "220101"       // wheel speeds, steering angle, accelerations
    }

    /** Integrated Charging Control Unit (hdr 7E5 → rx 7ED) */
    object Iccu {
        const val OBC = "22E002"            // on-board charger DC V/A
        const val LDC_LEGACY = "22E011"     // 12V DC-DC — pre-facelift
        const val LDC_FACELIFT = "22E101"   // 12V DC-DC — facelift
        const val OBC_AC = "22E021"         // OBC AC side
    }

    /** Vehicle Charging Management System (hdr 744 → rx 74C, extended session) */
    object Vcms {
        const val CHARGE_CONTROLLER = "22E001"
        const val EVSE_INFO = "22E002"
        const val CHARGE_TARGETS = "22E003"
    }
}

/**
 * ICCU 12V DID facelift detection.
 *
 * Pre-facelift IONIQ 5 answers the LDC block on DID E011; facelift cars
 * answer on E101. We try the remembered candidate first, then the other,
 * and persist the winner per VIN in SharedPreferences.
 */
object IccuPids {

    private const val PREF_KEY_PREFIX = "iccu_ldc_did_"

    /**
     * Ordered DID candidates to try for the 12V LDC block.
     * The previously-successful DID (if any) comes first.
     */
    fun candidates(prefs: SharedPreferences, vin: String?): List<String> {
        val remembered = vin?.let { prefs.getString(PREF_KEY_PREFIX + it, null) }
        return listOfNotNull(remembered) +
            listOf(IoniqPids.Iccu.LDC_LEGACY, IoniqPids.Iccu.LDC_FACELIFT)
                .filter { it != remembered }
    }

    /** Remember which LDC DID worked for this VIN. */
    fun noteSuccess(prefs: SharedPreferences, vin: String?, did: String) {
        if (vin.isNullOrBlank()) {
            Timber.d("IccuPids: no VIN, not persisting successful DID %s", did)
            return
        }
        prefs.edit().putString(PREF_KEY_PREFIX + vin, did).apply()
        Timber.d("IccuPids: VIN %s uses LDC DID %s", vin, did)
    }
}
