package dev.hermeskotlin.core.common

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that never swallows cancellation (docs/WIDGETS-SPEC.md D26, M4): a coroutine cancelled
 * by a deadline or a newer collect must die at once, not be reported as a failed call and continue the
 * pipeline — otherwise a timed-out widget refresh would keep running and even overwrite the stored
 * snapshot it was supposed to fall back to.
 */
suspend fun <R> runCatchingCancellable(block: suspend () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
