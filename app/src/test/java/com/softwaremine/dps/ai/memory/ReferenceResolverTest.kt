package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.memory.CalendarEventMemory
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.ReminderMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of memory-backed reference resolution (Day 05 Phase E).
 *
 * ## What this proves
 * That the three named example follow-ups actually resolve against memory —
 * not just that the vocabulary matches in isolation. Each demo message from
 * the Phase E brief has a corresponding test here.
 */
class ReferenceResolverTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val resolver = ReferenceResolver(zone = zone)

    private val abdul = Contact(id = "42", displayName = "Abdul", phoneNumbers = listOf("+923001234567"))

    private fun intent(
        type: IntentType,
        parameters: IntentParameters = IntentParameters(),
        action: IntentAction = IntentAction.CREATE,
    ) = DpsIntent(type = type, parameters = parameters, action = action)

    // -----------------------------------------------------------------
    // Person pronouns
    // -----------------------------------------------------------------

    @Test
    fun `usko resolves to the last contact`() {
        val memory = ConversationMemory(lastContact = abdul)
        val resolved = resolver.resolve(
            "Usko WhatsApp bhi bhej do.",
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "on my way")),
            memory,
        )

        assertEquals("Abdul", resolved.parameters.person)
    }

    @Test
    fun `unhe and unko are also recognised`() {
        val memory = ConversationMemory(lastContact = abdul)

        listOf("unhe bata do", "unko message karo").forEach { text ->
            val resolved = resolver.resolve(
                text,
                intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "x")),
                memory,
            )
            assertEquals("'$text'", "Abdul", resolved.parameters.person)
        }
    }

    @Test
    fun `falls back to the referenced name when no contact resolved`() {
        val memory = ConversationMemory(lastContact = null, lastReferencedPerson = "Bilal")
        val resolved = resolver.resolve(
            "usko phir se message karo",
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "x")),
            memory,
        )

        assertEquals("Bilal", resolved.parameters.person)
    }

    @Test
    fun `does not touch person when nothing is remembered`() {
        val resolved = resolver.resolve(
            "usko message karo",
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "x")),
            ConversationMemory.EMPTY,
        )

        assertNull(resolved.parameters.person)
    }

    @Test
    fun `an explicit person is never overwritten`() {
        val memory = ConversationMemory(lastContact = abdul)
        val resolved = resolver.resolve(
            "usko message karo",
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(person = "Sara", message = "x")),
            memory,
        )

        assertEquals("Sara", resolved.parameters.person)
    }

    @Test
    fun `no pronoun cue means no resolution attempted`() {
        val memory = ConversationMemory(lastContact = abdul)
        val resolved = resolver.resolve(
            "message Bilal about the invoice",
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "x")),
            memory,
        )

        assertNull(resolved.parameters.person)
    }

    // -----------------------------------------------------------------
    // Target resolution — reminders
    // -----------------------------------------------------------------

    @Test
    fun `an explicit reminder cue resolves the target id`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 1001, title = "call the bank", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "us reminder ko cancel kar do",
            intent(IntentType.REMINDER, action = IntentAction.CANCEL),
            memory,
        )

        assertEquals("1001", resolved.parameters.targetId)
    }

    @Test
    fun `an update with no new title falls back to the last reminder`() {
        // No explicit reminder-cue phrase in the text at all, but action is
        // UPDATE and nothing new is being named, so the fallback applies.
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 2002, title = "meeting", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "30 minute pehle kar do",
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("2002", resolved.parameters.targetId)
    }

    /**
     * On-device regression: the model can extract *something* into `message`
     * even for a pure reschedule ("30 minute pehle kar do" read as a message
     * body) — when that happens the fallback in the previous test does not
     * apply, and only an explicit cue phrase saves the resolution. "Uska
     * reminder" is exactly that cue, and was missing from [ReferenceResolver]'s
     * vocabulary until this was caught running the real classifier on-device.
     */
    @Test
    fun `uska reminder resolves the target even when the model also filled another field`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 5005, title = "call the bank", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "uska reminder 30 minute pehle kar do",
            intent(
                IntentType.REMINDER,
                IntentParameters(message = "30 minute pehle"),
                action = IntentAction.UPDATE,
            ),
            memory,
        )

        assertEquals("5005", resolved.parameters.targetId)
    }

    @Test
    fun `a create action never resolves a target id even with reminder words present`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 3003, title = "old", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "set a reminder for tomorrow",
            intent(IntentType.REMINDER, IntentParameters(title = "new one"), action = IntentAction.CREATE),
            memory,
        )

        assertNull(resolved.parameters.targetId)
    }

    @Test
    fun `an explicit target id from clarification merge is never overwritten`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 4004, title = "x", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "cancel it",
            intent(IntentType.REMINDER, IntentParameters(targetId = "9999"), action = IntentAction.CANCEL),
            memory,
        )

        assertEquals("9999", resolved.parameters.targetId)
    }

    @Test
    fun `nothing to resolve against leaves the target id unset`() {
        val resolved = resolver.resolve(
            "cancel the reminder",
            intent(IntentType.REMINDER, action = IntentAction.CANCEL),
            ConversationMemory.EMPTY,
        )

        assertNull(resolved.parameters.targetId)
    }

    // -----------------------------------------------------------------
    // Target resolution — calendar events
    // -----------------------------------------------------------------

    @Test
    fun `kal wali meeting resolves the last calendar event`() {
        val memory = ConversationMemory(
            lastCalendarEvent = CalendarEventMemory(id = 55, title = "standup", startMillis = 0L, endMillis = 0L),
        )
        val resolved = resolver.resolve(
            "kal wali meeting ko Friday kar do",
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("55", resolved.parameters.targetId)
    }

    @Test
    fun `a reminder cue does not resolve a calendar event`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 7, title = "x", triggerAtMillis = 0L),
            lastCalendarEvent = CalendarEventMemory(id = 8, title = "y", startMillis = 0L, endMillis = 0L),
        )
        val resolved = resolver.resolve(
            "us reminder ko cancel kar do",
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.CANCEL),
            memory,
        )

        // The model classified this as CALENDAR_EVENT, but the user's own
        // words name a reminder, not an event. Classification alone is not
        // sufficient evidence — this must stay unresolved so
        // ClarificationEngine asks, rather than silently cancelling the
        // wrong thing.
        assertNull(resolved.parameters.targetId)
    }

    @Test
    fun `a task cue does not resolve a calendar event`() {
        val memory = ConversationMemory(
            lastTask = com.softwaremine.dps.domain.memory.TaskMemory(id = 9, title = "x"),
            lastCalendarEvent = CalendarEventMemory(id = 10, title = "y", startMillis = 0L, endMillis = 0L),
        )
        val resolved = resolver.resolve(
            "us task ko cancel kar do",
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.CANCEL),
            memory,
        )

        assertNull(resolved.parameters.targetId)
    }

    @Test
    fun `cues for two different entities together leave the calendar event unresolved`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 7, title = "x", triggerAtMillis = 0L),
            lastCalendarEvent = CalendarEventMemory(id = 8, title = "y", startMillis = 0L, endMillis = 0L),
        )
        val resolved = resolver.resolve(
            "us reminder ko us meeting jaisa treat karo",
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.CANCEL),
            memory,
        )

        // A calendar cue ("us meeting") and a reminder cue ("us reminder")
        // both appear — genuinely ambiguous, so this must not guess.
        assertNull(resolved.parameters.targetId)
    }

    @Test
    fun `a bare follow-up with no competing cue still resolves the calendar event`() {
        // "move my last meeting to 5pm" — no listed calendar cue phrase, no
        // title, no reminder or task cue either. This is the case the fix
        // must not break: nothing else it could plausibly mean.
        val memory = ConversationMemory(
            lastCalendarEvent = CalendarEventMemory(id = 20, title = "standup", startMillis = 0L, endMillis = 0L),
        )
        val resolved = resolver.resolve(
            "move my last meeting to 5pm",
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("20", resolved.parameters.targetId)
    }

    // -----------------------------------------------------------------
    // Relative time offsets
    // -----------------------------------------------------------------

    @Test
    fun `30 minutes earlier moves the trigger time back by exactly 30 minutes`() {
        val original = LocalDateTime.of(2026, 8, 7, 16, 0).atZone(zone).toInstant().toEpochMilli()
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 10, title = "call the bank", triggerAtMillis = original),
        )
        val resolved = resolver.resolve(
            "uska reminder 30 minute pehle kar do",
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("2026-08-07", resolved.parameters.date)
        assertEquals("15:30", resolved.parameters.time)
    }

    @Test
    fun `later offsets move the trigger time forward`() {
        val original = LocalDateTime.of(2026, 8, 7, 16, 0).atZone(zone).toInstant().toEpochMilli()
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 11, title = "x", triggerAtMillis = original),
        )
        val resolved = resolver.resolve(
            "uska reminder 15 minute baad kar do",
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("16:15", resolved.parameters.time)
    }

    @Test
    fun `an hour offset is also understood`() {
        val original = LocalDateTime.of(2026, 8, 7, 16, 0).atZone(zone).toInstant().toEpochMilli()
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 12, title = "x", triggerAtMillis = original),
        )
        val resolved = resolver.resolve(
            "uska reminder 1 hour pehle kar do",
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            memory,
        )

        assertEquals("15:00", resolved.parameters.time)
    }

    @Test
    fun `no offset phrase leaves date and time untouched`() {
        val memory = ConversationMemory(
            lastReminder = ReminderMemory(id = 13, title = "x", triggerAtMillis = 0L),
        )
        val resolved = resolver.resolve(
            "cancel the reminder",
            intent(IntentType.REMINDER, action = IntentAction.CANCEL),
            memory,
        )

        assertNull(resolved.parameters.date)
        assertNull(resolved.parameters.time)
    }

    @Test
    fun `an offset without a resolved reminder target changes nothing`() {
        val resolved = resolver.resolve(
            "uska reminder 30 minute pehle kar do",
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            ConversationMemory.EMPTY,
        )

        assertNull(resolved.parameters.date)
        assertNull(resolved.parameters.time)
    }

    // -----------------------------------------------------------------
    // No-op behaviour
    // -----------------------------------------------------------------

    @Test
    fun `an intent needing no enrichment is returned unchanged`() {
        val original = intent(
            IntentType.REMINDER,
            IntentParameters(title = "call the bank", time = "16:00"),
        )
        val resolved = resolver.resolve("remind me to call the bank at 4", original, ConversationMemory.EMPTY)

        assertSame(original, resolved)
    }
}
