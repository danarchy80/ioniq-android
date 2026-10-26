package com.ioniq.util

import android.content.SharedPreferences
import kotlin.math.abs

/**
 * Self-measuring usable-capacity estimator.
 *
 * Records (SOC %, remaining energy kWh) pairs observed at the extremes of
 * the SOC range. Once the observed span is wide enough, the slope
 * Δenergy/ΔSOC gives the usable capacity directly — no assumed pack size.
 *
 * Rules (per spec):
 *  - span ≥ 25 %: compute slope = Δenergy / ΔSOC
 *  - span ≥ 60 %: mark the estimate "verified"
 *  - usableCapacity = slope × (100 − currentSOC) + currentRemaining
 *  - SOH correction: slope ×= currentSOH / referenceSOH
 *  - sanity bounds: capacity 25–130 kWh, slope 0.3–1.5
 *  - fallback (span too small, SOC > 9 %): remaining × 93 / (SOC − 7)
 *
 * State is persisted per-VIN in SharedPreferences.
 */
object CapacityCalibrator {

    private const val PREFS_MIN_SOC = "cap_min_soc_"
    private const val PREFS_MIN_KWH = "cap_min_kwh_"
    private const val PREFS_MAX_SOC = "cap_max_soc_"
    private const val PREFS_MAX_KWH = "cap_max_kwh_"
    private const val PREFS_REF_SOH = "cap_ref_soh_"
    private const val PREFS_CAPACITY = "cap_capacity_"
    private const val PREFS_VERIFIED = "cap_verified_"

    private const val MIN_SPAN_SLOPE = 25f     // % span needed to compute a slope
    private const val MIN_SPAN_VERIFIED = 60f  // % span to mark verified
    private const val MIN_CAPACITY_KWH = 25f
    private const val MAX_CAPACITY_KWH = 130f
    private const val MIN_SLOPE = 0.3f
    private const val MAX_SLOPE = 1.5f
    private const val FALLBACK_MIN_SOC = 9f

    /**
     * Feed one observation and return the current best estimate of usable
     * capacity in kWh (null if nothing reliable can be computed yet).
     *
     * @param socPct       current SOC % (displayed or BMS)
     * @param remainingKwh current remaining energy kWh
     * @param sohPct       optional state of health % used for slope correction
     */
    fun observe(
        prefs: SharedPreferences,
        vin: String,
        socPct: Float,
        remainingKwh: Float,
        sohPct: Float? = null
    ): Float? {
        if (socPct !in 0f..100f || remainingKwh < 0f) return null

        // Track the extreme (soc, remaining) samples
        val minSoc = prefs.getFloat(PREFS_MIN_SOC + vin, Float.MAX_VALUE)
        val minKwh = prefs.getFloat(PREFS_MIN_KWH + vin, Float.MAX_VALUE)
        val maxSoc = prefs.getFloat(PREFS_MAX_SOC + vin, -1f)
        val maxKwh = prefs.getFloat(PREFS_MAX_KWH + vin, -1f)

        val editor = prefs.edit()
        if (socPct < minSoc) {
            editor.putFloat(PREFS_MIN_SOC + vin, socPct)
            editor.putFloat(PREFS_MIN_KWH + vin, remainingKwh)
        }
        if (socPct > maxSoc) {
            editor.putFloat(PREFS_MAX_SOC + vin, socPct)
            editor.putFloat(PREFS_MAX_KWH + vin, remainingKwh)
        }

        // Reference SOH: first SOH seen for this VIN
        var refSoh = prefs.getFloat(PREFS_REF_SOH + vin, 0f)
        if (refSoh <= 0f && sohPct != null) {
            refSoh = sohPct
            editor.putFloat(PREFS_REF_SOH + vin, refSoh)
        }
        editor.apply()

        val effMinSoc = minOf(minSoc, socPct)
        val effMinKwh = if (socPct <= minSoc) remainingKwh else minKwh
        val effMaxSoc = maxOf(maxSoc, socPct)
        val effMaxKwh = if (socPct >= maxSoc) remainingKwh else maxKwh
        val span = effMaxSoc - effMinSoc

        var estimate: Float? = null
        var verified = prefs.getBoolean(PREFS_VERIFIED + vin, false)

        if (span >= MIN_SPAN_SLOPE && abs(effMaxSoc - effMinSoc) > 0.01f) {
            var slope = (effMaxKwh - effMinKwh) / span
            if (sohPct != null && refSoh > 0f) {
                slope *= sohPct / refSoh
            }
            if (slope in MIN_SLOPE..MAX_SLOPE) {
                val capacity = slope * (100f - socPct) + remainingKwh
                if (capacity in MIN_CAPACITY_KWH..MAX_CAPACITY_KWH) {
                    estimate = capacity
                    if (span >= MIN_SPAN_VERIFIED) verified = true
                    prefs.edit()
                        .putFloat(PREFS_CAPACITY + vin, capacity)
                        .putBoolean(PREFS_VERIFIED + vin, verified)
                        .apply()
                }
            }
        }

        if (estimate == null) {
            // Fallback: assume displayed 0–100 % covers ~93 % of the pack
            estimate = calibratedCapacity(prefs, vin) ?: fallback(socPct, remainingKwh)
            if (estimate != null) {
                prefs.edit().putFloat(PREFS_CAPACITY + vin, estimate).apply()
            }
        }
        return estimate
    }

    /** Last persisted estimate for this VIN (null if never computed). */
    fun calibratedCapacity(prefs: SharedPreferences, vin: String): Float? =
        prefs.getFloat(PREFS_CAPACITY + vin, -1f).takeIf { it > 0f }

    /** True once a ≥60 % SOC span has been observed with a sane slope. */
    fun isVerified(prefs: SharedPreferences, vin: String): Boolean =
        prefs.getBoolean(PREFS_VERIFIED + vin, false)

    /** Forget everything calibrated for this VIN. */
    fun reset(prefs: SharedPreferences, vin: String) {
        prefs.edit()
            .remove(PREFS_MIN_SOC + vin).remove(PREFS_MIN_KWH + vin)
            .remove(PREFS_MAX_SOC + vin).remove(PREFS_MAX_KWH + vin)
            .remove(PREFS_REF_SOH + vin).remove(PREFS_CAPACITY + vin)
            .remove(PREFS_VERIFIED + vin)
            .apply()
    }

    private fun fallback(socPct: Float, remainingKwh: Float): Float? {
        if (socPct <= FALLBACK_MIN_SOC) return null
        val capacity = remainingKwh * 93f / (socPct - 7f)
        return capacity.takeIf { it in MIN_CAPACITY_KWH..MAX_CAPACITY_KWH }
    }
}
