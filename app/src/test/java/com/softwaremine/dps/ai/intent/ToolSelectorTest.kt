package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.tool.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Verification of the tool selection engine (Day 05 Phase D).
 *
 * ## Why the clock is injected
 * "Remind me at 4" resolves differently at 2pm and at 6pm. A test that used the
 * wall clock would pass all morning and fail after lunch, which is worse than
 * no test at all. [FIXED_NOW] pins it.
 *
 * ## What is actually being verified
 * Two things the rest of the pipeline depends on and cannot check for itself:
 * that each intent reaches the right tool and operation, and that date/time
 * combination follows the stated rules exactly. A reminder landing an hour or a
 * day out is indistinguishable, to the user, from DPS not working.
 */
class ToolSelectorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /** Thursday 6 August 2026, 14:30 local. */
    private val fixedNow: LocalDateTime = LocalDateTime.of(2026, 8, 6, 14, 30)

    private val selector = ToolSelector(zone = zone, now = { fixedNow })

    private fun intent(type: IntentType, parameters: IntentParameters) =
        DpsIntent(type = type, parameters = parameters)

    private fun epochOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    // -----------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------

    @Test
    fun `a reminder routes to the reminder tool`() {
        val call = selector.select(
            intent(IntentType.REMINDER, IntentParameters(title = "call the bank", time = "16:00")),
        )!!

        assertEquals(ToolId.REMINDER, call.toolId)
        assertEquals("create_reminder", call.operation)
        assertEquals("call the bank", call.arguments["title"])
    }

    @Test
    fun `a calendar event routes to the calendar tool without an end time`() {
        val call = selector.select(
            intent(
                IntentType.CALENDAR_EVENT,
                IntentParameters(title = "standup", date = "2026-08-07", time = "09:30"),
            ),
        )!!

        assertEquals(ToolId.CALENDAR, call.toolId)
        assertEquals("create_event", call.operation)
        assertEquals(
            epochOf(LocalDateTime.of(2026, 8, 7, 9, 30)).toString(),
            call.arguments["start"],
        )
        // The tool defaults to one hour; refusing an event because the user did
        // not say when it ends would be worse than assuming.
        assertNull(call.arguments["end"])
    }

    @Test
    fun `a notification routes to the notification tool`() {
        val call = selector.select(
            intent(IntentType.NOTIFICATION, IntentParameters(message = "stand up")),
        )!!

        assertEquals(ToolId.NOTIFICATION, call.toolId)
        assertEquals("notify", call.operation)
    }

    @Test
    fun `a notification create routes to notify`() {
        val call = selector.select(
            intent(
                IntentType.NOTIFICATION,
                IntentParameters(title = "stand up"),
                action = IntentAction.CREATE,
            ),
        )!!

        assertEquals(ToolId.NOTIFICATION, call.toolId)
        assertEquals("notify", call.operation)
        assertEquals("stand up", call.arguments["title"])
    }

    @Test
    fun `a notification cancel routes to cancel, never to notify`() {
        val call = selector.select(
            intent(
                IntentType.NOTIFICATION,
                IntentParameters(targetId = "42"),
                action = IntentAction.CANCEL,
            ),
        )!!

        assertEquals(ToolId.NOTIFICATION, call.toolId)
        assertEquals("cancel", call.operation)
        assertEquals("42", call.arguments["id"])
    }

    @Test
    fun `an unsupported notification update never falls through to notify`() {
        val call = selector.select(
            intent(
                IntentType.NOTIFICATION,
                IntentParameters(title = "stand up"),
                action = IntentAction.UPDATE,
            ),
        )!!

        assertEquals(ToolId.NOTIFICATION, call.toolId)
        // "update" is deliberately not an operation AndroidNotificationTool
        // implements — the executor's own Unsupported fallback reports that
        // honestly. What matters here is what it must never be: "notify",
        // which would silently post something the user never asked for.
        assertTrue(call.operation != "notify")
    }

    @Test
    fun `a contact lookup routes to the contacts tool`() {
        val call = selector.select(
            intent(IntentType.CONTACT_LOOKUP, IntentParameters(person = "Abdul")),
        )!!

        assertEquals(ToolId.CONTACTS, call.toolId)
        assertEquals("find_contact", call.operation)
        assertEquals("Abdul", call.arguments["name"])
    }

    /**
     * The most important routing assertion in this file.
     *
     * Phase C guarantees nothing is sent without the user pressing send. That
     * guarantee is only as strong as the operation named here: `send_message`
     * would bypass the confirmation flow entirely.
     */
    @Test
    fun `a WhatsApp message routes to the preparing operation and never to sending`() {
        val call = selector.select(
            intent(
                IntentType.WHATSAPP_MESSAGE,
                IntentParameters(person = "Sara", message = "on my way"),
            ),
        )!!

        assertEquals(ToolId.WHATSAPP, call.toolId)
        assertEquals("prepare_message", call.operation)
        assertTrue(
            "Selector must never name a sending operation",
            !call.operation.contains("send"),
        )
        assertEquals("Sara", call.arguments["contact"])
        assertEquals("on my way", call.arguments["message"])
    }

    @Test
    fun `an email routes to composing and never to sending`() {
        val call = selector.select(
            intent(
                IntentType.EMAIL_MESSAGE,
                IntentParameters(email = "a@b.com", title = "Invoice", message = "attached"),
            ),
        )!!

        assertEquals(ToolId.GMAIL, call.toolId)
        assertEquals("compose_email", call.operation)
        assertTrue(!call.operation.contains("send"))
        assertEquals("a@b.com", call.arguments["to"])
        assertEquals("Invoice", call.arguments["subject"])
        assertEquals("attached", call.arguments["body"])
    }

    @Test
    fun `conversation maps to no tool`() {
        assertNull(selector.select(intent(IntentType.CONVERSATION, IntentParameters())))
    }

    /**
     * The call-safety equivalent of the WhatsApp test above: the operation
     * named here must never be one that dials automatically. `place_call`
     * only opens the dialer — see [com.softwaremine.dps.data.android.tool.AndroidCallTool]'s
     * doc for why `ACTION_DIAL`, never `ACTION_CALL`, is the only capability
     * this codebase has.
     */
    @Test
    fun `a call routes to the phone tool without a message argument`() {
        val call = selector.select(
            intent(
                IntentType.CALL_CONTACT,
                IntentParameters(person = "Bilal", phone = "+923001234567"),
            ),
        )!!

        assertEquals(ToolId.PHONE, call.toolId)
        assertEquals("place_call", call.operation)
        assertEquals("Bilal", call.arguments["contact"])
        assertEquals("+923001234567", call.arguments["phone"])
        assertNull("A call carries no message body", call.arguments["message"])
    }

    @Test
    fun `a call by phone number alone omits the contact argument`() {
        val call = selector.select(
            intent(IntentType.CALL_CONTACT, IntentParameters(phone = "+923001234567")),
        )!!

        assertEquals("+923001234567", call.arguments["phone"])
        assertNull(call.arguments["contact"])
    }

    @Test
    fun `a phone number is carried through when the user gave one instead of a name`() {
        val call = selector.select(
            intent(
                IntentType.WHATSAPP_MESSAGE,
                IntentParameters(phone = "+923001234567", message = "hi"),
            ),
        )!!

        assertEquals("+923001234567", call.arguments["phone"])
        assertNull(call.arguments["contact"])
    }

    @Test
    fun `a reminder falls back to the message when no title was extracted`() {
        val call = selector.select(
            intent(IntentType.REMINDER, IntentParameters(message = "take medicine", time = "20:00")),
        )!!

        assertEquals("take medicine", call.arguments["title"])
    }

    // -----------------------------------------------------------------
    // Instant resolution
    // -----------------------------------------------------------------

    @Test
    fun `date and time present are combined directly`() {
        val resolved = selector.resolveInstant(
            IntentParameters(date = "2026-08-09", time = "18:45"),
        )

        assertEquals(epochOf(LocalDateTime.of(2026, 8, 9, 18, 45)), resolved)
    }

    @Test
    fun `time only later today means today`() {
        // Now is 14:30, so 16:00 has not happened yet.
        val resolved = selector.resolveInstant(IntentParameters(time = "16:00"))

        assertEquals(epochOf(LocalDateTime.of(2026, 8, 6, 16, 0)), resolved)
    }

    @Test
    fun `time only already past today means tomorrow`() {
        // Now is 14:30, so 09:00 is gone; the user cannot have meant this morning.
        val resolved = selector.resolveInstant(IntentParameters(time = "09:00"))

        assertEquals(epochOf(LocalDateTime.of(2026, 8, 7, 9, 0)), resolved)
    }

    @Test
    fun `date only defaults to nine in the morning`() {
        val resolved = selector.resolveInstant(IntentParameters(date = "2026-09-01"))

        assertEquals(epochOf(LocalDateTime.of(2026, 9, 1, 9, 0)), resolved)
    }

    @Test
    fun `neither date nor time resolves to nothing`() {
        assertNull(selector.resolveInstant(IntentParameters(title = "something")))
    }

    @Test
    fun `an unparseable time resolves to nothing rather than to a guess`() {
        assertNull(selector.resolveInstant(IntentParameters(time = "sometime soon")))
    }

    // -----------------------------------------------------------------
    // Date and time parsing
    // -----------------------------------------------------------------

    @Test
    fun `parses the date shapes models emit`() {
        val expected = LocalDate.of(2026, 8, 9)

        assertEquals(expected, selector.parseDate("2026-08-09"))
        assertEquals(expected, selector.parseDate("2026/08/09"))
        assertEquals(expected, selector.parseDate("09-08-2026"))
        assertEquals(expected, selector.parseDate("09/08/2026"))
        assertEquals(expected, selector.parseDate("  2026-08-09  "))
    }

    /**
     * Relative expressions are the model's job, not this layer's.
     *
     * The model has today's date injected into its prompt and the conversation
     * for context; a parser here would guess worse, and silently.
     */
    @Test
    fun `does not attempt to parse relative dates`() {
        assertNull(selector.parseDate("tomorrow"))
        assertNull(selector.parseDate("next Tuesday"))
        assertNull(selector.parseDate(""))
    }

    @Test
    fun `parses the time shapes models emit`() {
        assertEquals(LocalTime.of(16, 0), selector.parseTime("16:00"))
        assertEquals(LocalTime.of(16, 0), selector.parseTime("16:00:00"))
        assertEquals(LocalTime.of(4, 5), selector.parseTime("4:05"))
        // A bare hour, which models emit often enough to matter.
        assertEquals(LocalTime.of(16, 0), selector.parseTime("16"))
        // A full date-time landing in the time field, which also happens.
        assertEquals(LocalTime.of(18, 30), selector.parseTime("2026-08-09T18:30"))
    }

    @Test
    fun `rejects a time it cannot understand`() {
        assertNull(selector.parseTime("half past four"))
        assertNull(selector.parseTime("25:00"))
        assertNull(selector.parseTime("99"))
        assertNull(selector.parseTime(""))
    }

    // -----------------------------------------------------------------
    // Priority
    // -----------------------------------------------------------------

    @Test
    fun `an urgent notification uses the reminders channel`() {
        listOf("high", "urgent", "important", "URGENT").forEach { priority ->
            val call = selector.select(
                intent(IntentType.NOTIFICATION, IntentParameters(message = "x", priority = priority)),
            )!!
            assertEquals("priority '$priority'", "reminders", call.arguments["channel"])
        }
    }

    @Test
    fun `an ordinary notification uses the assistant channel`() {
        val call = selector.select(
            intent(IntentType.NOTIFICATION, IntentParameters(message = "x", priority = "normal")),
        )!!

        assertEquals("assistant", call.arguments["channel"])
    }

    @Test
    fun `no arguments are emitted for details the user did not give`() {
        val call = selector.select(
            intent(IntentType.REMINDER, IntentParameters(title = "x", time = "16:00")),
        )!!

        // Nothing is invented: absent fields are absent, not empty strings.
        assertNull(call.arguments["body"])
    }

    // -----------------------------------------------------------------
    // Action-based routing (Day 05 Phase E)
    // -----------------------------------------------------------------

    private fun intent(type: IntentType, parameters: IntentParameters, action: IntentAction) =
        DpsIntent(type = type, parameters = parameters, action = action)

    @Test
    fun `an update reminder call is addressed by the remembered target id`() {
        val call = selector.select(
            intent(
                IntentType.REMINDER,
                IntentParameters(targetId = "1001", time = "16:30"),
                IntentAction.UPDATE,
            ),
        )!!

        assertEquals(ToolId.REMINDER, call.toolId)
        assertEquals("update_reminder", call.operation)
        assertEquals("1001", call.arguments["id"])
    }

    @Test
    fun `an update reminder call never invents a title from a blank message`() {
        // Rescheduling must not overwrite the existing title with "" — the
        // tool already keeps it when the argument is absent.
        val call = selector.select(
            intent(
                IntentType.REMINDER,
                IntentParameters(targetId = "1001", time = "16:30"),
                IntentAction.UPDATE,
            ),
        )!!

        assertNull(call.arguments["title"])
    }

    @Test
    fun `an update reminder call carries a new title only when one was given`() {
        val call = selector.select(
            intent(
                IntentType.REMINDER,
                IntentParameters(targetId = "1001", title = "renamed"),
                IntentAction.UPDATE,
            ),
        )!!

        assertEquals("renamed", call.arguments["title"])
    }

    @Test
    fun `a cancel reminder call carries only the id`() {
        val call = selector.select(
            intent(IntentType.REMINDER, IntentParameters(targetId = "1001"), IntentAction.CANCEL),
        )!!

        assertEquals(ToolId.REMINDER, call.toolId)
        assertEquals("cancel_reminder", call.operation)
        assertEquals(setOf("id"), call.arguments.keys)
    }

    @Test
    fun `an update or cancel with no resolved target omits the id rather than guessing`() {
        val update = selector.select(
            intent(IntentType.REMINDER, IntentParameters(time = "16:00"), IntentAction.UPDATE),
        )!!
        val cancel = selector.select(
            intent(IntentType.REMINDER, IntentParameters(), IntentAction.CANCEL),
        )!!

        assertNull(update.arguments["id"])
        assertNull(cancel.arguments["id"])
    }

    @Test
    fun `create still synthesizes a title from the message exactly as before`() {
        val call = selector.select(
            intent(IntentType.REMINDER, IntentParameters(message = "take medicine", time = "20:00"), IntentAction.CREATE),
        )!!

        assertEquals("create_reminder", call.operation)
        assertEquals("take medicine", call.arguments["title"])
    }

    // -----------------------------------------------------------------
    // Calendar action-based routing (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `an update event call is addressed by the remembered target id`() {
        val call = selector.select(
            intent(
                IntentType.CALENDAR_EVENT,
                IntentParameters(targetId = "500", time = "17:00"),
                IntentAction.UPDATE,
            ),
        )!!

        assertEquals(ToolId.CALENDAR, call.toolId)
        assertEquals("update_event", call.operation)
        assertEquals("500", call.arguments["id"])
        assertEquals(
            epochOf(LocalDateTime.of(2026, 8, 6, 17, 0)).toString(),
            call.arguments["start"],
        )
    }

    @Test
    fun `an update event call never invents a title when none was given`() {
        val call = selector.select(
            intent(
                IntentType.CALENDAR_EVENT,
                IntentParameters(targetId = "500", time = "17:00"),
                IntentAction.UPDATE,
            ),
        )!!

        assertNull(call.arguments["title"])
    }

    @Test
    fun `a delete event call carries only the id`() {
        val call = selector.select(
            intent(IntentType.CALENDAR_EVENT, IntentParameters(targetId = "500"), IntentAction.CANCEL),
        )!!

        assertEquals(ToolId.CALENDAR, call.toolId)
        assertEquals("delete_event", call.operation)
        assertEquals(setOf("id"), call.arguments.keys)
    }

    @Test
    fun `an event update or delete with no resolved target omits the id rather than guessing`() {
        val update = selector.select(
            intent(IntentType.CALENDAR_EVENT, IntentParameters(time = "17:00"), IntentAction.UPDATE),
        )!!
        val cancel = selector.select(
            intent(IntentType.CALENDAR_EVENT, IntentParameters(), IntentAction.CANCEL),
        )!!

        assertNull(update.arguments["id"])
        assertNull(cancel.arguments["id"])
    }

    // -----------------------------------------------------------------
    // Calendar Find/List (Phase 5)
    // -----------------------------------------------------------------

    @Test
    fun `a calendar list call forwards the already-resolved date`() {
        val call = selector.select(
            intent(IntentType.CALENDAR_EVENT, IntentParameters(date = "2026-08-07"), IntentAction.LIST),
        )!!

        assertEquals(ToolId.CALENDAR, call.toolId)
        assertEquals("list_events", call.operation)
        assertEquals("2026-08-07", call.arguments["date"])
    }

    @Test
    fun `a calendar list call with no resolved date omits it rather than guessing`() {
        val call = selector.select(
            intent(IntentType.CALENDAR_EVENT, IntentParameters(), IntentAction.LIST),
        )!!

        assertEquals("list_events", call.operation)
        assertNull(call.arguments["date"])
    }
}
