package com.softwaremine.dps.data.android.common

import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Parsing and validation of tool arguments.
 *
 * ## Purpose
 * Tool arguments arrive as `Map<String, String>` because they originate from a
 * language model, which produces text. Every tool therefore needs the same
 * conversions — text to time, text to boolean, required-argument checks — and
 * without a shared home each tool grows its own slightly different version.
 *
 * The differences are where the bugs live: one tool accepting `"true"` but not
 * `"yes"`, another treating a missing end time as an error where a third
 * defaults it.
 *
 * ## Design: failures are values, never exceptions
 * Every function here returns either a parsed value or a [ToolResult.Failure]
 * ready to return. Models produce malformed arguments routinely — that is an
 * expected outcome, not a defect, and it must not travel as an exception
 * through a layer contractually forbidden from throwing.
 *
 * ## Time formats accepted
 * Two, deliberately:
 *
 * 1. **Epoch milliseconds** — unambiguous, no timezone question, and what the
 *    Calendar Provider itself stores.
 * 2. **ISO-8601 local date-time** (`2026-08-06T14:30`) — what a model produces
 *    when asked for a time, resolved against the supplied zone.
 *
 * Natural language ("tomorrow at 4") is deliberately **not** parsed here. That
 * is the model's job — it has the conversation and today's date; a regex in the
 * tool layer would guess worse and silently.
 *
 * ## Dependencies
 * `java.time` (available from API 26, our minSdk) and domain tool types.
 * No Android imports.
 */
internal object ToolArguments {

    /** Wraps a parsed value or the failure to return instead. */
    sealed interface Parsed<out T> {
        data class Value<T>(val value: T) : Parsed<T>
        data class Invalid(val failure: ToolResult.Failure) : Parsed<Nothing>
    }

    private fun invalid(message: String): Parsed.Invalid =
        Parsed.Invalid(ToolResult.Failure(reason = message, retryable = false))

    /** A required argument, or a failure naming what was missing. */
    fun required(call: ToolCall, key: String): Parsed<String> {
        val value = call.argument(key)?.trim()
        return if (value.isNullOrEmpty()) {
            invalid("Missing required argument '$key'.")
        } else {
            Parsed.Value(value)
        }
    }

    /**
     * A time argument as epoch milliseconds.
     *
     * Accepts epoch millis or ISO-8601 local date-time. [zone] resolves the
     * latter and defaults to the device zone.
     */
    fun time(call: ToolCall, key: String, zone: ZoneId): Parsed<Long> {
        val raw = call.argument(key)?.trim()
        if (raw.isNullOrEmpty()) return invalid("Missing required argument '$key'.")
        return parseTime(raw, key, zone)
    }

    /** As [time], but returns `null` when absent rather than failing. */
    fun optionalTime(call: ToolCall, key: String, zone: ZoneId): Parsed<Long?> {
        val raw = call.argument(key)?.trim()
        if (raw.isNullOrEmpty()) return Parsed.Value(null)
        return when (val parsed = parseTime(raw, key, zone)) {
            is Parsed.Value -> Parsed.Value(parsed.value)
            is Parsed.Invalid -> parsed
        }
    }

    private fun parseTime(raw: String, key: String, zone: ZoneId): Parsed<Long> {
        // Epoch millis first: unambiguous and cheapest to recognise.
        raw.toLongOrNull()?.let { millis ->
            // A plausibility window. A model asked for a timestamp will
            // occasionally emit epoch *seconds*, which would otherwise be
            // silently scheduled in 1970 — a reminder that never fires and
            // gives no clue why.
            if (millis < MIN_PLAUSIBLE_EPOCH_MILLIS) {
                return invalid(
                    "'$key' looks like seconds rather than milliseconds. " +
                        "Provide epoch milliseconds or an ISO-8601 date-time.",
                )
            }
            return Parsed.Value(millis)
        }

        return try {
            val local = LocalDateTime.parse(raw)
            Parsed.Value(local.atZone(zone).toInstant().toEpochMilli())
        } catch (parseFailure: DateTimeParseException) {
            invalid(
                "'$key' is not a valid time. Use epoch milliseconds or " +
                    "ISO-8601 such as 2026-08-06T14:30.",
            )
        }
    }

    /**
     * A boolean argument.
     *
     * Generous in what it accepts: models express affirmatives several ways and
     * rejecting `"yes"` because the tool wanted `"true"` is a failure the user
     * experiences as the assistant being obtuse.
     */
    fun boolean(call: ToolCall, key: String, default: Boolean = false): Boolean =
        when (call.argument(key)?.trim()?.lowercase()) {
            "true", "yes", "1", "y" -> true
            "false", "no", "0", "n" -> false
            else -> default
        }

    /** An integer argument, or [default] when absent or unparseable. */
    fun int(call: ToolCall, key: String, default: Int): Int =
        call.argument(key)?.trim()?.toIntOrNull() ?: default

    /**
     * Resolves a timezone argument, falling back to the device zone.
     *
     * An unrecognised zone falls back rather than failing: scheduling in the
     * device's zone is far closer to the user's intent than refusing outright.
     */
    fun zone(call: ToolCall, key: String = "timezone"): ZoneId {
        val raw = call.argument(key)?.trim()
        if (raw.isNullOrEmpty()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(raw) }.getOrElse { ZoneId.systemDefault() }
    }

    /** Validates that [end] is after [start]. */
    fun requireOrdered(start: Long, end: Long): Parsed<Unit> =
        if (end <= start) {
            invalid("End time must be after start time.")
        } else {
            Parsed.Value(Unit)
        }

    /** Validates that [millis] is in the future, allowing small clock skew. */
    fun requireFuture(millis: Long, now: Long, key: String): Parsed<Unit> =
        if (millis <= now - FUTURE_TOLERANCE_MILLIS) {
            invalid("'$key' is in the past.")
        } else {
            Parsed.Value(Unit)
        }

    /** Formats epoch millis for a user-facing summary. */
    fun describe(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()

    /** 2001-09-09. Anything earlier is almost certainly seconds mistaken for millis. */
    private const val MIN_PLAUSIBLE_EPOCH_MILLIS = 1_000_000_000_000L

    /** Tolerance for a reminder set for "now". */
    private const val FUTURE_TOLERANCE_MILLIS = 60_000L
}
