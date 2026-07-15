package com.ioniq.diag

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Thread-safe circular log buffer that captures recent Timber log lines.
 * Used by the support-email diagnostics to include what happened just before a failure.
 *
 * Designed to hold ~500 lines (~50KB) — enough context without ballooning the email.
 */
object LogBuffer {
    private const val MAX_LINES = 500
    private val buffer = ArrayDeque<String>(MAX_LINES)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Append a log line (thread-safe). */
    fun append(priority: Int, tag: String?, message: String, throwable: Throwable? = null) {
        val ts = timeFmt.format(Date())
        val p = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "F"
            else -> "?"
        }
        val tagStr = tag?.take(20) ?: "—"
        val line = "$ts $p/$tagStr: $message"

        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
            if (throwable != null) {
                val stack = throwable.stackTraceToString()
                    .lineSequence().take(15).joinToString("\n")
                buffer.addLast("  $stack")
            }
        }
    }

    /** Snapshot of the most recent log lines (newest last). */
    fun drain(): List<String> = synchronized(buffer) { buffer.toList() }

    /** Custom Timber tree that writes every log to the buffer. */
    class TimberTree : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            LogBuffer.append(priority, tag, message, t)
        }
    }
}
