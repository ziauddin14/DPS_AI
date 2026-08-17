package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentType

/**
 * A remembered entity DPS could be redirecting an ambiguous non-CREATE
 * request toward — a calendar event, a reminder or a task already sitting in
 * [com.softwaremine.dps.domain.memory.ConversationMemory].
 */
data class DisambiguationCandidate(
    val type: IntentType,
    val targetId: String,
    val label: String,
)

/**
 * A request held because the model's own classification named a type with no
 * way to resolve what it refers to — or resolved to nothing of its own —
 * while conversation memory holds one or more plausible alternatives it never
 * got to consider (Day 09, Option 1).
 *
 * ## Why this exists alongside [PendingContactSelection]
 * Structurally the same shape — hold candidates, ask which one, resume with
 * no further inference pass — but over remembered entities rather than
 * contacts, and reached from a different signal entirely: never the user's
 * raw text, only [com.softwaremine.dps.ai.intent.ClarificationEngine]'s own
 * completeness result plus [com.softwaremine.dps.domain.memory.ConversationMemory].
 * See [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s
 * `disambiguationCandidates` for exactly where and why this is populated.
 *
 * ## Freshness
 * Mirrors [PendingContactSelection] and [PendingConfirmation].
 */
data class PendingTypeDisambiguation(
    val originalIntent: DpsIntent,
    val candidates: List<DisambiguationCandidate>,
    val requestedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - requestedAtMillis <= FRESHNESS_WINDOW_MILLIS

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
