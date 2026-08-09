package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent

/**
 * A request held because more than one contact matched, waiting on the user
 * to say which one.
 *
 * ## Why the whole [originalIntent] is stored, not just the name
 * The user does not repeat "message Ali, on my way" after picking a contact —
 * they answer "the second one." Storing the intent that was already fully
 * understood, minus only *which* Ali, is what lets
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] resume the moment
 * a choice is made, with no further inference pass (Phase E Stage 2 brief,
 * requirement 2: "resume execution without unnecessarily running inference
 * again").
 *
 * ## Freshness
 * Mirrors [PendingPermissionAction] exactly, for the same reason: a selection
 * made five minutes into an unrelated conversation no longer implies the user
 * still wants the original request carried out.
 */
data class PendingContactSelection(
    val originalIntent: DpsIntent,
    val candidates: List<Contact>,
    val requestedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - requestedAtMillis <= FRESHNESS_WINDOW_MILLIS

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
