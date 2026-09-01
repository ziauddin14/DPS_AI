package com.softwaremine.dps.ai.memory

/**
 * Recognizes an explicit "always remind me N minutes before" statement (M6)
 * — the one, narrow write path [com.softwaremine.dps.domain.preferences.UserPreferences.defaultReminderLeadMinutes]
 * gets in this milestone.
 *
 * ## Why this is deterministic, not model-assisted
 * Unlike semantic-fact extraction ([com.softwaremine.dps.domain.intent.IntentType.REMEMBER_FACT]'s
 * own doc explains why *that* needs the model), the target shape here is
 * narrow enough for a fixed pattern: a "default" cue plus the exact same
 * relative-offset phrase [findRelativeOffsetMillis] already parses reliably
 * for one-off reminders. No new parsing logic — reuses that function
 * directly rather than a second, drifting copy of the same regex.
 *
 * ## Why this never touches the fallback default
 * [com.softwaremine.dps.domain.preferences.UserPreferences]'s own doc states
 * the invariant this recognizer must not violate: the *fallback* 30-minute
 * default must never be written back as though the user had chosen it. This
 * class only ever returns a value when the user's own words explicitly
 * asked for a *default* to be set — an ordinary one-off "remind me 15
 * minutes before" is deliberately left alone (it already works, unchanged,
 * through [ReferenceResolver]/`anchorToPriorEvent`'s own existing paths).
 */
object PreferenceStatementRecognizer {

    /**
     * The lead time (positive minutes, always "before") the user explicitly
     * asked to be remembered as their default — or `null` when [rawText]
     * carries no such statement. Only ever fires for "before"/"pehle"-shaped
     * phrases; "30 minutes after" is not a lead time and is never returned
     * as one, however it was phrased.
     *
     * @param referenceResolver the caller's own existing instance — a
     *   parameter rather than a field, so this stays a stateless `object`
     *   without constructing a redundant [ReferenceResolver] on every call.
     */
    fun recognizeDefaultLeadMinutes(rawText: String, referenceResolver: ReferenceResolver): Int? {
        val normalized = rawText.lowercase().trim()
        if (DEFAULT_CUES.none { normalized.contains(it) }) return null

        val offsetMillis = referenceResolver.findRelativeOffsetMillis(normalized) ?: return null
        if (offsetMillis >= 0) return null // "baad"/"later" is not a lead time.

        val minutes = (-offsetMillis / MILLIS_PER_MINUTE).toInt()
        return minutes.takeIf { it > 0 }
    }

    private val DEFAULT_CUES = listOf(
        "hamesha", "hamesha se", "by default", "default", "always",
    )

    private const val MILLIS_PER_MINUTE = 60_000L
}
