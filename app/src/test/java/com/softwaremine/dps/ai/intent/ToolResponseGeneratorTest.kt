package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the natural response generator (Day 05 Phase D).
 *
 * ## The failure these tests exist to prevent
 * Phase C made sending a WhatsApp message or an email without the user pressing
 * send structurally impossible. That guarantee can still be broken at the last
 * step — by *claiming* something was sent.
 *
 * Templating the wording is what removes the possibility, and the sweep at the
 * bottom of this file is what proves it holds for every result variant against
 * every intent, rather than for the handful anyone thought to write by hand.
 */
class ToolResponseGeneratorTest {

    private val generator = ToolResponseGenerator()

    private fun intent(type: IntentType) = DpsIntent(type = type, parameters = IntentParameters())

    // -----------------------------------------------------------------
    // Ordinary outcomes
    // -----------------------------------------------------------------

    @Test
    fun `a success speaks in the tool's own words`() {
        val reply = generator.describe(
            ToolResult.Success("Reminder set for 4:00 PM."),
            intent(IntentType.REMINDER),
        )

        assertEquals("Reminder set for 4:00 PM.", reply)
    }

    @Test
    fun `a success with no summary still says something specific`() {
        assertEquals(
            "Reminder set.",
            generator.describe(ToolResult.Success(""), intent(IntentType.REMINDER)),
        )
        assertEquals(
            "Added to your calendar.",
            generator.describe(ToolResult.Success(""), intent(IntentType.CALENDAR_EVENT)),
        )
        assertEquals(
            "Your message is ready to send.",
            generator.describe(ToolResult.Success(""), intent(IntentType.WHATSAPP_MESSAGE)),
        )
    }

    @Test
    fun `a retryable failure offers to try again`() {
        val reply = generator.describe(
            ToolResult.Failure("I couldn't reach your calendar.", retryable = true),
            intent(IntentType.CALENDAR_EVENT),
        )

        assertTrue(reply.startsWith("I couldn't reach your calendar."))
        assertTrue("A retryable failure should offer a retry: $reply", reply.contains("try again"))
    }

    @Test
    fun `a permanent failure does not offer a pointless retry`() {
        val reply = generator.describe(
            ToolResult.Failure("I couldn't find anyone called Abdul.", retryable = false),
            intent(IntentType.CONTACT_LOOKUP),
        )

        assertEquals("I couldn't find anyone called Abdul.", reply)
    }

    @Test
    fun `a cancellation is acknowledged without apology`() {
        val reply = generator.describe(ToolResult.Cancelled(), intent(IntentType.REMINDER))

        assertTrue(reply.isNotBlank())
        assertTrue(!reply.lowercase().contains("error"))
    }

    @Test
    fun `an unsupported operation explains the OS version when that is the cause`() {
        val reply = generator.describe(
            ToolResult.Unsupported("I can't do that here.", minApiLevel = 33),
            intent(IntentType.NOTIFICATION),
        )

        assertTrue(reply.contains("newer version of Android"))
    }

    @Test
    fun `a timeout says so plainly`() {
        val reply = generator.describe(
            ToolResult.Timeout(elapsedMillis = 10_500, limitMillis = 10_000),
            intent(IntentType.CALENDAR_EVENT),
        )

        assertTrue(reply.lowercase().contains("longer"))
        // Raw millisecond counts are internals, not something a secretary says.
        assertTrue(!reply.contains("10500"))
        assertTrue(!reply.contains("10000"))
    }

    // -----------------------------------------------------------------
    // Permission recovery
    // -----------------------------------------------------------------

    @Test
    fun `a permission request names the capability and promises to resume`() {
        val reply = generator.describe(
            ToolResult.PermissionRequired(
                listOf(DpsPermission.READ_CONTACTS),
                rationale = "internal rationale",
            ),
            intent(IntentType.WHATSAPP_MESSAGE),
        )

        assertTrue("Should name the capability: $reply", reply.contains("your contacts"))
        assertTrue("Should ask rather than act: $reply", reply.contains("Would you like"))
        assertTrue("Should promise resumption: $reply", reply.contains("pick up where we left off"))
    }

    @Test
    fun `calendar permissions are described as the calendar`() {
        listOf(DpsPermission.READ_CALENDAR, DpsPermission.WRITE_CALENDAR).forEach { permission ->
            val reply = generator.describe(
                ToolResult.PermissionRequired(listOf(permission), "x"),
                intent(IntentType.CALENDAR_EVENT),
            )
            assertTrue("$permission: $reply", reply.contains("your calendar"))
        }
    }

    @Test
    fun `the purpose stated matches what the user actually asked for`() {
        val reply = generator.describe(
            ToolResult.PermissionRequired(listOf(DpsPermission.SCHEDULE_EXACT_ALARM), "x"),
            intent(IntentType.REMINDER),
        )

        assertTrue(reply.contains("set that reminder"))
    }

    // -----------------------------------------------------------------
    // The two invariants that matter
    // -----------------------------------------------------------------

    /**
     * `ToolResult.Error.cause` carries an exception class and message. It tells
     * the user nothing they can act on and exposes internals AI Rules 1 and 2
     * forbid showing.
     */
    @Test
    fun `an error never surfaces the underlying exception`() {
        val reply = generator.describe(
            ToolResult.Error(
                message = "The tool failed unexpectedly.",
                cause = "IllegalStateException: cursor closed at ContactsProvider.query",
            ),
            intent(IntentType.CONTACT_LOOKUP),
        )

        assertTrue(reply, !reply.contains("IllegalStateException"))
        assertTrue(reply, !reply.contains("cursor"))
        assertTrue(reply, !reply.contains("ContactsProvider"))
        assertTrue(reply.isNotBlank())
    }

    /**
     * The sweep. Every variant against every intent.
     *
     * Two properties are asserted for all of them: the reply is never empty —
     * silence after an action is indistinguishable from a hang — and it never
     * claims something was sent, because nothing in this system sends anything.
     */
    @Test
    fun `no reply ever claims a message was sent, and none is ever empty`() {
        val results = listOf(
            ToolResult.Success("Your message to Abdul is ready in WhatsApp."),
            ToolResult.Success(""),
            ToolResult.Failure("I couldn't find that contact.", retryable = false),
            ToolResult.Failure("Your calendar was busy.", retryable = true),
            ToolResult.PermissionRequired(listOf(DpsPermission.READ_CONTACTS), "x"),
            ToolResult.PermissionRequired(listOf(DpsPermission.POST_NOTIFICATIONS), "x"),
            ToolResult.Cancelled(),
            ToolResult.Unsupported("Not available.", minApiLevel = null),
            ToolResult.Unsupported("Not available.", minApiLevel = 33),
            ToolResult.Timeout(1, 1),
            ToolResult.Error("Something went wrong.", cause = "boom"),
        )

        val claimsOfSending = listOf(
            "i've sent", "i have sent", "i sent", "message sent", "email sent",
            "has been sent", "was sent", "delivered",
        )

        IntentType.entries.forEach { type ->
            results.forEach { result ->
                val reply = generator.describe(result, intent(type))

                assertTrue(
                    "Empty reply for $type / ${result::class.simpleName}",
                    reply.isNotBlank(),
                )
                claimsOfSending.forEach { claim ->
                    assertTrue(
                        "Reply for $type / ${result::class.simpleName} claimed sending: $reply",
                        !reply.lowercase().contains(claim),
                    )
                }
            }
        }
    }

    /** No reply may name a tool, an operation or an Android permission string. */
    @Test
    fun `no reply exposes internal architecture`() {
        val forbidden = listOf(
            "android.permission", "ToolResult", "ToolCall", "toolId",
            "prepare_message", "create_reminder", "compose_email", "exception",
        )

        val results = listOf(
            ToolResult.Success(""),
            ToolResult.PermissionRequired(listOf(DpsPermission.WRITE_CALENDAR), "x"),
            ToolResult.Unsupported("Not available.", minApiLevel = 33),
            ToolResult.Timeout(1, 1),
            ToolResult.Error("x", cause = "java.lang.IllegalStateException"),
        )

        IntentType.entries.forEach { type ->
            results.forEach { result ->
                val reply = generator.describe(result, intent(type)).lowercase()
                forbidden.forEach { term ->
                    assertTrue(
                        "Reply for $type leaked '$term'",
                        !reply.contains(term.lowercase()),
                    )
                }
            }
        }
    }
}
