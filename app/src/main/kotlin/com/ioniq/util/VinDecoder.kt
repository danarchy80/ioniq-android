package com.ioniq.util

import timber.log.Timber

/**
 * Minimal VIN decoder for Hyundai/Kia vehicles (IONIQ 5 focus).
 *
 * Extracts manufacturer (from WMI), assembly plant, model year and build
 * country. Validation: 17 chars, no I/O/Q, valid year code at position 9
 * (0-indexed; the 10th character).
 */
object VinDecoder {

    data class VinInfo(
        val vin: String,
        val manufacturer: String?,   // "Hyundai" / "Kia" / null
        val plant: String?,          // "Ulsan" / "Montgomery" / "Anseong" / null
        val country: String?,        // "USA" / "Czech" / "Korea" / "China" / null
        val modelYear: Int?,         // e.g. 2024
        val valid: Boolean
    )

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
    private const val YEAR_BASE = 2010

    private val HYUNDAI_WMI = listOf("KMH", "KM8", "5NM", "TMA")
    private val KIA_WMI = listOf("KN", "5XY", "U5Y", "U6Y")

    private val PLANTS = mapOf('U' to "Ulsan", 'M' to "Montgomery", 'A' to "Anseong")
    private val COUNTRIES = mapOf('5' to "USA", 'T' to "Czech", 'K' to "Korea", 'L' to "China")

    fun decode(vin: String?): VinInfo? {
        if (vin.isNullOrBlank()) return null
        val v = vin.trim().uppercase()

        val valid = isValid(v)
        if (!valid) {
            Timber.d("VIN decode rejected: %s", v)
        }

        val manufacturer = when {
            HYUNDAI_WMI.any { v.startsWith(it) } -> "Hyundai"
            KIA_WMI.any { v.startsWith(it) } -> "Kia"
            else -> null
        }
        val plant = v.getOrNull(10)?.let { PLANTS[it] }
        val country = v.firstOrNull()?.let { COUNTRIES[it] }
        val modelYear = v.getOrNull(9)?.let { YEAR_CODES.indexOf(it) }
            ?.let { YEAR_BASE + it }

        return VinInfo(
            vin = v,
            manufacturer = manufacturer,
            plant = plant,
            country = country,
            modelYear = modelYear,
            valid = valid
        )
    }

    /** 17 chars, no I/O/Q, valid year code at position 9. */
    fun isValid(vin: String?): Boolean {
        if (vin.isNullOrBlank()) return false
        val v = vin.trim().uppercase()
        if (v.length != 17) return false
        if (!v.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        if (v.any { it == 'I' || it == 'O' || it == 'Q' }) return false
        val yearIdx = YEAR_CODES.indexOf(v[9])
        return yearIdx >= 0
    }
}
