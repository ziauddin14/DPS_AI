package com.softwaremine.dps.domain.intent

import com.softwaremine.dps.domain.tool.ToolCall

/**
 * What the orchestrator decided to do with a user message.
 *
 * ## Purpose
 * The one type the whole Phase D pipeline returns. Every path a request can
 * take ends in exactly one of these, which is what lets the session manager
 * handle the outcome without knowing how it was reached.
 *
 * ## Why clarification is a first-class outcome
 * [NeedsClarification] is the reason an intent layer exists at all. Without it
 * the only options for "remind me about the meeting" are to invent a time or to
 * fail — and inventing one produces a reminder that fires at the wrong moment,
 * which is worse than either asking or refusing.
 *
 * ## Dependencies
 * Domain intent and tool types. Pure Kotlin.
 */
sealed interface IntentResolution {

    /**
     * Ordinary conversation. No tool involved.
     *
     * The default for anything not clearly an action, including a model reply
     * that could not be parsed. Answering conversationally when an action was
     * wanted merely disappoints; acting when only conversation was wanted can
     * send a message the user never asked for.
     */
    data class Conversation(val reason: String) : IntentResolution

    /** A complete request, ready to execute. */
    data class Actionable(
        val intent: DpsIntent,
        val call: ToolCall,
    ) : IntentResolution

    /**
     * Understood, but something needed is missing.
     *
     * @param question the follow-up to put to the user, phrased naturally.
     * @param missing the fields that would satisfy the request.
     * @param partial what was understood, so a later answer can be merged in
     *   rather than making the user repeat themselves.
     */
    data class NeedsClarification(
        val intent: DpsIntent,
        val question: String,
        val missing: Set<IntentField>,
        val partial: IntentParameters,
    ) : IntentResolution

    /**
     * Understood and routable, but the tool it needs is unavailable.
     *
     * Distinct from [Conversation]: the request made sense and DPS simply
     * cannot carry it out, which the user is entitled to be told.
     */
    data class Unavailable(
        val intent: DpsIntent,
        val reason: String,
    ) : IntentResolution
}

/**
 * A tool call held back because a permission is missing.
 *
 * ## Purpose
 * Makes "resume after the permission is granted" possible. Without it, granting
 * a permission leaves the user having to repeat their request word for word —
 * which they experience as DPS having forgotten what they just said.
 *
 * ## Why the call is stored rather than the sentence
 * Re-running the original text would mean a second inference pass, several
 * seconds of it on this hardware, and a fresh chance for the model to interpret
 * the same words differently. Storing the resolved [ToolCall] makes resumption
 * immediate and guarantees the action taken is the one the user was told about.
 *
 * ## Lifetime
 * Process-scoped and single-slot: a newer pending action replaces an older one.
 * A queue of deferred actions would let DPS perform something the user asked
 * for long ago and has since forgotten, which is worse than losing it.
 */
data class PendingPermissionAction(
    val call: ToolCall,
    val intent: DpsIntent,
    val requestedAtMillis: Long,
) {
    /**
     * Whether this is still worth resuming.
     *
     * A permission granted ten minutes after the request no longer implies the
     * user still wants that action taken.
     */
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - requestedAtMillis <= FRESHNESS_WINDOW_MILLIS

    companion object {
        /** Five minutes. Long enough to visit settings, short enough to stay intentional. */
        const val FRESHNESS_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
