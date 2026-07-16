package com.ioniq.diag

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Thread-safe circular log buffer that captures log lines in memory for fast access
 * AND appends them to a disk session file so they survive process death / crashes.
 *
 * On Application.onCreate():
 *   1. Rotate session.log → previous_run.log (if it exists)
 *   2. Start a fresh session file
 *
 * During runtime:
 *   - Every log line is written to both: the in-memory ring buffer AND the session file
 *   - Flush ensures the buffered writer is pumped to disk
 *
 * On next crash / restart, previous_run.log contains the logs leading up to the crash,
 * and diagnostics include it alongside the current run logs.
 */
object LogBuffer {
    private const val MAX_LINES = 500

    private val memoryBuffer = ArrayDeque<String>(MAX_LINES)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var initialized = false
    private var sessionDir: File? = null
    private var writer: BufferedWriter? = null

    /** Initialize disk-backed storage — call from Application.onCreate(). */
    fun initialize(context: Context) {
        synchronized(memoryBuffer) {
            if (initialized) return

            val logDir = File(context.filesDir, "logs")
            logDir.mkdirs()

            val sessionFile = File(logDir, "session.log")
            val previousFile = File(logDir, "previous_run.log")

            // Close existing writer if any
            writer?.close()

            // Rotate: current session → previous run (so we have it after this session)
            if (sessionFile.exists() && sessionFile.length() > 0) {
                try {
                    if (previousFile.exists()) previousFile.delete()
                    sessionFile.renameTo(previousFile)
                } catch (_: Exception) {
                    // best-effort rotation
                }
            }

            // Start fresh session file
            try {
                sessionFile.createNewFile()
                writer = BufferedWriter(FileWriter(sessionFile, /* append = */ true))
                val sessionTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                writer?.write("── Session started: $sessionTs ──\n")
                writer?.flush()
            } catch (_: Exception) {
                writer = null
            }

            sessionDir = logDir
            memoryBuffer.clear()
            initialized = true
        }
    }

    /** Append a log line — writes to both in-memory ring buffer AND session file. */
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

        var stackSuffix: String? = null
        if (throwable != null) {
            stackSuffix = "  " + throwable.stackTraceToString()
                .lineSequence().take(10).joinToString("\n")
        }

        synchronized(memoryBuffer) {
            // Ring buffer — always bounded
            if (memoryBuffer.size >= MAX_LINES) memoryBuffer.removeFirst()
            memoryBuffer.addLast(line)
            if (stackSuffix != null) {
                if (memoryBuffer.size >= MAX_LINES) memoryBuffer.removeFirst()
                memoryBuffer.addLast(stackSuffix)
            }

            // Disk — append unconditionally so nothing is lost on crash.
            try {
                writer?.let {
                    it.write(line)
                    it.newLine()
                    if (stackSuffix != null) {
                        it.write(stackSuffix)
                        it.newLine()
                    }
                    it.flush()
                }
            } catch (_: Exception) {
                // Swallow — best effort, never let logging crash the app
            }
        }
    }

    /** Snapshot of the most recent in-memory log lines (newest last). */
    fun drain(): List<String> = synchronized(memoryBuffer) { memoryBuffer.toList() }

    /**
     * Load previous run's log lines (the session file that rotated on the last startup).
     * Returns null if no previous run exists or it's too old / unreadable.
     */
    fun getPreviousRunLogs(context: Context): List<String>? {
        val previousFile = File(context.filesDir, "logs/previous_run.log")
        if (!previousFile.exists() || previousFile.length() == 0L) return null
        return try {
            previousFile.readLines().takeLast(MAX_LINES).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Flush to disk immediately (call on key lifecycle events, e.g. before a critical action). */
    fun flush() {
        synchronized(memoryBuffer) {
            try {
                writer?.flush()
            } catch (_: Exception) { /* best effort */ }
        }
    }

    /** Internal Timber tree that forwards every log to the ring buffer + disk. */
    class TimberTree : timber.log.Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            LogBuffer.append(priority, tag, message, t)
        }
    }
}
