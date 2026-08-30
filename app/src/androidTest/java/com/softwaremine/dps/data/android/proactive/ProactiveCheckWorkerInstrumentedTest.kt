package com.softwaremine.dps.data.android.proactive

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.preferences.UserPreferences
import com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Real-device verification of [ProactiveCheckWorker]'s pipeline and
 * scheduling uniqueness (M4-A).
 *
 * ## Why [ProactiveCheckWorker.runCheck] is called directly, not through WorkManager
 * `PeriodicWorkRequest`'s minimum interval is 15 minutes — waiting for a real
 * periodic trigger to fire naturally is impractical inside a test and would
 * not add any correctness signal `runCheck` itself doesn't already provide.
 * What genuinely needs proving on-device is the pipeline's real behaviour
 * against real `AndroidTaskStore`/`ProactiveStateStore`/`NotificationPresenter`
 * state, and that scheduling never registers the periodic work twice — both
 * covered here without needing the `androidx.work:work-testing` artifact
 * this project does not otherwise depend on.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveCheckWorkerInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun activeNotificationIds(): Set<Int> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.map { it.id }.toSet()
    }

    /**
     * Matches [ProactiveCheckWorker]'s own private `EVENT_NOTIFICATION_ID_OFFSET`/
     * mask exactly, duplicated here only so tests can clean up without that
     * function needing to be non-private for test access alone.
     */
    private val eventNotificationIdRange = 3_000_000..(3_000_000 + 0x00FFFFFF)

    private fun cancelAllEventNotifications() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.id in eventNotificationIdRange }.forEach { nm.cancel(it.id) }
    }

    private fun deleteCalendarEvent(eventId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    private fun hasCalendarPermission(writer: CalendarWriter): Boolean =
        writer.findWritableCalendar() is CalendarWriter.CalendarTarget.Found

    // -----------------------------------------------------------------
    // Pipeline: READ → EVALUATE → NOTIFY → RECORD (M4-A: overdue tasks)
    // -----------------------------------------------------------------

    @Test
    fun anOverdueTaskIsDetectedNotifiedAndMarked(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A overdue check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        try {
            val notificationId = 2_000_000 + overdue.id

            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)

            assertTrue("Expected Result.success(), got $result", result is androidx.work.ListenableWorker.Result.Success)
            assertTrue(
                "The real notification must actually be posted, got ${activeNotificationIds()}",
                notificationId in activeNotificationIds(),
            )

            val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, java.time.ZoneId.systemDefault())
            val expectedMarker = ProactiveRuleEvaluator.markerKey(overdue.id, dateKey)
            assertTrue(
                "The marker must be persisted only after the notification was posted",
                expectedMarker in stateStore.load().notifiedMarkers,
            )
            assertEquals(now, stateStore.load().lastCheckedAtMillis)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            stateStore.clear()
        }
    }

    @Test
    fun runningTheCheckTwiceForTheSameOverdueTaskDoesNotDuplicateTheNotification(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A duplicate-prevention check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )
        val notificationId = 2_000_000 + overdue.id

        try {
            ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            // The notification is cancelled deliberately between runs — the
            // property under test is that run #2 does not re-post it because
            // the task is already marked notified, not that the system
            // happens to still be showing run #1's notification.

            val secondRunResult = ProactiveCheckWorker.runCheck(
                context,
                now + TimeUnit.MINUTES.toMillis(30),
                logger,
                taskStore,
                stateStore,
            )

            assertTrue(secondRunResult is androidx.work.ListenableWorker.Result.Success)
            assertFalse(
                "A second run within the same notification cycle must not re-post the notification",
                notificationId in activeNotificationIds(),
            )

            val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, java.time.ZoneId.systemDefault())
            val markerCount = stateStore.load().notifiedMarkers.count {
                ProactiveRuleEvaluator.taskIdIfMarkerMatches(it, dateKey) == overdue.id
            }
            assertEquals("Exactly one marker must exist for this task on this date, not duplicated", 1, markerCount)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            taskStore.delete(overdue.id)
            stateStore.clear()
        }
    }

    @Test
    fun noEligibleTasksMeansNoNotificationAndNoMarker(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        // A future task and a completed overdue task — neither eligible.
        val future = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A future task, not overdue",
                status = TaskStatus.PENDING,
                dueAtMillis = now + TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        val completed = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A completed overdue task",
                status = TaskStatus.COMPLETED,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        try {
            val beforeIds = activeNotificationIds()

            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)

            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertEquals(
                "Neither an ineligible future task nor a completed one may post a notification",
                beforeIds,
                activeNotificationIds(),
            )
            assertTrue(stateStore.load().notifiedMarkers.isEmpty())
            assertEquals(now, stateStore.load().lastCheckedAtMillis)
        } finally {
            taskStore.delete(future.id)
            taskStore.delete(completed.id)
            stateStore.clear()
        }
    }

    // -----------------------------------------------------------------
    // Pipeline: READ → EVALUATE → NOTIFY → RECORD (M4-B: upcoming events)
    // -----------------------------------------------------------------

    @Test
    fun aOneTimeUpcomingCalendarEventIsDetectedNotifiedAndMarked(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        if (!hasCalendarPermission(calendarWriter)) return@runBlocking // calendar permission not granted on this run

        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found

        val now = System.currentTimeMillis()
        val start = now + TimeUnit.MINUTES.toMillis(30)
        val created = calendarWriter.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B one-time upcoming check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = start + TimeUnit.HOURS.toMillis(1),
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val result = ProactiveCheckWorker.runCheck(
                context, now, logger,
                stateStore = stateStore,
                calendarWriter = calendarWriter,
            )

            assertTrue("Expected Result.success(), got $result", result is androidx.work.ListenableWorker.Result.Success)

            val expectedMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, start, ZoneId.systemDefault())
            assertTrue(
                "The marker must be persisted only after the upcoming-event notification was posted",
                expectedMarker in stateStore.load().notifiedMarkers,
            )
        } finally {
            cancelAllEventNotifications()
            deleteCalendarEvent(eventId)
            stateStore.clear()
        }
    }

    @Test
    fun anAllDayCalendarEventProducesNoProactiveNotification(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        if (!hasCalendarPermission(calendarWriter)) return@runBlocking

        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found

        val now = System.currentTimeMillis()
        val utcMidnightTomorrow = (now / 86_400_000L + 1) * 86_400_000L
        val created = calendarWriter.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B all-day exclusion check",
            description = null,
            location = null,
            startMillis = utcMidnightTomorrow,
            endMillis = utcMidnightTomorrow + 86_400_000L,
            allDay = true,
            timezone = "UTC",
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val beforeIds = activeNotificationIds()

            val result = ProactiveCheckWorker.runCheck(
                context, now, logger,
                stateStore = stateStore,
                calendarWriter = calendarWriter,
            )

            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertEquals(
                "An all-day event must never produce a proactive notification",
                beforeIds,
                activeNotificationIds(),
            )
            assertTrue(
                "An all-day event must never receive a notified marker either",
                stateStore.load().notifiedMarkers.none { it.startsWith("event:$eventId:") },
            )
        } finally {
            deleteCalendarEvent(eventId)
            stateStore.clear()
        }
    }

    @Test
    fun runningTheCheckTwiceForTheSameUpcomingEventDoesNotDuplicateTheNotification(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        if (!hasCalendarPermission(calendarWriter)) return@runBlocking

        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found

        val now = System.currentTimeMillis()
        val start = now + TimeUnit.MINUTES.toMillis(30)
        val created = calendarWriter.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B duplicate-prevention check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = start + TimeUnit.HOURS.toMillis(1),
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            ProactiveCheckWorker.runCheck(context, now, logger, stateStore = stateStore, calendarWriter = calendarWriter)
            cancelAllEventNotifications()
            // Cancelled deliberately between runs, exactly like the M4-A
            // duplicate-prevention test above — the property under test is
            // that run #2 does not re-post because a marker already exists,
            // not that the system happens to still show run #1's notification.

            val secondRunResult = ProactiveCheckWorker.runCheck(
                context,
                now + TimeUnit.MINUTES.toMillis(5),
                logger,
                stateStore = stateStore,
                calendarWriter = calendarWriter,
            )

            assertTrue(secondRunResult is androidx.work.ListenableWorker.Result.Success)
            assertTrue(
                "A second run within the same occurrence's notification cycle must not re-post the notification",
                activeNotificationIds().none { it in eventNotificationIdRange },
            )

            val markerCount = stateStore.load().notifiedMarkers.count { it.startsWith("event:$eventId:upcoming:$start:") }
            assertEquals("Exactly one marker must exist for this occurrence, not duplicated", 1, markerCount)
        } finally {
            cancelAllEventNotifications()
            deleteCalendarEvent(eventId)
            stateStore.clear()
        }
    }

    /**
     * Proves that marking one occurrence of a recurring event as notified
     * never suppresses a *different* occurrence of the same series — the
     * exact property [com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluatorTest]
     * already proves against synthetic data, reproduced here end-to-end
     * against a real recurring event and the real worker pipeline.
     *
     * ## Why the "already notified" sibling marker is computed, not discovered
     * An earlier version of this test tried to find two real occurrences of
     * the same series inside one 60-minute window using
     * `FREQ=MINUTELY;INTERVAL=20`, to seed one and prove the other still
     * fires. That relies on this device's `CalendarProvider` expanding a
     * minutely recurrence — an unusual, sparsely-exercised granularity real
     * calendar apps rarely generate — and it did not reliably expand as
     * RFC 5545 describes on this device. The property under test is about
     * marker independence, not about provider recurrence-expansion
     * granularity (which the plain daily-recurrence tests elsewhere in this
     * file, and [com.softwaremine.dps.data.android.calendar.CalendarWriterInstrumentedTest],
     * already exercise). So here, exactly one real occurrence is discovered
     * (a standard daily series' occurrence within the window) and the
     * "already notified" sibling is a synthetic, deterministically computed
     * marker for a different begin time (24 hours earlier) — still a real
     * marker key, still exercised through the real worker pipeline, just
     * not requiring the real provider to expand a second real occurrence
     * within one short window.
     */
    @Test
    fun aDifferentOccurrenceOfTheSameRecurringEventCanStillNotifyAfterAnEarlierOneIsMarked(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        if (!hasCalendarPermission(calendarWriter)) return@runBlocking

        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found

        val now = System.currentTimeMillis()
        val seriesStart = now - TimeUnit.DAYS.toMillis(1) + TimeUnit.MINUTES.toMillis(30)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, target.calendarId)
            put(CalendarContract.Events.TITLE, "M4-B distinct-occurrence check")
            put(CalendarContract.Events.DTSTART, seriesStart)
            put(CalendarContract.Events.DURATION, "PT10M")
            put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=10")
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        assertNotNull("Test fixture setup failed: calendar provider rejected the recurring event insert", uri)
        val eventId = ContentUris.parseId(uri!!)

        try {
            val discovery = calendarWriter.findUpcomingInstances(now, now + TimeUnit.MINUTES.toMillis(60), limit = 20)
            assertTrue(discovery is CalendarWriter.InstanceQueryOutcome.Found)
            val todaysOccurrence = (discovery as CalendarWriter.InstanceQueryOutcome.Found).occurrences
                .firstOrNull { it.sourceEventId == eventId && it.beginMillis > now }
            assertNotNull("Expected today's occurrence of the daily series to be found within the window", todaysOccurrence)

            // A different, sibling occurrence of the SAME series — yesterday's
            // — computed deterministically (24h earlier), never discovered via
            // a second real query, and pre-seeded as already notified.
            val siblingBeginMillis = todaysOccurrence!!.beginMillis - TimeUnit.DAYS.toMillis(1)
            val siblingMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, siblingBeginMillis, ZoneId.systemDefault())
            stateStore.save(ProactiveState(lastCheckedAtMillis = now, notifiedMarkers = setOf(siblingMarker)))

            val result = ProactiveCheckWorker.runCheck(context, now, logger, stateStore = stateStore, calendarWriter = calendarWriter)
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)

            val todaysMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, todaysOccurrence.beginMillis, ZoneId.systemDefault())
            assertTrue(
                "Today's occurrence must still be notified even though a sibling occurrence's marker already exists",
                todaysMarker in stateStore.load().notifiedMarkers,
            )
            assertTrue(
                "The pre-seeded sibling marker must still be present, untouched",
                siblingMarker in stateStore.load().notifiedMarkers,
            )
        } finally {
            cancelAllEventNotifications()
            deleteCalendarEvent(eventId)
            stateStore.clear()
        }
    }

    // -----------------------------------------------------------------
    // Failure-mode safety (M4-B)
    // -----------------------------------------------------------------

    /**
     * Proves calendar unavailability neither crashes the worker nor blocks
     * the overdue-task check — genuinely tested against a real,
     * operator-revoked `READ_CALENDAR` grant, not a simulated failure.
     *
     * ## How to run this test for real
     * ```
     * adb shell pm revoke com.softwaremine.dps android.permission.READ_CALENDAR
     * adb shell am instrument -w -r \
     *   -e class com.softwaremine.dps.data.android.proactive.ProactiveCheckWorkerInstrumentedTest#readCalendarUnavailableDoesNotCrashAndOverdueTaskCheckStillRuns \
     *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
     * adb shell pm grant com.softwaremine.dps android.permission.READ_CALENDAR
     * ```
     * If `READ_CALENDAR` happens to already be granted when this runs (e.g.
     * as part of the whole class, without the revoke step), the
     * calendar-specific assertion is skipped rather than faked — only the
     * overdue-task assertion, which holds unconditionally either way, is
     * unconditional.
     */
    @Test
    fun readCalendarUnavailableDoesNotCrashAndOverdueTaskCheckStillRuns(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        val calendarWriter = CalendarWriter(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-B permission-unavailable check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        try {
            // No try/catch around runCheck itself: an uncaught exception here
            // would fail this test directly, which is exactly how "the
            // worker must not crash" gets proven rather than assumed.
            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore, calendarWriter = calendarWriter)
            assertTrue(
                "Expected a WorkManager Result (success or retry), never an uncaught exception, got $result",
                result is androidx.work.ListenableWorker.Result,
            )

            val taskNotificationId = 2_000_000 + overdue.id
            assertTrue(
                "The overdue-task check must still run and notify even when calendar access is unavailable",
                taskNotificationId in activeNotificationIds(),
            )

            if (!hasCalendarPermission(calendarWriter)) {
                assertTrue(
                    "No event marker may exist when calendar access was genuinely unavailable for this run",
                    stateStore.load().notifiedMarkers.none { it.startsWith("event:") },
                )
            }
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            stateStore.clear()
        }
    }

    /**
     * Proves a notification-posting failure never persists an occurrence
     * marker — genuinely tested against the real
     * `NotificationManagerCompat.areNotificationsEnabled()` gate, toggled
     * via `adb shell cmd appops set <pkg> POST_NOTIFICATION deny` (disables
     * notifications for the app without touching the `READ_CALENDAR`/
     * `POST_NOTIFICATIONS` runtime grants — exactly the distinction
     * [NotificationPresenter.areNotificationsEnabled]'s own doc describes).
     *
     * ## How to run this test for real
     * ```
     * adb shell cmd appops set com.softwaremine.dps POST_NOTIFICATION deny
     * adb shell am instrument -w -r \
     *   -e class com.softwaremine.dps.data.android.proactive.ProactiveCheckWorkerInstrumentedTest#aNotificationPostingFailureDoesNotPersistTheOccurrenceMarker \
     *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
     * adb shell cmd appops set com.softwaremine.dps POST_NOTIFICATION allow
     * ```
     */
    @Test
    fun aNotificationPostingFailureDoesNotPersistTheOccurrenceMarker(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        if (!hasCalendarPermission(calendarWriter)) return@runBlocking

        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found
        val presenter = NotificationPresenter(context, logger)

        val now = System.currentTimeMillis()
        val start = now + TimeUnit.MINUTES.toMillis(30)
        val created = calendarWriter.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B notification-failure check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = start + TimeUnit.HOURS.toMillis(1),
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val notificationsEnabled = presenter.areNotificationsEnabled()

            val result = ProactiveCheckWorker.runCheck(
                context, now, logger,
                stateStore = stateStore,
                calendarWriter = calendarWriter,
                presenter = presenter,
            )
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)

            val marker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, start, ZoneId.systemDefault())
            if (!notificationsEnabled) {
                assertTrue(
                    "A notification that could not be posted must never be marked as notified",
                    marker !in stateStore.load().notifiedMarkers,
                )
            } else {
                // Environment did not have notifications disabled for this
                // run — documented, not treated as this test's own failure.
                assertTrue(marker in stateStore.load().notifiedMarkers)
            }
        } finally {
            cancelAllEventNotifications()
            deleteCalendarEvent(eventId)
            stateStore.clear()
        }
    }

    // -----------------------------------------------------------------
    // M4-C: proactiveAssistantEnabled gate
    // -----------------------------------------------------------------

    @Test
    fun defaultPreferenceStateAllowsProactiveChecksToRunWithoutAnyExplicitOptIn(): Unit = runBlocking {
        val preferenceStore = PersistentPreferenceStore.create(context, logger)
        preferenceStore.clear() // simulates an install/upgrade with nothing saved yet
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-C default-enabled check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        try {
            ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore, preferenceStore = preferenceStore)

            assertTrue(
                "A fresh/legacy install with no saved preference must behave exactly as before M4-C — not be silently opted out",
                (2_000_000 + overdue.id) in activeNotificationIds(),
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            stateStore.clear()
            // Guards against the documented async SharedPreferences.apply()
            // flush race: a short-lived am instrument process can otherwise
            // exit before this cleanup write reaches disk.
            delay(400)
        }
    }

    @Test
    fun disablingTheProactiveAssistantSkipsAllReadsNotificationsAndMarkerWrites(): Unit = runBlocking {
        // This test asserts on ProactiveStateStore.lastCheckedAtMillis staying
        // exactly null, which the REAL periodic ProactiveCheckWorker — already
        // scheduled on this device by every prior app launch since M4-A, under
        // ExistingPeriodicWorkPolicy.KEEP — could independently overwrite by
        // genuinely firing mid-test. Cancelled here, restored in finally, so
        // that real background job cannot race this specific assertion; no
        // other test in this file makes a strict-enough claim about this field
        // to need the same guard.
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)

        val preferenceStore = PersistentPreferenceStore.create(context, logger)
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        val calendarWriter = CalendarWriter(context, logger)
        stateStore.clear()
        preferenceStore.save(UserPreferences(proactiveAssistantEnabled = false))

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-C disabled check — overdue task",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        val hasCalendar = hasCalendarPermission(calendarWriter)
        var eventId: Long? = null
        if (hasCalendar) {
            val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found
            val start = now + TimeUnit.MINUTES.toMillis(30)
            val created = calendarWriter.insertEvent(
                calendarId = target.calendarId,
                title = "M4-C disabled check — upcoming event",
                description = null,
                location = null,
                startMillis = start,
                endMillis = start + TimeUnit.HOURS.toMillis(1),
                allDay = false,
                timezone = TimeZone.getDefault().id,
            )
            assertTrue(created is CalendarWriter.InsertOutcome.Created)
            eventId = (created as CalendarWriter.InsertOutcome.Created).eventId
        }

        try {
            val beforeIds = activeNotificationIds()

            val result = ProactiveCheckWorker.runCheck(
                context, now, logger, taskStore, stateStore,
                calendarWriter = calendarWriter,
                preferenceStore = preferenceStore,
            )

            assertTrue("Disabled must still return Result.success(), not retry or crash", result is androidx.work.ListenableWorker.Result.Success)
            assertEquals(
                "A genuinely eligible overdue task and upcoming event must both produce zero notifications while disabled",
                beforeIds,
                activeNotificationIds(),
            )
            assertTrue(
                "No marker of any kind may be written while disabled",
                stateStore.load().notifiedMarkers.isEmpty(),
            )
            assertNull(
                "ProactiveStateStore.save() must never even be called while disabled — lastCheckedAtMillis must stay null",
                stateStore.load().lastCheckedAtMillis,
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            eventId?.let { deleteCalendarEvent(it) }
            cancelAllEventNotifications()
            stateStore.clear()
            preferenceStore.save(UserPreferences(proactiveAssistantEnabled = true))
            ProactiveCheckWorker.schedule(context) // restores the real periodic registration cancelled above
            delay(400)
        }
    }

    @Test
    fun enablingTheProactiveAssistantPreservesExistingOverdueTaskAndUpcomingEventBehavior(): Unit = runBlocking {
        val preferenceStore = PersistentPreferenceStore.create(context, logger)
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        val calendarWriter = CalendarWriter(context, logger)
        stateStore.clear()
        preferenceStore.save(UserPreferences(proactiveAssistantEnabled = true))

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-C explicitly-enabled check — overdue task",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        val hasCalendar = hasCalendarPermission(calendarWriter)
        var eventId: Long? = null
        var eventStart: Long? = null
        if (hasCalendar) {
            val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found
            val start = now + TimeUnit.MINUTES.toMillis(30)
            eventStart = start
            val created = calendarWriter.insertEvent(
                calendarId = target.calendarId,
                title = "M4-C explicitly-enabled check — upcoming event",
                description = null,
                location = null,
                startMillis = start,
                endMillis = start + TimeUnit.HOURS.toMillis(1),
                allDay = false,
                timezone = TimeZone.getDefault().id,
            )
            assertTrue(created is CalendarWriter.InsertOutcome.Created)
            eventId = (created as CalendarWriter.InsertOutcome.Created).eventId
        }

        try {
            val result = ProactiveCheckWorker.runCheck(
                context, now, logger, taskStore, stateStore,
                calendarWriter = calendarWriter,
                preferenceStore = preferenceStore,
            )

            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertTrue(
                "An explicit true must behave exactly like the M4-A/M4-B baseline for an overdue task",
                (2_000_000 + overdue.id) in activeNotificationIds(),
            )
            if (hasCalendar) {
                val expectedMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId!!, eventStart!!, ZoneId.systemDefault())
                assertTrue(
                    "An explicit true must behave exactly like the M4-A/M4-B baseline for an upcoming event",
                    expectedMarker in stateStore.load().notifiedMarkers,
                )
            }
            assertEquals(now, stateStore.load().lastCheckedAtMillis)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            eventId?.let { deleteCalendarEvent(it) }
            cancelAllEventNotifications()
            stateStore.clear()
            delay(400)
        }
    }

    @Test
    fun disablingThePreferenceSurvivesAFreshPreferenceStoreReconstructionAndTheWorkerHonorsIt(): Unit = runBlocking {
        // First "process": save disabled.
        PersistentPreferenceStore.create(context, logger).save(UserPreferences(proactiveAssistantEnabled = false))

        // A brand-new PersistentPreferenceStore instance — the object-level
        // analogue of "a fresh process reads what an earlier process wrote,"
        // exactly like this worker already reconstructs a fresh
        // AndroidTaskStore/ProactiveStateStore/CalendarWriter on every real
        // WorkManager-triggered run rather than reusing one held in memory.
        val freshPreferenceStore = PersistentPreferenceStore.create(context, logger)
        assertFalse(freshPreferenceStore.load().proactiveAssistantEnabled)

        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-C reconstruction check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        try {
            ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore, preferenceStore = freshPreferenceStore)

            assertTrue(
                "The freshly reconstructed store's disabled value must actually gate the worker",
                (2_000_000 + overdue.id) !in activeNotificationIds(),
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            stateStore.clear()
            PersistentPreferenceStore.create(context, logger).save(UserPreferences(proactiveAssistantEnabled = true))
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Scheduling uniqueness
    // -----------------------------------------------------------------

    /**
     * Proves [ProactiveCheckWorker.schedule]'s `ExistingPeriodicWorkPolicy.KEEP`
     * choice actually prevents a duplicate registration — exactly the
     * behaviour [com.softwaremine.dps.DpsApplication.onCreate] relies on
     * running once per process start without ever accumulating a second
     * periodic worker.
     */
    @Test
    fun schedulingTwiceRegistersOnlyOneUniquePeriodicWork(): Unit = runBlocking {
        val workManager = WorkManager.getInstance(context)

        try {
            ProactiveCheckWorker.schedule(context)
            ProactiveCheckWorker.schedule(context)

            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            val nonCancelled = infos.filter { it.state != WorkInfo.State.CANCELLED }

            assertEquals(
                "Calling schedule() twice must register exactly one unique periodic work, got $infos",
                1,
                nonCancelled.size,
            )
        } finally {
            workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }

    @Test
    fun schedulingWithKeepPolicyPreservesAnAlreadyEnqueuedWorksIdentity(): Unit = runBlocking {
        val workManager = WorkManager.getInstance(context)

        // The real DpsApplication.onCreate() calls ProactiveCheckWorker.schedule()
        // unconditionally on every process start, so on any device where the
        // app has ever actually launched, a non-cancelled real registration
        // under UNIQUE_WORK_NAME already exists by the time this test runs —
        // which would make the KEEP policy below silently ignore this test's
        // own "original" enqueue (there being nothing to keep it FOR), the
        // exact premise this test needs to control. Cleared synchronously
        // first so "original" is genuinely the first registration.
        workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).result.get()

        try {
            // Enqueue directly with a distinguishable request first, mirroring
            // what a prior app launch would already have registered.
            val original = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(30, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(
                ProactiveCheckWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                original,
            ).result.get()

            ProactiveCheckWorker.schedule(context) // simulates a second app start

            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            assertEquals(1, infos.count { it.state != WorkInfo.State.CANCELLED })
            assertNotNull(
                "The originally enqueued work's id must survive a later schedule() call under KEEP",
                infos.firstOrNull { it.id == original.id },
            )
        } finally {
            workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }
}
