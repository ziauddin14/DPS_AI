package com.softwaremine.dps.domain.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outcome of executing a tool. Every outcome, typed.
 *
 * ## Purpose
 * The single return type of the tool layer. Nothing throws across this
 * boundary: an exception escaping a tool would reach the AI orchestration layer
 * with no structured way to explain itself to the user, and "the app crashed"
 * is not an answer a secretary can give.
 *
 * ## Why it is serializable
 * These results are ultimately fed back to the LLM as observations so it can
 * decide what to say. That is Day 06 work, but the shape has to be right now —
 * retrofitting serialization onto a type already threaded through several
 * layers is far more disruptive than declaring it correctly at the outset.
 *
 * Payloads are `Map<String, String>` rather than arbitrary objects for the same
 * reason: whatever a tool returns must survive being rendered into a prompt.
 *
 * ## [Failure] versus [Error] — a real distinction, not redundancy
 * - [Failure] — the tool worked correctly and the operation did not succeed.
 *   No matching contact; the event was already deleted. **Expected.** The AI
 *   should tell the user plainly and perhaps offer an alternative.
 * - [Error] — the tool itself malfunctioned. An unexpected exception, a
 *   contract violation. **A defect.** The AI should apologise rather than
 *   explain, and the count of these in production is a quality signal.
 *
 * Collapsing them would hide defect rates inside ordinary "didn't work"
 * outcomes, which is exactly where they become invisible.
 *
 * ## Scope note (Day 05 Phase 1)
 * The type is complete. No tool produces anything but [Unsupported] yet.
 *
 * ## Dependencies
 * [DpsPermission] and kotlinx.serialization. Pure Kotlin.
 */
@Serializable
sealed interface ToolResult {

    /** Whether this outcome represents the operation having been carried out. */
    val isSuccess: Boolean get() = this is Success

    /**
     * The operation completed.
     *
     * @param summary one line, safe to show a user or hand to the LLM. Must not
     *   name tools or internal architecture (AI Rules 1 and 2).
     * @param data structured results, e.g. a created event id.
     */
    @Serializable
    @SerialName("success")
    data class Success(
        val summary: String,
        val data: Map<String, String> = emptyMap(),
    ) : ToolResult

    /**
     * The tool ran correctly; the operation did not succeed. Expected.
     *
     * @param retryable whether the same call could plausibly succeed later.
     *   Distinguishes "no network right now" from "no such contact".
     */
    @Serializable
    @SerialName("failure")
    data class Failure(
        val reason: String,
        val retryable: Boolean = false,
    ) : ToolResult

    /**
     * Blocked pending permissions.
     *
     * Returned rather than silently requesting: a permission dialog is a
     * user-visible interruption, and deciding when to show one requires knowing
     * what the user is trying to do — context the tool layer does not have.
     *
     * @param rationale plain-language reason, suitable for the pre-request
     *   explanation the platform recommends.
     */
    @Serializable
    @SerialName("permission_required")
    data class PermissionRequired(
        val permissions: List<DpsPermission>,
        val rationale: String,
    ) : ToolResult

    /** The user or the system cancelled the operation. Not an error. */
    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        val reason: String = "Cancelled.",
    ) : ToolResult

    /**
     * The operation cannot be performed on this device or build.
     *
     * Covers an unregistered tool, an operation a tool does not implement, and
     * a capability absent below some API level.
     *
     * @param minApiLevel set when the cause is OS version, so the UI can say
     *   something more useful than "not supported".
     */
    @Serializable
    @SerialName("unsupported")
    data class Unsupported(
        val reason: String,
        val minApiLevel: Int? = null,
    ) : ToolResult

    /**
     * The tool exceeded its time budget.
     *
     * Separate from [Failure] because the operation may still be in flight on
     * the platform side — a calendar write that timed out may yet land, so
     * blind retry risks duplicates.
     */
    @Serializable
    @SerialName("timeout")
    data class Timeout(
        val elapsedMillis: Long,
        val limitMillis: Long,
    ) : ToolResult

    /**
     * The tool malfunctioned. A defect, not an expected outcome.
     *
     * @param cause exception class and message, for diagnostics. **Never shown
     *   to the user** — AI Rules 1 and 2 forbid exposing internals.
     */
    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
        val cause: String? = null,
    ) : ToolResult
}
