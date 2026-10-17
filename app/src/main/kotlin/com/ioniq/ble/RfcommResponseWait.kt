package com.ioniq.ble

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Await an adapter response while preserving normal coroutine timeout/cancellation semantics. */
internal suspend fun awaitRfcommResponse(
    pendingResponse: Deferred<String?>,
    timeoutMs: Long
): String? = withTimeoutOrNull(timeoutMs) {
    pendingResponse.await()
}
