package com.softwaremine.dps.domain.preferences

import kotlinx.serialization.Serializable

/**
 * Durable user configuration (M3-C) — distinct from
 * [com.softwaremine.dps.domain.memory.ConversationMemory], which is recent
 * conversational/entity context, not configuration the user set once and
 * expects to persist indefinitely.
 *
 * ## Why this stays small
 * Only the one preference the current architecture actually needs a place
 * for. Every other preference category (language, notifications, arbitrary
 * key/value settings) has no existing consumer or requirement yet — adding
 * fields for them now would be exactly the speculative "generic preference
 * bag" M3-C's own brief warns against.
 *
 * ## [defaultReminderLeadMinutes]
 * `null` means "the user has not explicitly configured a default lead
 * time" — not "0 minutes before." The one consumer,
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s
 * `anchorToPriorEvent`, falls back to its own existing fixed 30-minute
 * default in that case; this class never performs that fallback itself, and
 * the fallback value is never written back here as though the user had
 * chosen it.
 *
 * ## Correction from the first M3-C pass
 * The first implementation stored a UTC timezone offset (`utcOffset:
 * String?`) here. `anchorToPriorEvent`'s existing default constant
 * (`DEFAULT_REMINDER_LEAD_MILLIS`) is always subtracted from a prior
 * event's start — a reminder *lead time*, always "minutes before," never a
 * timezone concept — and nothing else in the codebase consumed `utcOffset`
 * either. Replaced with the field the M3 investigation's own §15 proposed
 * and the actual consumer needs: a plain minutes count, matching
 * `DEFAULT_REMINDER_LEAD_MILLIS`'s own always-"before" semantics.
 */
@Serializable
data class UserPreferences(
    val defaultReminderLeadMinutes: Int? = null,
) {
    companion object {
        /** No preference explicitly configured yet. */
        val EMPTY = UserPreferences()
    }
}
