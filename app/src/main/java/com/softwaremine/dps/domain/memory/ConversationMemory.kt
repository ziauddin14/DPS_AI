package com.softwaremine.dps.domain.memory

import com.softwaremine.dps.domain.contact.Contact

/**
 * What DPS remembers about the current conversation, beyond the raw message list.
 *
 * ## Purpose
 * A chat transcript answers "what was said." It does not answer "who is
 * `usko`?" or "which reminder does `us reminder ko cancel kar do` mean?" —
 * questions every real secretary answers from short-term memory, not by asking
 * the user to repeat themselves. [ConversationMemory] is that short-term memory:
 * one slot per kind of thing a follow-up message might refer back to.
 *
 * ## Why "last X" rather than a full history
 * A user who says "usko" almost always means the most recent contact they
 * discussed, not some contact from ten messages ago. Modelling a full history
 * here would invite ambiguity a single slot avoids by construction — there is
 * only ever one candidate for "usko" to resolve to. [ai.memory.ReferenceResolver]
 * depends on that being unambiguous.
 *
 * ## Lifetime
 * Process-scoped, one instance per conversation, replaced (never mutated) as
 * `data class` `copy()` calls after every completed action —
 * [ai.memory.ConversationMemoryUpdater] is where those updates happen.
 *
 * ## Dependencies
 * [Contact]. Pure Kotlin — no Android, no tool or intent types, so this can be
 * read and tested without either.
 */
data class ConversationMemory(
    /** The most recently resolved or discussed person. */
    val lastContact: Contact? = null,

    /** The most recently created or referenced reminder. */
    val lastReminder: ReminderMemory? = null,

    /** The most recently created or referenced calendar event. */
    val lastCalendarEvent: CalendarEventMemory? = null,

    /** The most recently composed email. */
    val lastEmail: EmailMemory? = null,

    /** The most recently prepared WhatsApp message. */
    val lastWhatsAppAction: WhatsAppMemory? = null,

    /** The most recently created or referenced task (Day 06). */
    val lastTask: TaskMemory? = null,

    /** The most recently created or referenced meeting note (Day 06). */
    val lastMeeting: MeetingMemory? = null,

    /**
     * The name of the person last mentioned, even when it never resolved to a
     * [Contact] — a failed or ambiguous lookup still tells a follow-up ("usko")
     * who "them" was supposed to be.
     */
    val lastReferencedPerson: String? = null,

    /** The most recently discussed date or time, as an absolute instant. */
    val lastReferencedDateTimeMillis: Long? = null,

    /** When this memory was last updated. Diagnostics only. */
    val updatedAtMillis: Long = 0L,
) {
    /** A conversation with nothing remembered yet. */
    companion object {
        val EMPTY = ConversationMemory()
    }
}

/**
 * Enough to update or cancel a reminder without asking the user to repeat its
 * details — [AndroidReminderTool][com.softwaremine.dps.data.android.tool.AndroidReminderTool]'s
 * `update_reminder`/`cancel_reminder` operations are addressed by [id] alone.
 */
data class ReminderMemory(
    val id: Int,
    val title: String,
    val triggerAtMillis: Long,
)

/**
 * Enough to update or cancel a calendar event by [id], mirroring [ReminderMemory].
 */
data class CalendarEventMemory(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** What was last emailed, so "usko" after composing an email resolves sensibly. */
data class EmailMemory(
    val recipientName: String?,
    val recipientAddress: String?,
    val subject: String,
)

/** What was last drafted for WhatsApp. Nothing here implies anything was sent. */
data class WhatsAppMemory(
    val recipientName: String?,
    val recipientPhone: String?,
    val message: String,
)

/**
 * Enough to update, complete or cancel a task by [id] without repeating its
 * title (Day 06), mirroring [ReminderMemory].
 */
data class TaskMemory(
    val id: Int,
    val title: String,
)

/** The most recently discussed meeting, so a follow-up question can name which one (Day 06). */
data class MeetingMemory(
    val id: Int,
    val title: String,
)
