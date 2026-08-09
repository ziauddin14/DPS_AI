package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.memory.CalendarEventMemory
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.EmailMemory
import com.softwaremine.dps.domain.memory.MeetingMemory
import com.softwaremine.dps.domain.memory.ReminderMemory
import com.softwaremine.dps.domain.memory.TaskMemory
import com.softwaremine.dps.domain.memory.WhatsAppMemory
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Updates [ConversationMemory] after a tool call, so the next message can say
 * "usko" instead of repeating a name, a time or an id.
 *
 * ## Why only [ToolResult.Success] updates memory
 * A failed or refused action changed nothing on the device, so it must not
 * change what DPS believes it can refer back to either — remembering a
 * reminder that was never actually created would let a later "cancel the
 * reminder" appear to succeed against something that does not exist.
 *
 * ## Why ambiguous contact matches do not set [ConversationMemory.lastContact]
 * [com.softwaremine.dps.data.android.tool.AndroidContactsTool] reports an
 * ambiguous match as `Success` with `data["ambiguous"] = "true"` — a genuine
 * outcome, but not a resolved person. Remembering one of the candidates as
 * "the" contact would silently guess exactly what
 * [com.softwaremine.dps.domain.contact.ContactResolver] was built to refuse to
 * guess. Only the searched name is kept, in
 * [ConversationMemory.lastReferencedPerson], so a follow-up still has
 * something to work with without implying a person was chosen.
 *
 * ## Why this depends on [ToolSelector] (Day 05 Phase E Stage 2)
 * `update_event`'s [ToolResult.Success.data] carries only the event id — the
 * new start time it applied lives in the [DpsIntent] that was executed, not
 * the result, exactly as [ToolSelector] itself computed it when building the
 * call. Re-deriving that here with separate date/time parsing would be the
 * duplicated business logic the brief explicitly rules out; both classes live
 * in this module, so [ToolSelector.resolveInstant]'s `internal` visibility
 * already permits reuse.
 *
 * ## Dependencies
 * Domain memory, intent and tool types, [ToolSelector], `java.time`. No Android.
 */
class ConversationMemoryUpdater(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val toolSelector: ToolSelector = ToolSelector(zone = zone),
) {

    fun remember(
        memory: ConversationMemory,
        intent: DpsIntent,
        toolId: ToolId,
        result: ToolResult,
        nowMillis: Long,
    ): ConversationMemory {
        if (result !is ToolResult.Success) return memory

        val updated = when (toolId) {
            ToolId.REMINDER -> rememberReminder(memory, intent, result, nowMillis)
            ToolId.CALENDAR -> rememberCalendarEvent(memory, intent, result, nowMillis)
            ToolId.CONTACTS -> rememberContact(memory, intent, result, nowMillis)
            ToolId.WHATSAPP -> rememberWhatsApp(memory, result, nowMillis)
            ToolId.GMAIL -> rememberEmail(memory, result, nowMillis)
            ToolId.TASK -> rememberTask(memory, intent, result, nowMillis)
            ToolId.MEETING -> rememberMeeting(memory, intent, result, nowMillis)
            else -> memory
        }

        // Independent of which tool ran: "Abdul ko yaad dila dena" names Abdul
        // without REMINDER ever resolving him as a contact, but a later "usko"
        // still means him. CONTACTS/WHATSAPP/GMAIL already set
        // lastReferencedPerson above from an actual resolution — this only
        // fills the gap for tools that never resolve a person at all.
        val mentioned = intent.parameters.value(IntentField.PERSON)
        return if (mentioned != null && toolId !in PERSON_RESOLVING_TOOLS) {
            updated.copy(lastReferencedPerson = mentioned, updatedAtMillis = nowMillis)
        } else {
            updated
        }
    }

    private fun rememberReminder(
        memory: ConversationMemory,
        intent: DpsIntent,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        if (intent.action == IntentAction.CANCEL) {
            return memory.copy(lastReminder = null, updatedAtMillis = nowMillis)
        }

        val id = result.data["reminder_id"]?.toIntOrNull() ?: return memory
        val triggerAt = result.data["trigger_at"]?.toLongOrNull() ?: return memory
        val title = intent.parameters.value(IntentField.TITLE)
            ?: intent.parameters.value(IntentField.MESSAGE)
            ?: memory.lastReminder?.title.orEmpty()

        return memory.copy(
            lastReminder = ReminderMemory(id = id, title = title, triggerAtMillis = triggerAt),
            lastReferencedDateTimeMillis = triggerAt,
            updatedAtMillis = nowMillis,
        )
    }

    private fun rememberCalendarEvent(
        memory: ConversationMemory,
        intent: DpsIntent,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        if (intent.action == IntentAction.CANCEL) {
            return memory.copy(lastCalendarEvent = null, updatedAtMillis = nowMillis)
        }

        val id = result.data["event_id"]?.toLongOrNull() ?: return memory

        if (intent.action == IntentAction.UPDATE) {
            // update_event's own Success.data carries only `event_id` — the
            // new start/end live in the intent that was executed, not the
            // result, so they are re-derived the same way ToolSelector built
            // the call in the first place, via the ToolSelector it already
            // shares the module with (see class doc).
            val existing = memory.lastCalendarEvent
            val start = toolSelector.resolveInstant(intent.parameters) ?: existing?.startMillis ?: return memory
            val duration = existing?.let { it.endMillis - it.startMillis } ?: DEFAULT_EVENT_DURATION_MILLIS
            val title = intent.parameters.value(IntentField.TITLE) ?: existing?.title.orEmpty()

            return memory.copy(
                lastCalendarEvent = CalendarEventMemory(id, title, start, start + duration),
                lastReferencedDateTimeMillis = start,
                updatedAtMillis = nowMillis,
            )
        }

        val start = result.data["start"]?.let(::parseLocalMillis) ?: return memory
        val end = result.data["end"]?.let(::parseLocalMillis) ?: start
        val title = intent.parameters.value(IntentField.TITLE)
            ?: intent.parameters.value(IntentField.MESSAGE)
            ?: memory.lastCalendarEvent?.title.orEmpty()

        return memory.copy(
            lastCalendarEvent = CalendarEventMemory(id, title, start, end),
            lastReferencedDateTimeMillis = start,
            updatedAtMillis = nowMillis,
        )
    }

    private fun rememberContact(
        memory: ConversationMemory,
        intent: DpsIntent,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        if (result.data["ambiguous"] == "true") {
            val searched = intent.parameters.value(IntentField.PERSON)
            return if (searched == null) memory else memory.copy(
                lastReferencedPerson = searched,
                updatedAtMillis = nowMillis,
            )
        }

        val id = result.data["contact_id"] ?: return memory
        val name = result.data["name"] ?: return memory
        val contact = Contact(
            id = id,
            displayName = name,
            phoneNumbers = listOfNotNull(result.data["phone"]),
            emailAddresses = listOfNotNull(result.data["email"]),
        )

        return memory.copy(
            lastContact = contact,
            lastReferencedPerson = name,
            updatedAtMillis = nowMillis,
        )
    }

    private fun rememberWhatsApp(
        memory: ConversationMemory,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        val recipient = result.data["recipient"]
        val phone = result.data["phone"]
        val message = result.data["message"] ?: return memory

        return memory.copy(
            lastWhatsAppAction = WhatsAppMemory(recipient, phone, message),
            lastReferencedPerson = recipient ?: memory.lastReferencedPerson,
            updatedAtMillis = nowMillis,
        )
    }

    private fun rememberEmail(
        memory: ConversationMemory,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        val recipient = result.data["recipient"]
        val address = result.data["to"]
        val subject = result.data["subject"].orEmpty()
        if (recipient == null && address == null) return memory

        return memory.copy(
            lastEmail = EmailMemory(recipient, address, subject),
            lastReferencedPerson = recipient ?: memory.lastReferencedPerson,
            updatedAtMillis = nowMillis,
        )
    }

    /**
     * Day 06. `cancel_task` deletes the record, so nothing survives to refer
     * back to — mirroring [rememberReminder]'s own CANCEL handling.
     * `complete_task`/`update_task` keep it, since the task still exists.
     */
    private fun rememberTask(
        memory: ConversationMemory,
        intent: DpsIntent,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        if (intent.action == IntentAction.CANCEL) {
            return memory.copy(lastTask = null, updatedAtMillis = nowMillis)
        }

        val id = result.data["task_id"]?.toIntOrNull() ?: return memory
        val title = intent.parameters.value(IntentField.TITLE) ?: memory.lastTask?.title.orEmpty()

        return memory.copy(
            lastTask = TaskMemory(id = id, title = title),
            updatedAtMillis = nowMillis,
        )
    }

    private fun rememberMeeting(
        memory: ConversationMemory,
        intent: DpsIntent,
        result: ToolResult.Success,
        nowMillis: Long,
    ): ConversationMemory {
        val id = result.data["meeting_id"]?.toIntOrNull() ?: return memory
        val title = intent.parameters.value(IntentField.TITLE) ?: memory.lastMeeting?.title.orEmpty()

        return memory.copy(
            lastMeeting = MeetingMemory(id = id, title = title),
            updatedAtMillis = nowMillis,
        )
    }

    /** Parses a [com.softwaremine.dps.data.android.common.ToolArguments.describe] string back to epoch millis. */
    private fun parseLocalMillis(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        /** Tools whose branch above already sets `lastReferencedPerson` from a confirmed resolution. */
        val PERSON_RESOLVING_TOOLS = setOf(ToolId.CONTACTS, ToolId.WHATSAPP, ToolId.GMAIL)

        /** Used only when nothing is remembered to preserve a duration from. Matches AndroidCalendarTool's own default. */
        const val DEFAULT_EVENT_DURATION_MILLIS = 60L * 60L * 1000L
    }
}
