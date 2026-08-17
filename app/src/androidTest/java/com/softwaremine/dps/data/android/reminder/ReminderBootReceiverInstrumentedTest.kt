package com.softwaremine.dps.data.android.reminder

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves [ReminderBootReceiver] genuinely reinstates reminders — not just
 * that it compiles against [ReminderStore] and [ReminderScheduler], but that
 * a reminder it reschedules actually fires, that running it twice for one
 * boot does not double anything, that a reminder whose time already passed
 * is pruned rather than rescheduled, and that the exact-alarm outcome it
 * records reflects the platform's real current answer.
 *
 * A real `adb reboot` is not driven from here — see the Reminder Reboot
 * Survival investigation report for that procedure and its evidence. What is
 * tested here is [ReminderBootReceiver.onReceive] invoked directly with a
 * synthetic `BOOT_COMPLETED` intent, which is the standard way to exercise a
 * manifest-registered receiver's logic and needs no reboot to be meaningful:
 * the receiver has no way to tell a real broadcast from this one.
 */
@RunWith(AndroidJUnit4::class)
class ReminderBootReceiverInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()
    private val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED)

    @Test
    fun futureReminderIsRescheduledAndActuallyFiresAfterBootBroadcast(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val nm = context.getSystemService(NotificationManager::class.java)

        val id = store.nextId()
        val reminder = StoredReminder(
            id = id,
            title = TITLE,
            body = TITLE,
            triggerAtMillis = System.currentTimeMillis() + TRIGGER_DELAY_MILLIS,
            createdAtMillis = System.currentTimeMillis(),
            wasExact = false,
        )
        store.put(reminder)

        try {
            ReminderBootReceiver().onReceive(context, bootIntent)

            // AlarmManager has no query API (see ReminderStore's own docs), so
            // "the alarm is registered" is proven the only way it can be: it
            // actually fires, on the real scheduled time, through the real
            // ReminderReceiver.
            val fired = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                while (nm.activeNotifications.none { it.id == id }) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertTrue(
                "Expected notification id=$id after boot-rescheduling; active ids " +
                    "were ${nm.activeNotifications.map { it.id }}",
                fired == true,
            )

            val recordCleared = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                while (store.find(id) != null) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertTrue("Expected the fired reminder's record to be cleared", recordCleared == true)
        } finally {
            nm.cancel(id)
            scheduler.cancel(id)
            store.remove(id)
        }
    }

    @Test
    fun invokingTheBootReceiverTwiceDoesNotDuplicateTheAlarmOrNotification(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val nm = context.getSystemService(NotificationManager::class.java)

        val id = store.nextId()
        val reminder = StoredReminder(
            id = id,
            title = TITLE,
            body = TITLE,
            triggerAtMillis = System.currentTimeMillis() + TRIGGER_DELAY_MILLIS,
            createdAtMillis = System.currentTimeMillis(),
            wasExact = false,
        )
        store.put(reminder)

        try {
            // Simulates the receiver somehow running twice for the same boot.
            // ReminderScheduler.schedule keys its PendingIntent by id with
            // FLAG_UPDATE_CURRENT, so AlarmManager replaces rather than
            // duplicates — this proves that guarantee holds through the boot
            // receiver, not just through a single schedule() call.
            ReminderBootReceiver().onReceive(context, bootIntent)
            ReminderBootReceiver().onReceive(context, bootIntent)

            val fired = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                while (nm.activeNotifications.none { it.id == id }) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertTrue("Expected exactly one eventual firing, got none", fired == true)

            // Give a further margin past the first firing: if the double
            // invocation had genuinely registered two alarms, a second one
            // could still land inside this window.
            delay(EXTRA_DUPLICATE_CHECK_MILLIS)
            assertEquals(
                "A duplicated schedule() call must not produce a second firing",
                1,
                nm.activeNotifications.count { it.id == id },
            )
        } finally {
            nm.cancel(id)
            scheduler.cancel(id)
            store.remove(id)
        }
    }

    @Test
    fun expiredReminderIsPrunedRatherThanRescheduled(): Unit = runBlocking {
        val store = ReminderStore(context, logger)
        val nm = context.getSystemService(NotificationManager::class.java)

        val id = store.nextId()
        val pastReminder = StoredReminder(
            id = id,
            title = TITLE,
            body = TITLE,
            triggerAtMillis = System.currentTimeMillis() - 60_000L,
            createdAtMillis = System.currentTimeMillis() - 120_000L,
            wasExact = true,
        )
        store.put(pastReminder)

        try {
            ReminderBootReceiver().onReceive(context, bootIntent)

            assertNull("An expired reminder must be pruned, not kept", store.find(id))

            // A negative check: confirm it never fires, rather than merely
            // that it isn't in the store. If pruning were broken and this got
            // rescheduled with an already-past trigger time, AlarmManager
            // fires an overdue alarm almost immediately, so a short window is
            // enough to catch that regression.
            val eventuallyFired = withTimeoutOrNull(NEGATIVE_CHECK_MILLIS) {
                while (nm.activeNotifications.none { it.id == id }) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
            assertNull("An expired reminder must never post a notification", eventuallyFired)
        } finally {
            nm.cancel(id)
            store.remove(id)
        }
    }

    @Test
    fun rescheduledReminderRecordsTheActualExactnessOutcome(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val nm = context.getSystemService(NotificationManager::class.java)

        // Whatever this device's real, current answer is — not assumed
        // either way, exactly as ReminderScheduler itself decides it.
        val actualExactness = scheduler.canScheduleExact()

        val id = store.nextId()
        val reminder = StoredReminder(
            id = id,
            title = TITLE,
            body = TITLE,
            // Far enough out that this test's own runtime can never reach it —
            // this test checks the recorded outcome, not firing.
            triggerAtMillis = System.currentTimeMillis() + FAR_FUTURE_MILLIS,
            createdAtMillis = System.currentTimeMillis(),
            // Deliberately the opposite of the real answer, simulating a
            // reminder created before an exact-alarm grant changed.
            wasExact = !actualExactness,
        )
        store.put(reminder)

        try {
            ReminderBootReceiver().onReceive(context, bootIntent)

            val updated = store.find(id)
            assertNotNull("Rescheduled reminder must still be on record", updated)
            assertEquals(
                "wasExact must reflect the platform's current answer after " +
                    "reschedule, not the stale value the record had before boot",
                actualExactness,
                updated!!.wasExact,
            )
        } finally {
            nm.cancel(id)
            scheduler.cancel(id)
            store.remove(id)
        }
    }

    private companion object {
        const val TITLE = "Reminder boot receiver regression"

        const val TRIGGER_DELAY_MILLIS = 8_000L
        const val FAR_FUTURE_MILLIS = 5 * 60 * 1000L

        const val POLL_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val NEGATIVE_CHECK_MILLIS = 5_000L
        const val EXTRA_DUPLICATE_CHECK_MILLIS = 3_000L
    }
}
