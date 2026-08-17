package com.softwaremine.dps.data.android.reminder

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.data.android.tool.AndroidReminderTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real end-to-end reminder path fires, not just that it is
 * scheduled: `ReminderScheduler -> AlarmManager -> ReminderReceiver ->
 * NotificationPresenter`.
 *
 * ## Why this exists
 * Every other reminder test (see `AndroidToolsInstrumentedTest`) schedules
 * hours in the future and returns immediately — proving the call into
 * `AlarmManager` was made, never that anything fires. That gap was closed by
 * hand during the Reminder Trigger investigation (three real-device
 * reproductions: process alive, process killed and backgrounded, and forced
 * Doze — all fired correctly, evidenced via `ActivityManager: Start proc ...
 * for broadcast {ReminderReceiver}` and the posted notification). This test
 * keeps the first of those three — the one a normal `connectedAndroidTest`
 * run can execute unattended — as permanent regression coverage. The other
 * two require driving `adb` (backgrounding the app, forcing Doze) from
 * outside the instrumentation process and are not expressible as a
 * self-contained JUnit test; their evidence is recorded in the investigation
 * report instead.
 *
 * ## Why a short real wait rather than a fake clock
 * The thing under test *is* the real `AlarmManager` actually calling back
 * through a real `PendingIntent` into a real manifest-registered receiver.
 * Faking any part of that chain would stop testing the thing that broke
 * trust in the first place. The wait is bounded by [withTimeoutOrNull]
 * rather than a fixed sleep, so the test finishes as soon as the
 * notification appears instead of always paying the worst case.
 */
@RunWith(AndroidJUnit4::class)
class ReminderTriggerInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    @Test
    fun scheduledReminderActuallyFiresNotifiesAndClearsItsRecord(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val tool = AndroidReminderTool(scheduler, store)
        val nm = context.getSystemService(NotificationManager::class.java)

        // store.nextId() is a persisted, monotonically increasing counter
        // (ReminderStore), so this id cannot collide with a reminder left
        // over from a previous run — no dependency on a clean notification
        // shelf or a fixed literal id.
        val triggerAt = System.currentTimeMillis() + TRIGGER_DELAY_MILLIS
        val created = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "create_reminder",
                mapOf("title" to TITLE, "time" to triggerAt.toString()),
            ),
        )
        assertTrue("Expected Success, got $created", created is ToolResult.Success)
        val reminderId = (created as ToolResult.Success).data["reminder_id"]!!.toInt()

        try {
            val fired = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                while (nm.activeNotifications.none { it.id == reminderId }) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertTrue(
                "Expected notification id=$reminderId within ${POLL_TIMEOUT_MILLIS}ms of " +
                    "the scheduled time; active ids were ${nm.activeNotifications.map { it.id }}",
                fired == true,
            )

            // ReminderReceiver clears the store record once it fires, so a
            // pending listing shows only what is genuinely still ahead.
            val recordCleared = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                while (store.find(reminderId) != null) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertTrue("Expected the fired reminder's record to be cleared", recordCleared == true)
            assertNull(store.find(reminderId))
        } finally {
            // Unconditional, and deliberately not asserted: if an assertion
            // above already failed, throwing again here would replace that
            // failure's message with a useless one instead of adding to it.
            // The cleanup itself still always runs, leaving nothing behind —
            // no dangling alarm, no stuck notification, no orphaned record.
            nm.cancel(reminderId)
            scheduler.cancel(reminderId)
            store.remove(reminderId)
        }
    }

    private companion object {
        const val TITLE = "Reminder trigger regression"

        // Short enough to keep the suite fast, long enough that scheduling
        // overhead can never be mistaken for a missed alarm.
        const val TRIGGER_DELAY_MILLIS = 8_000L

        // Generous relative to TRIGGER_DELAY_MILLIS: covers the inexact-alarm
        // fallback's documented drift as well as ordinary device jitter,
        // while still being a hard bound rather than an open-ended wait.
        const val POLL_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
