package com.ioniq.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RfcommConnectTimeoutTest {
    @Test
    fun timeoutClosesSocketAndUnblocksConnect() {
        val connectEntered = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)

        val failure = runCatching {
            runBlocking {
                connectWithSocketTimeout(
                    timeoutMs = 100,
                    closeSocket = { socketClosed.countDown() },
                    connect = {
                        connectEntered.countDown()
                        if (!socketClosed.await(2, TimeUnit.SECONDS)) {
                            throw AssertionError("watchdog did not close the socket")
                        }
                        throw IOException("socket closed")
                    }
                )
            }
        }.exceptionOrNull()

        assertTrue("blocking connect was entered", connectEntered.await(1, TimeUnit.SECONDS))
        assertTrue("watchdog closed the socket", socketClosed.await(1, TimeUnit.SECONDS))
        assertTrue("timeout is reported", failure is RfcommConnectTimeoutException)
    }

    @Test
    fun successfulConnectDoesNotCloseSocket() = runBlocking {
        var closed = false

        connectWithSocketTimeout(
            timeoutMs = 1_000,
            closeSocket = { closed = true },
            connect = { }
        )

        assertTrue("successful socket remains open", !closed)
    }
}
