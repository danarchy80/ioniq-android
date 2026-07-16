package com.ioniq.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ioniq.BuildConfig
import com.ioniq.data.model.VehicleTelemetry
import timber.log.Timber
import android.widget.Toast

/**
 * Composes and fires a support email via ACTION_SENDTO.
 * The diagnostic bundle is assembled by [SupportEmailCollector] and attached as text.
 * Falls back to clipboard copy when no email client is available.
 */
object SupportEmailSender {

    private const val DEFAULT_SUPPORT_EMAIL = "support@ioniq.app"
    private const val SUBJECT = "Ioniq OBD-II Support Request"

    /** Result of a launch attempt — UI layer can react accordingly. */
    sealed class Result {
        data object EmailClientLaunched : Result()
        data class CopiedToClipboard(val supportEmail: String, val fullBody: String) : Result()
    }

    /**
     * Launch the user's email client with a pre-filled support email.
     * Falls back to copying the diagnostics to clipboard if no email client is installed.
     */
    fun launch(context: Context, telemetry: VehicleTelemetry? = null): Result {
        val body = try {
            SupportEmailCollector.collect(context, telemetry)
        } catch (e: Exception) {
            Timber.e(e, "Failed to collect diagnostics")
            "[Error collecting diagnostics: ${e.message}]"
        }

        val destination = BuildConfig.SUPPORT_EMAIL
            .takeIf { it.isNotBlank() && it != "unconfigured" }
            ?: DEFAULT_SUPPORT_EMAIL

        val fullBody = buildEmailBody(body)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(destination))
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            putExtra(Intent.EXTRA_TEXT, fullBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Try sending directly first, fall back to chooser if multiple apps available
        return if (intent.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(intent)
                Result.EmailClientLaunched
            } catch (e: Exception) {
                Timber.w(e, "Email launch failed, trying chooser")
                val chooser = Intent.createChooser(intent, "Send support email").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(chooser)
                    Result.EmailClientLaunched
                } catch (e2: Exception) {
                    Timber.e(e2, "All email attempts failed — copying diagnostics to clipboard")
                    copyToClipboard(context, destination, fullBody)
                }
            }
        } else {
            Timber.w("No email client available — copying diagnostics to clipboard")
            copyToClipboard(context, destination, fullBody)
        }
    }

    private fun copyToClipboard(context: Context, destination: String, fullBody: String): Result {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipLabel = "$SUBJECT — for $destination"
        val clipText = """
            To: $destination
            Subject: $SUBJECT

            $fullBody
        """.trimIndent()
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, clipText))
            Toast.makeText(
                context,
                "No email app found — diagnostics copied to clipboard. Paste into any email to $destination",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Timber.w(e, "Could not show clipboard toast")
        }
        return Result.CopiedToClipboard(destination, fullBody)
    }

    private fun buildEmailBody(diagnostics: String): String {
        return """
        ── Please describe your issue above this line ──

        ─────────── DIAGNOSTIC DATA (below) ───────────
        $diagnostics
        """.trimIndent()
    }
}
