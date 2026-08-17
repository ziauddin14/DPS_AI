package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.IntentAction

/**
 * Recognises "cancel" and "reschedule" phrasing the model's own [IntentAction]
 * classification missed.
 *
 * ## Why this exists alongside the model's own `action` field
 * [com.softwaremine.dps.ai.intent.IntentJsonParser] already reads an optional
 * `action` key from the model's JSON. This class is the safety net for when
 * that key is absent or wrong — which, on a 1.5B model working in Roman Urdu,
 * is often enough to matter (the same reasoning that makes
 * [com.softwaremine.dps.ai.intent.IntentJsonParser] tolerant of malformed JSON
 * applies here to a malformed *classification*).
 *
 * ## Why the keyword list is deliberately narrow
 * "Kar do" alone cannot mean UPDATE — "yaad dila do" ("remind me") ends in
 * "do" and is an ordinary CREATE. Every phrase below names the *change*
 * explicitly (reschedule, pehle/baad, badal do) rather than matching on a verb
 * so common it appears in nearly every Roman Urdu imperative. A false CANCEL or
 * UPDATE is worse than missing one — [detect] only overrides the model's own
 * answer when a cue is unambiguous, and otherwise trusts it.
 *
 * ## Dependencies
 * [IntentAction]. No Android, no model call — this never costs a token.
 */
class ActionDetector {

    /**
     * Detects create/update/cancel from [rawText].
     *
     * @param modelAction what the model's own classification (or its default,
     *   [IntentAction.CREATE]) already said. Returned unchanged unless a
     *   keyword in [rawText] unambiguously says otherwise.
     */
    fun detect(rawText: String, modelAction: IntentAction): IntentAction {
        val normalized = rawText.lowercase().trim()

        return when {
            CANCEL_KEYWORDS.any { normalized.contains(it) } -> IntentAction.CANCEL
            UPDATE_KEYWORDS.any { normalized.contains(it) } -> IntentAction.UPDATE
            else -> modelAction
        }
    }

    private companion object {
        val CANCEL_KEYWORDS = listOf(
            "cancel",
            "delete",
            "remove",
            "hata do",
            "hata dena",
            "hatao",
            "khatam kar do",
            "khatam kardo",
            "mita do",
            "mita dena",
            "band kar do",
        )

        val UPDATE_KEYWORDS = listOf(
            "reschedule",
            "pehle kar do",
            "pehle kardo",
            "pehle yaad dila do",
            "pehle yaad dilado",
            "jaldi kar do",
            "baad kar do",
            "der kar do",
            "late kar do",
            "badal do",
            "badal dena",
            "badlo",
            "change kar do",
            "waqt badal do",
            "time badal do",
            "date badal do",
            "move kar do",
            "shift kar do",
            // CAT5 (Day 09 follow-up): bare English "move"/"change" alone are
            // deliberately NOT added here — both are common enough as
            // ordinary CREATE *content* ("remind me to move the couch",
            // "remind me to change the oil") that matching them bare would
            // misroute a brand-new request, exactly the failure this class's
            // own doc warns against. Anchoring each to "that/the
            // meeting/event/reminder" keeps every entry a phrase that names
            // the change explicitly, the same discipline "move kar do"/
            // "shift kar do" already follow — see the investigation's false-
            // positive analysis for why the anchor is required, not optional.
            "move that meeting",
            "move the meeting",
            "move that event",
            "move the event",
            "move that reminder",
            "move the reminder",
            "change that meeting",
            "change the meeting",
            "change that event",
            "change the event",
            "change that reminder",
            "change the reminder",
        )
    }
}
