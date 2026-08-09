package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.intent.requiredFields

/**
 * Decides whether a request can be carried out, and what to ask when it cannot.
 *
 * ## Purpose
 * The component that makes DPS ask instead of guess.
 *
 * A reminder with no time cannot exist. A WhatsApp message with no recipient
 * cannot be addressed. Faced with either, an assistant has three options:
 * invent the missing part, fail, or ask. Inventing produces a reminder that
 * fires at the wrong moment or a message to the wrong person — the two failures
 * this product can least afford. Failing wastes what the user already said.
 *
 * Asking is the only one that respects both.
 *
 * ## How completeness is decided
 * [IntentType.requiredFields] gives alternative field groups; an intent is
 * satisfiable when **any one group** is fully present. "Message Abdul" and
 * "message +923001234567" are both complete, by different routes, and neither
 * should trigger a question.
 *
 * Deriving the question from that data rather than from a hand-written `when`
 * means a new intent gets sensible clarification for free, and the two can
 * never drift apart.
 *
 * ## Merging answers
 * [merge] folds a follow-up answer into what was already understood, so the
 * user answers the question rather than repeating the whole request. Without
 * it, "at 4pm" after "remind me about the meeting" would lose the meeting.
 *
 * ## Dependencies
 * Domain intent types. Pure Kotlin.
 */
class ClarificationEngine {

    /** Outcome of checking an intent for completeness. */
    sealed interface Check {
        /** Everything needed is present. */
        data object Complete : Check

        /** Something is missing; [question] asks for it. */
        data class Missing(
            val fields: Set<IntentField>,
            val question: String,
        ) : Check
    }

    /**
     * Checks whether [intent] can be executed.
     *
     * When several field groups would satisfy it, the group **closest to
     * complete** is chosen to ask about. Asking for the one remaining piece of
     * a nearly-finished route is far better than asking for two pieces of an
     * untouched one.
     */
    fun check(intent: DpsIntent): Check {
        // Day 06: a query needs no fields — "mere pending tasks dikhao" is
        // complete by construction, whatever it does or does not filter by.
        if (intent.action == IntentAction.LIST) return Check.Complete

        // UPDATE/CANCEL name no new content — the whole request is "which one,
        // and what changes about it" — so completeness means knowing *which*
        // item, not the title/time groups a fresh CREATE would need. `fields`
        // is empty here because the missing thing is a referent, not a value
        // any IntentField represents.
        if (intent.action != IntentAction.CREATE && intent.type in TARGETABLE_TYPES) {
            return if (intent.parameters.targetId != null) {
                Check.Complete
            } else {
                Check.Missing(fields = emptySet(), question = questionForMissingTarget(intent.type))
            }
        }

        // Day 06: TASK/ACTION_ITEM completion or cancellation may address the
        // item either by a remembered id (a pronoun reference resolved by
        // com.softwaremine.dps.ai.memory.ReferenceResolver) or by a spoken
        // title ("DBPMS wala task complete kar do") — ToolSelector passes
        // both through, and the Android tool falls back to a title match
        // when no id is present. UPDATE is deliberately excluded: there
        // `title` means the *new* title, not an address, so it still needs a
        // resolved id, matching REMINDER/CALENDAR_EVENT.
        if (intent.type in TITLE_ADDRESSABLE_TYPES && intent.action in setOf(IntentAction.COMPLETE, IntentAction.CANCEL)) {
            return if (intent.parameters.targetId != null || intent.parameters.has(IntentField.TITLE)) {
                Check.Complete
            } else {
                Check.Missing(fields = emptySet(), question = questionForMissingTarget(intent.type))
            }
        }
        if (intent.type == IntentType.TASK && intent.action == IntentAction.UPDATE) {
            return if (intent.parameters.targetId != null) {
                Check.Complete
            } else {
                Check.Missing(fields = emptySet(), question = questionForMissingTarget(intent.type))
            }
        }

        val groups = intent.type.requiredFields
        if (groups.isEmpty()) return Check.Complete

        val parameters = intent.parameters

        // Any fully satisfied group means the request is actionable.
        if (groups.any { group -> group.all(parameters::has) }) return Check.Complete

        val closest = groups.minByOrNull { group -> group.count { !parameters.has(it) } }
            ?: return Check.Complete

        val missing = closest.filterNot(parameters::has).toSet()
        return Check.Missing(
            fields = missing,
            question = questionFor(intent.type, missing),
        )
    }

    /**
     * Folds [answer] into [existing], keeping what was already known.
     *
     * New values win only where they are present, so answering one question
     * cannot erase an earlier detail.
     */
    fun merge(existing: IntentParameters, answer: IntentParameters): IntentParameters =
        IntentParameters(
            person = answer.person ?: existing.person,
            date = answer.date ?: existing.date,
            time = answer.time ?: existing.time,
            message = answer.message ?: existing.message,
            title = answer.title ?: existing.title,
            description = answer.description ?: existing.description,
            email = answer.email ?: existing.email,
            phone = answer.phone ?: existing.phone,
            priority = answer.priority ?: existing.priority,
            // A resolved target survives a clarification round the same way
            // any other already-known detail does — see ai.memory.ReferenceResolver.
            targetId = answer.targetId ?: existing.targetId,
            duration = answer.duration ?: existing.duration,
            period = answer.period ?: existing.period,
        )

    /**
     * Phrases the follow-up.
     *
     * Written as a person would ask, and **never mentioning tools, fields or
     * parameters** — AI Rules 1 and 2 forbid exposing internal architecture,
     * and "missing required field TIME" is not something a secretary says.
     *
     * Questions are specific to the intent because the natural phrasing differs:
     * a reminder wants "when", an email wants "who to".
     */
    internal fun questionFor(type: IntentType, missing: Set<IntentField>): String {
        // One missing field gets a direct question; several get a combined one,
        // because asking twice in a row reads as interrogation.
        val single = missing.singleOrNull()

        return when (type) {
            IntentType.REMINDER -> when (single) {
                IntentField.TIME -> "When should I remind you?"
                IntentField.TITLE, IntentField.MESSAGE -> "What should I remind you about?"
                else -> "What should I remind you about, and when?"
            }

            IntentType.CALENDAR_EVENT -> when (single) {
                IntentField.DATE, IntentField.TIME -> "When is it?"
                IntentField.TITLE -> "What should I call this event?"
                else -> "What is the event, and when is it?"
            }

            IntentType.NOTIFICATION -> "What should the notification say?"

            IntentType.CONTACT_LOOKUP -> "Who are you looking for?"

            IntentType.WHATSAPP_MESSAGE -> when (single) {
                IntentField.MESSAGE -> "What would you like the message to say?"
                IntentField.PERSON, IntentField.PHONE -> "Who should I message?"
                else -> "Who should I message, and what should it say?"
            }

            IntentType.EMAIL_MESSAGE -> when (single) {
                IntentField.MESSAGE -> "What should the email say?"
                IntentField.PERSON, IntentField.EMAIL -> "Who should I email?"
                IntentField.TITLE -> "What subject should I use?"
                else -> "Who should I email, and what should it say?"
            }

            IntentType.TASK -> "What should the task be?"
            IntentType.WORK_LOG -> "What did you work on, or for how long?"
            IntentType.MEETING_NOTE -> "What should I note about the meeting?"
            IntentType.ACTION_ITEM -> "What's the follow-up?"
            // requiredFields is empty for REPORT, so this never fires in practice.
            IntentType.REPORT -> "Could you say a bit more?"

            // Conversation requires nothing, so this is unreachable in practice.
            IntentType.CONVERSATION -> "Could you say a bit more?"
        }
    }

    /** Asked when an UPDATE/CANCEL/COMPLETE never resolved which item it targets. */
    internal fun questionForMissingTarget(type: IntentType): String = when (type) {
        IntentType.REMINDER -> "Which reminder do you mean?"
        IntentType.CALENDAR_EVENT -> "Which event do you mean?"
        IntentType.TASK -> "Which task do you mean?"
        IntentType.ACTION_ITEM -> "Which action item do you mean?"
        else -> "Which one do you mean?"
    }

    private companion object {
        /** Intent types addressed strictly by a resolved id — [ai.intent.ToolSelector]'s UPDATE/CANCEL calls. */
        val TARGETABLE_TYPES = setOf(IntentType.REMINDER, IntentType.CALENDAR_EVENT)

        /** Day 06: intent types whose COMPLETE/CANCEL may address the item by title instead of id. */
        val TITLE_ADDRESSABLE_TYPES = setOf(IntentType.TASK, IntentType.ACTION_ITEM)
    }
}
