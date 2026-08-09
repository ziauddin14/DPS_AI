package com.softwaremine.dps.data.android.tool

import android.app.NotificationManager
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.notification.DpsNotificationChannel
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.data.android.reminder.ReminderScheduler
import com.softwaremine.dps.data.android.reminder.ReminderStore
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Phase B tools.
 *
 * ## What these assert, and what they cannot
 * Grant state depends on the device and on what the user has previously done,
 * so no test here asserts that a permission *is* granted â€” that would pass or
 * fail for reasons unrelated to the code.
 *
 * What is asserted is that **both permission paths behave correctly**: granted
 * leads to execution, missing leads to `PermissionRequired`, and neither
 * crashes. Where a capability is genuinely observable â€” a created calendar row,
 * a registered notification channel, a pending alarm â€” that is checked directly
 * rather than inferred from a return value.
 */
@RunWith(AndroidJUnit4::class)
class AndroidToolsInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun container() = AiContainer(context)

    /**
     * The permission authority the scheduler consults.
     *
     * Constructed rather than stubbed: the exact-alarm decision must be the one
     * the real device makes, otherwise the fallback test would prove nothing.
     */
    private fun permissions() = AndroidPermissionManager(context, logger)

    // -----------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------

    @Test
    fun phaseBToolsAreRegisteredAsRealImplementations() {
        val registry = container().toolRegistry

        assertTrue(registry.find(ToolId.NOTIFICATION) is AndroidNotificationTool)
        assertTrue(registry.find(ToolId.CALENDAR) is AndroidCalendarTool)
        assertTrue(registry.find(ToolId.REMINDER) is AndroidReminderTool)
    }

    /**
     * Tools outside every implemented phase must remain declarations.
     *
     * ## Why this list shrank
     * It originally also named CONTACTS, WHATSAPP and GMAIL, because Phase B's
     * scope excluded them. Phase C implemented all three, so asserting they are
     * unimplemented became wrong — the assertion described a scope boundary
     * that legitimately moved, not behaviour that regressed.
     *
     * PHONE is named explicitly because it is genuinely still unimplemented and
     * must stay so until its own phase.
     */
    @Test
    fun outOfScopeToolsRemainUnimplemented() {
        val registry = container().toolRegistry

        assertTrue(
            "PHONE must not be implemented yet",
            registry.find(ToolId.PHONE) is UnimplementedTool,
        )

        // Every id registered exactly once, whatever its implementation state.
        assertEquals(ToolId.entries.size, registry.registeredIds().size)
        assertEquals(registry.all().size, registry.all().distinctBy { it.id }.size)
    }

    /**
     * Phase B's own three must still be real implementations.
     *
     * The genuine regression risk: a later phase replacing or shadowing an
     * earlier tool. This asserts that directly rather than inferring it.
     */
    @Test
    fun phaseBToolsRemainImplementedAfterLaterPhases() {
        val registry = container().toolRegistry

        assertTrue(registry.find(ToolId.NOTIFICATION) !is UnimplementedTool)
        assertTrue(registry.find(ToolId.CALENDAR) !is UnimplementedTool)
        assertTrue(registry.find(ToolId.REMINDER) !is UnimplementedTool)
    }

    // -----------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------

    /** Channels must exist before any notification can appear. */
    @Test
    fun notificationChannelsAreCreatedOnDevice() {
        val presenter = NotificationPresenter(context, logger)
        presenter.ensureAllChannels()

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager)

        DpsNotificationChannel.entries.forEach { channel ->
            val created = manager!!.getNotificationChannel(channel.id)
            assertNotNull("Channel ${channel.id} was not created", created)
            assertEquals(channel.importance, created!!.importance)
        }
    }

    /** Re-creating a channel must be safe â€” it is called before every post. */
    @Test
    fun ensureChannelIsIdempotent() {
        val presenter = NotificationPresenter(context, logger)
        repeat(3) { presenter.ensureChannel(DpsNotificationChannel.REMINDERS) }

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager!!.getNotificationChannel(DpsNotificationChannel.REMINDERS.id))
    }

    @Test
    fun notifyRequiresATitleAndReportsTheFailureClearly(): Unit = runBlocking {
        val tool = AndroidNotificationTool(NotificationPresenter(context, logger))

        val result = tool.execute(ToolCall(ToolId.NOTIFICATION, "notify", emptyMap()))

        assertTrue("Expected Failure, got $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("title"))
    }

    /**
     * Posting is exercised end to end.
     *
     * The outcome depends on whether notifications are enabled for the app, and
     * both outcomes are legitimate â€” what matters is that it returns a typed
     * result rather than throwing.
     */
    @Test
    fun notifyPostsOrReportsDisabledWithoutCrashing(): Unit = runBlocking {
        val tool = AndroidNotificationTool(NotificationPresenter(context, logger))

        val result = tool.execute(
            ToolCall(
                ToolId.NOTIFICATION,
                "notify",
                mapOf(
                    "title" to "DPS test notification",
                    "body" to "Posted by the Phase B instrumented test.",
                    "channel" to "assistant",
                    "id" to TEST_NOTIFICATION_ID.toString(),
                ),
            ),
        )

        when (result) {
            is ToolResult.Success -> assertEquals(
                TEST_NOTIFICATION_ID.toString(),
                result.data["notification_id"],
            )
            is ToolResult.Failure -> assertTrue(
                "Only 'notifications disabled' is an acceptable failure here: ${result.reason}",
                result.reason.contains("turned off"),
            )
            else -> throw AssertionError("Unexpected result: $result")
        }

        // Clean up whatever may have been posted.
        tool.execute(
            ToolCall(ToolId.NOTIFICATION, "cancel", mapOf("id" to TEST_NOTIFICATION_ID.toString())),
        )
    }

    @Test
    fun cancellingAnUnknownNotificationSucceeds(): Unit = runBlocking {
        val tool = AndroidNotificationTool(NotificationPresenter(context, logger))

        // Android cannot report whether a notification was showing, and the
        // caller's intent â€” "make sure it is gone" â€” is satisfied either way.
        val result = tool.execute(
            ToolCall(ToolId.NOTIFICATION, "cancel", mapOf("id" to "987654")),
        )

        assertTrue(result is ToolResult.Success)
    }

    // -----------------------------------------------------------------
    // Calendar
    // -----------------------------------------------------------------

    @Test
    fun calendarRejectsInvalidDatesBeforeTouchingTheProvider(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CALENDAR)!!

        val result = tool.execute(
            ToolCall(
                ToolId.CALENDAR,
                "create_event",
                mapOf("title" to "Bad date", "start" to "not-a-date"),
            ),
        )

        assertTrue("Expected Failure, got $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("ISO-8601"))
    }

    @Test
    fun calendarRejectsEndBeforeStart(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CALENDAR)!!

        val result = tool.execute(
            ToolCall(
                ToolId.CALENDAR,
                "create_event",
                mapOf(
                    "title" to "Backwards",
                    "start" to "2026-09-01T10:00",
                    "end" to "2026-09-01T09:00",
                ),
            ),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("after start"))
    }

    /**
     * `update_event`/`delete_event` were added in Day 05 Phase E Stage 2 —
     * this test used to assert they were `Unsupported`, which stopped being
     * true the moment `AndroidCalendarTool` implemented them. Listing remains
     * genuinely unsupported (nothing in this product enumerates events), so
     * that is what this now checks instead.
     */
    @Test
    fun calendarListingRemainsUnsupported(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CALENDAR)!!

        val result = tool.execute(ToolCall(ToolId.CALENDAR, "list_events"))

        assertTrue(result is ToolResult.Unsupported)
    }

    /** Calling update/delete with no `id` argument fails cleanly rather than crashing or guessing an event. */
    @Test
    fun calendarUpdateAndDeleteRequireAnId(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CALENDAR)!!

        val update = tool.execute(ToolCall(ToolId.CALENDAR, "update_event", mapOf("title" to "x")))
        val delete = tool.execute(ToolCall(ToolId.CALENDAR, "delete_event"))

        assertTrue("Expected Failure, got $update", update is ToolResult.Failure)
        assertTrue("Expected Failure, got $delete", delete is ToolResult.Failure)
    }

    /**
     * Full create path through the executor.
     *
     * Without calendar permission this returns `PermissionRequired`; with it,
     * an event is created and then verified by reading it back from the
     * provider â€” a returned id is not evidence that a row exists.
     */
    @Test
    fun calendarCreateEitherRequestsPermissionOrReallyCreatesTheEvent(): Unit = runBlocking {
        val container = container()
        val executor = container.toolExecutor

        val start = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val result = executor.execute(
            ToolCall(
                ToolId.CALENDAR,
                "create_event",
                mapOf(
                    "title" to CALENDAR_TEST_TITLE,
                    "start" to start.toString(),
                    "end" to (start + 3_600_000).toString(),
                    "description" to "Created by the Phase B instrumented test.",
                ),
            ),
        )

        when (result) {
            is ToolResult.PermissionRequired -> {
                // Expected when calendar permission has not been granted.
                assertTrue(result.permissions.isNotEmpty())
                assertTrue(
                    result.permissions.all {
                        it == DpsPermission.READ_CALENDAR || it == DpsPermission.WRITE_CALENDAR
                    },
                )
            }

            is ToolResult.Success -> {
                val eventId = result.data["event_id"]?.toLongOrNull()
                assertNotNull("Success must carry an event_id", eventId)

                // Verify the row genuinely exists rather than trusting the id.
                val cursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.TITLE),
                    "${CalendarContract.Events._ID} = ?",
                    arrayOf(eventId.toString()),
                    null,
                )
                cursor.use {
                    assertTrue("Event $eventId not found in provider", it != null && it.moveToFirst())
                    assertEquals(CALENDAR_TEST_TITLE, it!!.getString(0))
                }

                // Remove the test event.
                context.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID} = ?",
                    arrayOf(eventId.toString()),
                )
            }

            is ToolResult.Failure -> {
                // Legitimate when the device has no writable calendar.
                assertTrue(
                    "Unexpected failure: ${result.reason}",
                    result.reason.contains("calendar", ignoreCase = true),
                )
            }

            is ToolResult.Unsupported -> {
                // Legitimate when there is no calendar provider at all.
                assertTrue(result.reason.contains("calendar", ignoreCase = true))
            }

            else -> throw AssertionError("Unexpected result: $result")
        }
    }

    // -----------------------------------------------------------------
    // Reminders
    // -----------------------------------------------------------------

    /**
     * The fallback is the whole point: SCHEDULE_EXACT_ALARM is not auto-granted,
     * so a reminder must still be set when it is absent.
     */
    @Test
    fun reminderIsScheduledWhetherOrNotExactAlarmsArePermitted(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, permissions())
        val store = ReminderStore(context, logger)
        val tool = AndroidReminderTool(scheduler, store)

        val triggerAt = System.currentTimeMillis() + 6L * 60 * 60 * 1000
        val result = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "create_reminder",
                mapOf("title" to "Phase B test reminder", "time" to triggerAt.toString()),
            ),
        )

        assertTrue("Expected Success, got $result", result is ToolResult.Success)
        val success = result as ToolResult.Success
        val reminderId = success.data["reminder_id"]!!.toInt()

        // Whichever path ran, the outcome is reported truthfully.
        val exact = success.data["exact"]!!.toBooleanStrict()
        assertEquals(
            "Reported exactness must match the platform's actual answer",
            scheduler.canScheduleExact(),
            exact,
        )
        // Wording must match what was actually achieved.
        if (!exact) assertTrue(success.summary.contains("around"))

        // The reminder must now be listed as pending.
        val listed = tool.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
        assertTrue(listed is ToolResult.Success)
        assertTrue((listed as ToolResult.Success).data.values.contains(reminderId.toString()))

        // And cancelling must remove both the alarm and the record.
        val cancelled = tool.execute(
            ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to reminderId.toString())),
        )
        assertTrue(cancelled is ToolResult.Success)
        assertTrue(store.find(reminderId) == null)
    }

    @Test
    fun reminderInThePastIsRejected(): Unit = runBlocking {
        val tool = AndroidReminderTool(ReminderScheduler(context, logger, permissions()), ReminderStore(context, logger))

        val result = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "create_reminder",
                mapOf(
                    "title" to "Yesterday",
                    "time" to (System.currentTimeMillis() - 86_400_000).toString(),
                ),
            ),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("past"))
    }

    @Test
    fun updatingAnUnknownReminderFailsCleanly(): Unit = runBlocking {
        val tool = AndroidReminderTool(ReminderScheduler(context, logger, permissions()), ReminderStore(context, logger))

        val result = tool.execute(
            ToolCall(ToolId.REMINDER, "update_reminder", mapOf("id" to "424242")),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("No pending reminder"))
    }

    @Test
    fun reminderUpdateReschedulesAndPersists(): Unit = runBlocking {
        val store = ReminderStore(context, logger)
        val tool = AndroidReminderTool(ReminderScheduler(context, logger, permissions()), store)

        val first = System.currentTimeMillis() + 3L * 60 * 60 * 1000
        val created = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "create_reminder",
                mapOf("title" to "Original", "time" to first.toString()),
            ),
        ) as ToolResult.Success
        val id = created.data["reminder_id"]!!.toInt()

        val moved = first + 60L * 60 * 1000
        val updated = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "update_reminder",
                mapOf("id" to id.toString(), "time" to moved.toString(), "title" to "Moved"),
            ),
        )

        assertTrue("Expected Success, got $updated", updated is ToolResult.Success)
        val stored = store.find(id)
        assertNotNull(stored)
        assertEquals(moved, stored!!.triggerAtMillis)
        assertEquals("Moved", stored.title)

        tool.execute(ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to id.toString())))
    }

    // -----------------------------------------------------------------
    // Permission integration
    // -----------------------------------------------------------------

    /**
     * The permission gate must be enforced by the executor, not by each tool.
     *
     * Calendar permissions are declared in the manifest now but are dangerous
     * runtime permissions, so an ungranted device takes the PermissionRequired
     * path â€” proving the gate fires before the tool ever runs.
     */
    @Test
    fun executorGatesToolsOnPermissionState(): Unit = runBlocking {
        val container = container()
        val calendarState = container.permissionManager.state(DpsPermission.WRITE_CALENDAR)

        val result = container.toolExecutor.execute(
            ToolCall(
                ToolId.CALENDAR,
                "create_event",
                mapOf("title" to "Gate check", "start" to (System.currentTimeMillis() + 86_400_000).toString()),
            ),
        )

        if (calendarState.isUsable) {
            assertTrue(
                "With permission the tool should run, got $result",
                result !is ToolResult.PermissionRequired,
            )
        } else {
            assertTrue(
                "Without permission the gate must fire, got $result",
                result is ToolResult.PermissionRequired,
            )
        }
    }

    @Test
    fun permissionStatesAreReportedForEveryPhaseBPermission() {
        val manager = container().permissionManager

        listOf(
            DpsPermission.POST_NOTIFICATIONS,
            DpsPermission.READ_CALENDAR,
            DpsPermission.WRITE_CALENDAR,
            DpsPermission.SCHEDULE_EXACT_ALARM,
        ).forEach { permission ->
            val state = manager.state(permission)
            assertNotNull("No state for $permission", state)
            assertTrue(
                "$permission returned UNKNOWN, which suggests a query failure",
                state != PermissionState.UNKNOWN,
            )
        }
    }

    private companion object {
        const val TEST_NOTIFICATION_ID = 987001
        const val CALENDAR_TEST_TITLE = "DPS Phase B test event"
    }
}
