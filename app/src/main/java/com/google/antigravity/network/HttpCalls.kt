package com.google.antigravity.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** Cancel both connection and blocking body reads when the owning turn stops. */
internal suspend inline fun <T> Call.useCancellable(block: (Response) -> T): T {
    currentCoroutineContext().ensureActive()
    val call = this@useCancellable
    val cancellation = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        return call.execute().use { block(it) }
    } catch (e: java.io.IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        cancellation.cancel()
    }
}
