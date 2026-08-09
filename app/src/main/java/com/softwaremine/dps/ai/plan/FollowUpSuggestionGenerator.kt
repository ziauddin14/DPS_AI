package com.softwaremine.dps.ai.plan

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Offers a related action after a successful one, without ever taking it.
 *
 * ## Why this is templated, not modelled
 * The same reasoning as [com.softwaremine.dps.ai.intent.ToolResponseGenerator]:
 * a second inference pass to *think of* a suggestion would cost as much as the
 * original action, and a model free to suggest anything will eventually
 * suggest something DPS cannot actually do. The two suggestions here are the
 * ones the Stage 2 brief names, expressed as a fixed rule rather than a claim
 * of general creativity.
 *
 * ## Why the suggested action is a real [DpsIntent], not just text
 * "Would you like a reminder 30 minutes before?" must be answerable with a
 * bare "haan" and nothing else. Building the actual intent now — while the
 * event's time is already known — is what lets
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] execute it on
 * confirmation with no further inference pass, the same principle
 * [ConfirmationParser] and [ContactSelectionParser] apply to their own resumes.
 *
 * ## Never executed automatically
 * [suggestionFor] only returns what *could* happen next. Nothing here calls a
 * tool — requirement 7 is explicit that a suggestion is not a second action.
 *
 * ## Dependencies
 * Domain intent and tool types, `java.time`. No Android, no model call.
 */
class FollowUpSuggestionGenerator(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** A follow-up worth offering, and the intent that would carry it out if accepted. */
    data class Suggestion(val text: String, val intent: DpsIntent)

    /**
     * Returns a suggestion for what just succeeded, or `null` when nothing
     * applies — most successful actions do not have an obvious follow-up, and
     * offering one anyway would read as noise rather than help.
     */
    fun suggestionFor(intent: DpsIntent, result: ToolResult.Success): Suggestion? {
        if (intent.action != IntentAction.CREATE) return null

        return when (intent.type) {
            IntentType.CALENDAR_EVENT -> suggestReminderBeforeEvent(intent, result)
            IntentType.REMINDER -> suggestEventForReminder(intent, result)
            else -> null
        }
    }

    private fun suggestReminderBeforeEvent(intent: DpsIntent, result: ToolResult.Success): Suggestion? {
        val start = result.data["start"]?.let(::parseMillis) ?: return null
        val title = intent.parameters.value(IntentField.TITLE) ?: "the meeting"
        val reminderAt = start - REMINDER_LEAD_MILLIS

        val suggested = DpsIntent(
            type = IntentType.REMINDER,
            parameters = dateTimeParameters(title, reminderAt),
            action = IntentAction.CREATE,
        )
        return Suggestion(
            text = "Would you like me to set a reminder 30 minutes before?",
            intent = suggested,
        )
    }

    private fun suggestEventForReminder(intent: DpsIntent, result: ToolResult.Success): Suggestion? {
        val triggerAt = result.data["trigger_at"]?.toLongOrNull() ?: return null
        val title = intent.parameters.value(IntentField.TITLE)
            ?: intent.parameters.value(IntentField.MESSAGE)
            ?: "this"

        val suggested = DpsIntent(
            type = IntentType.CALENDAR_EVENT,
            parameters = dateTimeParameters(title, triggerAt),
            action = IntentAction.CREATE,
        )
        return Suggestion(
            text = "Would you like me to add a calendar event for this too?",
            intent = suggested,
        )
    }

    private fun dateTimeParameters(title: String, millis: Long) =
        Instant.ofEpochMilli(millis).atZone(zone).let { zoned ->
            com.softwaremine.dps.domain.intent.IntentParameters(
                title = title,
                date = zoned.format(DateTimeFormatter.ISO_LOCAL_DATE),
                time = zoned.format(DateTimeFormatter.ofPattern("HH:mm")),
            )
        }

    /** Parses a [com.softwaremine.dps.data.android.common.ToolArguments.describe] string back to epoch millis. */
    private fun parseMillis(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        const val REMINDER_LEAD_MILLIS = 30L * 60L * 1000L
    }
}
