package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.memory.CalendarEventMemory
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of memory updates after a tool call (Day 05 Phase E).
 *
 * Every `data` key asserted against here is one confirmed present on the real
 * tool implementations (`AndroidReminderTool`, `AndroidCalendarTool`,
 * `AndroidContactsTool`, `PrepareWhatsAppMessageTool`, `PrepareEmailTool`) —
 * a mismatch here would mean memory silently stops updating on-device while
 * every test still passes against an invented key name.
 */
class ConversationMemoryUpdaterTest {

    private val updater = ConversationMemoryUpdater()

    private fun intent(
        type: IntentType,
        parameters: IntentParameters = IntentParameters(),
        action: IntentAction = IntentAction.CREATE,
    ) = DpsIntent(type = type, parameters = parameters, action = action)

    // -----------------------------------------------------------------
    // Only Success updates memory
    // -----------------------------------------------------------------

    @Test
    fun `a failure never changes memory`() {
        val memory = ConversationMemory.EMPTY
        val result = updater.remember(
            memory,
            intent(IntentType.REMINDER, IntentParameters(title = "x", time = "16:00")),
            ToolId.REMINDER,
            ToolResult.Failure("no such reminder"),
            nowMillis = 1L,
        )

        assertSame(memory, result)
    }

    @Test
    fun `permission required never changes memory`() {
        val memory = ConversationMemory.EMPTY
        val result = updater.remember(
            memory,
            intent(IntentType.CONTACT_LOOKUP, IntentParameters(person = "Abdul")),
            ToolId.CONTACTS,
            ToolResult.PermissionRequired(emptyList(), "x"),
            nowMillis = 1L,
        )

        assertSame(memory, result)
    }

    // -----------------------------------------------------------------
    // Reminders
    // -----------------------------------------------------------------

    @Test
    fun `a created reminder is remembered`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.REMINDER, IntentParameters(title = "call the bank", time = "16:00")),
            ToolId.REMINDER,
            ToolResult.Success("Reminder set.", mapOf("reminder_id" to "1001", "trigger_at" to "999", "exact" to "true")),
            nowMillis = 5L,
        )

        assertEquals(1001, result.lastReminder?.id)
        assertEquals("call the bank", result.lastReminder?.title)
        assertEquals(999L, result.lastReminder?.triggerAtMillis)
        assertEquals(999L, result.lastReferencedDateTimeMillis)
        assertEquals(5L, result.updatedAtMillis)
    }

    /**
     * On-device regression: "Kal 4 baje Abdul ko yaad dila dena" names Abdul
     * without REMINDER ever resolving him as a contact — a later "usko" had
     * nothing to resolve against until this was caught running the full demo
     * sequence on-device.
     */
    @Test
    fun `a reminder that names a person still remembers them as referenced`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.REMINDER, IntentParameters(person = "Abdul", title = "call Abdul", time = "16:00")),
            ToolId.REMINDER,
            ToolResult.Success("Reminder set.", mapOf("reminder_id" to "1001", "trigger_at" to "999", "exact" to "true")),
            nowMillis = 5L,
        )

        assertEquals("Abdul", result.lastReferencedPerson)
        // REMINDER still never resolves a Contact — only the name is remembered.
        assertNull(result.lastContact)
    }

    @Test
    fun `an updated reminder keeps its previous title when none was given`() {
        val before = ConversationMemory(
            lastReminder = com.softwaremine.dps.domain.memory.ReminderMemory(1001, "call the bank", 100L),
        )
        val result = updater.remember(
            before,
            intent(IntentType.REMINDER, action = IntentAction.UPDATE),
            ToolId.REMINDER,
            ToolResult.Success("Reminder updated.", mapOf("reminder_id" to "1001", "trigger_at" to "5000", "exact" to "true")),
            nowMillis = 5L,
        )

        assertEquals("call the bank", result.lastReminder?.title)
        assertEquals(5000L, result.lastReminder?.triggerAtMillis)
    }

    @Test
    fun `a cancelled reminder is forgotten`() {
        val before = ConversationMemory(
            lastReminder = com.softwaremine.dps.domain.memory.ReminderMemory(1001, "x", 100L),
        )
        val result = updater.remember(
            before,
            intent(IntentType.REMINDER, action = IntentAction.CANCEL),
            ToolId.REMINDER,
            ToolResult.Success("Reminder cancelled.", mapOf("reminder_id" to "1001")),
            nowMillis = 5L,
        )

        assertNull(result.lastReminder)
    }

    // -----------------------------------------------------------------
    // Calendar
    // -----------------------------------------------------------------

    @Test
    fun `a created event is remembered with parsed start and end`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.CALENDAR_EVENT, IntentParameters(title = "standup")),
            ToolId.CALENDAR,
            ToolResult.Success(
                "Added \"standup\" to your calendar.",
                mapOf(
                    "event_id" to "55",
                    "calendar" to "Personal",
                    "start" to "2026-08-07T09:00",
                    "end" to "2026-08-07T09:30",
                ),
            ),
            nowMillis = 9L,
        )

        assertEquals(55L, result.lastCalendarEvent?.id)
        assertEquals("standup", result.lastCalendarEvent?.title)
    }

    /**
     * `update_event`'s own `Success.data` carries only `event_id` — the new
     * start has to be re-derived from the executed intent the same way
     * [ToolSelector] built the call, which is why [updater] is constructed
     * with a clock-pinned selector for this test.
     */
    @Test
    fun `an updated event preserves its original duration when only the start changed`() {
        val zone = ZoneId.of("Asia/Karachi")
        val fixedNow = LocalDateTime.of(2026, 8, 6, 14, 30)
        val updater = ConversationMemoryUpdater(zone = zone, toolSelector = ToolSelector(zone, now = { fixedNow }))
        val before = ConversationMemory(
            lastCalendarEvent = CalendarEventMemory(
                id = 55,
                title = "standup",
                startMillis = fixedNow.atZone(zone).toInstant().toEpochMilli(),
                endMillis = fixedNow.plusMinutes(30).atZone(zone).toInstant().toEpochMilli(),
            ),
        )

        val result = updater.remember(
            before,
            intent(IntentType.CALENDAR_EVENT, IntentParameters(time = "17:00"), IntentAction.UPDATE),
            ToolId.CALENDAR,
            ToolResult.Success("Updated the event.", mapOf("event_id" to "55")),
            nowMillis = 9L,
        )

        val expectedStart = LocalDateTime.of(2026, 8, 6, 17, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, result.lastCalendarEvent?.startMillis)
        // 30-minute length preserved, not left at the old absolute end time.
        assertEquals(30 * 60_000L, (result.lastCalendarEvent?.endMillis ?: 0) - expectedStart)
        assertEquals("standup", result.lastCalendarEvent?.title)
    }

    @Test
    fun `a cancelled event is forgotten`() {
        val before = ConversationMemory(
            lastCalendarEvent = CalendarEventMemory(id = 55, title = "standup", startMillis = 1L, endMillis = 2L),
        )
        val result = updater.remember(
            before,
            intent(IntentType.CALENDAR_EVENT, action = IntentAction.CANCEL),
            ToolId.CALENDAR,
            ToolResult.Success("Deleted the event.", mapOf("event_id" to "55")),
            nowMillis = 9L,
        )

        assertNull(result.lastCalendarEvent)
    }

    // -----------------------------------------------------------------
    // Contacts
    // -----------------------------------------------------------------

    @Test
    fun `a single contact match is remembered`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.CONTACT_LOOKUP, IntentParameters(person = "Abdul")),
            ToolId.CONTACTS,
            ToolResult.Success(
                "Found Abdul.",
                mapOf("contact_id" to "42", "name" to "Abdul", "phone" to "+923001234567"),
            ),
            nowMillis = 3L,
        )

        assertEquals("42", result.lastContact?.id)
        assertEquals("Abdul", result.lastContact?.displayName)
        assertEquals(listOf("+923001234567"), result.lastContact?.phoneNumbers)
        assertEquals("Abdul", result.lastReferencedPerson)
    }

    @Test
    fun `an ambiguous match remembers only the searched name, never a guessed contact`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.CONTACT_LOOKUP, IntentParameters(person = "Ali")),
            ToolId.CONTACTS,
            ToolResult.Success(
                "2 contacts match \"Ali\". Which one?",
                mapOf("count" to "2", "ambiguous" to "true"),
            ),
            nowMillis = 3L,
        )

        assertNull(result.lastContact)
        assertEquals("Ali", result.lastReferencedPerson)
    }

    // -----------------------------------------------------------------
    // WhatsApp and email
    // -----------------------------------------------------------------

    @Test
    fun `a prepared whatsapp draft is remembered and never implies it was sent`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.WHATSAPP_MESSAGE, IntentParameters(person = "Abdul", message = "on my way")),
            ToolId.WHATSAPP,
            ToolResult.Success(
                "WhatsApp is open with your message to Abdul.",
                mapOf(
                    "recipient" to "Abdul",
                    "phone" to "923001234567",
                    "message" to "on my way",
                    "sent" to "false",
                    "awaiting_user_confirmation" to "true",
                ),
            ),
            nowMillis = 4L,
        )

        assertEquals("Abdul", result.lastWhatsAppAction?.recipientName)
        assertEquals("923001234567", result.lastWhatsAppAction?.recipientPhone)
        assertEquals("on my way", result.lastWhatsAppAction?.message)
        assertEquals("Abdul", result.lastReferencedPerson)
    }

    @Test
    fun `a composed email is remembered`() {
        val result = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.EMAIL_MESSAGE, IntentParameters(email = "a@b.com", message = "attached")),
            ToolId.GMAIL,
            ToolResult.Success(
                "Your email to a@b.com is ready.",
                mapOf("recipient" to "a@b.com", "to" to "a@b.com", "subject" to "Invoice"),
            ),
            nowMillis = 4L,
        )

        assertEquals("a@b.com", result.lastEmail?.recipientAddress)
        assertEquals("Invoice", result.lastEmail?.subject)
    }

    // -----------------------------------------------------------------
    // Not a memory slot
    // -----------------------------------------------------------------

    @Test
    fun `notifications are not a memory slot`() {
        val memory = ConversationMemory.EMPTY
        val result = updater.remember(
            memory,
            intent(IntentType.NOTIFICATION, IntentParameters(message = "x")),
            ToolId.NOTIFICATION,
            ToolResult.Success("Done.", mapOf("notification_id" to "1", "channel" to "assistant")),
            nowMillis = 1L,
        )

        assertSame(memory, result)
    }
}
