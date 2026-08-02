package com.softwaremine.dps.core.result

import com.softwaremine.dps.core.error.DpsError

/**
 * The result of an operation that is expected to be able to fail.
 *
 * ## Purpose
 * On-device AI fails in ways cloud AI does not, and almost all of those failures
 * are *normal* conditions rather than bugs: the model is not downloaded yet, the
 * device is out of storage, the native library is missing for this ABI, the
 * conversation exceeded the context window. Every one of these must be rendered
 * to the user as a state, not surfaced as a crash.
 *
 * Modelling them as a return type rather than as exceptions means the compiler
 * — not code review — enforces that callers handle them. Exceptions remain
 * reserved for genuine programming errors, which keeps the two categories from
 * blurring into each other.
 *
 * ## Responsibilities
 * - Carry either a value or a [DpsError], never both and never neither.
 * - Provide composition operators so call sites stay linear and readable.
 *
 * ## Future extensions
 * Day 03 tool execution returns `DpsResult<ToolOutcome>`; the same type flows
 * through unchanged.
 *
 * ## Dependencies
 * [DpsError] only. No Android, no coroutines.
 *
 * @see DpsError for the failure taxonomy.
 */
sealed interface DpsResult<out T> {

    /** The operation produced [value]. */
    data class Success<out T>(val value: T) : DpsResult<T>

    /** The operation failed with [error]. */
    data class Failure(val error: DpsError) : DpsResult<Nothing>

    companion object {
        /** Convenience for the common `DpsResult.Success(Unit)` case. */
        val Ok: DpsResult<Unit> = Success(Unit)
    }
}

/** `true` when this is a [DpsResult.Success]. */
val DpsResult<*>.isSuccess: Boolean get() = this is DpsResult.Success

/** `true` when this is a [DpsResult.Failure]. */
val DpsResult<*>.isFailure: Boolean get() = this is DpsResult.Failure

/** The value on success, or `null` on failure. */
fun <T> DpsResult<T>.getOrNull(): T? = when (this) {
    is DpsResult.Success -> value
    is DpsResult.Failure -> null
}

/** The error on failure, or `null` on success. */
fun <T> DpsResult<T>.errorOrNull(): DpsError? = when (this) {
    is DpsResult.Success -> null
    is DpsResult.Failure -> error
}

/** The value on success, or [fallback] on failure. */
fun <T> DpsResult<T>.getOrDefault(fallback: T): T = when (this) {
    is DpsResult.Success -> value
    is DpsResult.Failure -> fallback
}

/** Transforms a successful value, propagating failure untouched. */
inline fun <T, R> DpsResult<T>.map(transform: (T) -> R): DpsResult<R> = when (this) {
    is DpsResult.Success -> DpsResult.Success(transform(value))
    is DpsResult.Failure -> this
}

/** Chains another fallible operation, propagating the first failure. */
inline fun <T, R> DpsResult<T>.flatMap(transform: (T) -> DpsResult<R>): DpsResult<R> =
    when (this) {
        is DpsResult.Success -> transform(value)
        is DpsResult.Failure -> this
    }

/** Replaces the error on failure, leaving success untouched. */
inline fun <T> DpsResult<T>.mapError(transform: (DpsError) -> DpsError): DpsResult<T> =
    when (this) {
        is DpsResult.Success -> this
        is DpsResult.Failure -> DpsResult.Failure(transform(error))
    }

/** Runs [action] on success and returns this unchanged. */
inline fun <T> DpsResult<T>.onSuccess(action: (T) -> Unit): DpsResult<T> {
    if (this is DpsResult.Success) action(value)
    return this
}

/** Runs [action] on failure and returns this unchanged. */
inline fun <T> DpsResult<T>.onFailure(action: (DpsError) -> Unit): DpsResult<T> {
    if (this is DpsResult.Failure) action(error)
    return this
}

/** Collapses both branches into a single value. */
inline fun <T, R> DpsResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (DpsError) -> R,
): R = when (this) {
    is DpsResult.Success -> onSuccess(value)
    is DpsResult.Failure -> onFailure(error)
}

/**
 * Bridges a throwing API into [DpsResult].
 *
 * Used at the edges of the system — JNI calls, file I/O, HTTP — where the
 * underlying API is exception-based and we must convert once, at the boundary,
 * rather than letting exceptions leak inward.
 *
 * [kotlin.coroutines.cancellation.CancellationException] is deliberately
 * rethrown: swallowing it would break structured concurrency, turning a
 * cancelled generation into a spurious error state.
 */
inline fun <T> dpsCatching(
    onError: (Throwable) -> DpsError,
    block: () -> T,
): DpsResult<T> = try {
    DpsResult.Success(block())
} catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    DpsResult.Failure(onError(throwable))
}
