package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent

/**
 * An intent held back pending a plain yes/no answer — either a follow-up
 * suggestion ("would you like a reminder for this too?") or a destructive
 * action that needs explicit consent first (deleting a calendar event).
 *
 * ## Why both share one type
 * Structurally identical: an intent that runs if the answer is yes, and does
 * not exist at all if it is no. [ai.secretary.SecretaryOrchestrator] tells
 * the two apart only by *why* it set this — which shows up as different
 * wording in the question already shown to the user, not as a field here.
 *
 * ## Freshness
 * Mirrors [PendingPermissionAction] and [PendingContactSelection]: a "yes"
 * five minutes into an unrelated conversation no longer implies consent to
 * the thing that was asked about.
 */
data class PendingConfirmation(
    val intent: DpsIntent,
    val requestedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - requestedAtMillis <= FRESHNESS_WINDOW_MILLIS

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
