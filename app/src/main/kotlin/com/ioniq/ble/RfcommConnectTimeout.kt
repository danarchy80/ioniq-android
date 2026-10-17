package com.ioniq.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class RfcommConnectTimeoutException(timeoutMs: Long) :
    IOException("RFCOMM socket connect timed out after ${timeoutMs}ms")

/**
 * Runs BluetoothSocket.connect() on an IO thread and closes its socket at the
 * deadline. Closing the socket is required because cancelling a coroutine does
 * not reliably interrupt Android's blocking RFCOMM connect call.
 */
internal suspend fun connectWithSocketTimeout(
    timeoutMs: Long,
    closeSocket: () -> Unit,
    connect: () -> Unit
) = coroutineScope {
    val timedOut = AtomicBoolean(false)
    val watchdog = launch {
        delay(timeoutMs)
        timedOut.set(true)
        runCatching(closeSocket)
    }

    try {
        withContext(Dispatchers.IO) { connect() }
        if (timedOut.get()) throw RfcommConnectTimeoutException(timeoutMs)
    } catch (e: CancellationException) {
        runCatching(closeSocket)
        if (timedOut.get()) throw RfcommConnectTimeoutException(timeoutMs)
        throw e
    } catch (e: Exception) {
        if (timedOut.get()) throw RfcommConnectTimeoutException(timeoutMs)
        throw e
    } finally {
        watchdog.cancel()
    }
}
