package com.helios.subly.sdk.data.translate

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal `Task<T>.await()` shim so we don't pull in
 * `kotlinx-coroutines-play-services` just for one call site.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    if (isComplete) {
        val ex = exception
        if (ex == null) {
            @Suppress("UNCHECKED_CAST")
            if (isCanceled) cont.cancel() else cont.resume(result as T)
        } else {
            cont.resumeWithException(ex)
        }
        return@suspendCancellableCoroutine
    }
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
