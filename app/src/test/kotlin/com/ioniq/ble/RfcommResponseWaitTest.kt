package com.ioniq.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfcommResponseWaitTest {
    @Test
    fun returnsAdapterResponse() = runBlocking {
        val pending = CompletableDeferred<String?>()
        val result = async { awaitRfcommResponse(pending, timeoutMs = 1_000) }
        pending.complete("NO DATA")

        assertEquals("NO DATA", result.await())
    }

    @Test
    fun disconnectCompletesPendingCommandImmediately() = runBlocking {
        val pending = CompletableDeferred<String?>()
        val result = async { awaitRfcommResponse(pending, timeoutMs = 5_000) }
        pending.complete(null) // Socket cleanup signals disconnection with no response.

        assertNull(result.await())
    }

    @Test
    fun timeoutReturnsNull() = runBlocking {
        val pending = CompletableDeferred<String?>()

        assertNull(awaitRfcommResponse(pending, timeoutMs = 50))
    }

    @Test
    fun callerCancellationCancelsWait() = runBlocking {
        val pending = CompletableDeferred<String?>()
        val waiter = launch { awaitRfcommResponse(pending, timeoutMs = 5_000) }
        delay(20)

        waiter.cancelAndJoin()

        assertTrue(waiter.isCancelled)
    }
}
