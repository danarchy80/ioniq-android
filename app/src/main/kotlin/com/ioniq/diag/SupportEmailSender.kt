package com.ioniq.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ioniq.BuildConfig
import com.ioniq.data.model.VehicleTelemetry
import timber.log.Timber

/**
 * Composes and fires a support email via ACTION_SENDTO.
 * The diagnostic bundle is assembled by [SupportEmailCollector] and attached as text.
 */
object SupportEmailSender {

    private const val DEFAULT_SUPPORT_EMAIL = "support@ioniq.app"
    private const val SUBJECT = "Ioniq OBD-II Support Request"

    /**
     * Launch the user's email client with a pre-filled support email.
     * Falls back to the hardcoded default if BuildConfig.SUPPORT_EMAIL is empty.
     */
    fun launch(context: Context, telemetry: VehicleTelemetry? = null) {
        val body = try {
            SupportEmailCollector.collect(context, telemetry)
        } catch (e: Exception) {
            Timber.e(e, "Failed to collect diagnostics")
            "[Error collecting diagnostics: ${e.message}]"
        }

        val destination = BuildConfig.SUPPORT_EMAIL
            .takeIf { it.isNotBlank() && it != "unconfigured" }
            ?: DEFAULT_SUPPORT_EMAIL

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$destination")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(destination))
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            putExtra(Intent.EXTRA_TEXT, buildEmailBody(body))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Timber.w("No email client available to send support email")
            // Fallback: copy to clipboard? User can still screenshot the diagnostics.
            // For now we let the UI handle this case.
            throw android.content.ActivityNotFoundException("No email client installed")
        }
    }

    private fun buildEmailBody(diagnostics: String): String {
        return """
        ── Please describe your issue above this line ──

        ─────────── DIAGNOSTIC DATA (below) ───────────
        $diagnostics
        """.trimIndent()
    }
}
