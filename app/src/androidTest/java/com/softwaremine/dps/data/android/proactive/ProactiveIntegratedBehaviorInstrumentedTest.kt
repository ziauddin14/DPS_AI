package com.softwaremine.dps.data.android.proactive

import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarWriter
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * M4-D — end-to-end validation of the complete M4 proactive system (M4-A +
 * M4-B + M4-C) as ONE coherent whole, on a real device, in one continuous
 * sequence.
 *
 * ## Why this file exists alongside the per-milestone suites
 * [ProactiveCheckWorkerInstrumentedTest], [ProactiveProcessDeathInstrumentedTest]
 * and [CalendarWriterInstrumentedTest] already prove every piece in
 * isolation. None of them exercises enable → duplicate-prevention → disable
 * → re-enable as one continuous flow against the SAME task/event/state,
 * which is the specific integration gap M4-D's own brief calls out. This
 * file adds exactly that scenario (M4-D's Scenarios A–D) and nothing else —
 * it does not replace or duplicate the existing per-milestone coverage.
 * Scenarios E (READ_CALENDAR unavailable) and F (notification posting
 * failure) already have dedicated, genuine on-device tests in
 * [ProactiveCheckWorkerInstrumentedTest] from the M4-C round
 * (`readCalendarUnavailableDoesNotCrashAndOverdueTaskCheckStillRuns`,
 * `aNotificationPostingFailureDoesNotPersistTheOccurrenceMarker`); M4-D
 * re-runs those as regression rather than duplicating them here.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveIntegratedBehaviorInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun activeNotificationIds(): Set<Int> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.map { it.id }.toSet()
    }

    private fun hasCalendarPermission(writer: CalendarWriter): Boolean =
        writer.findWritableCalendar() is CalendarWriter.CalendarTarget.Found

    /**
     * Scenarios A → D of M4-D's brief, run as one continuous sequence
     * against real [AndroidTaskStore]/[ProactiveStateStore]/[CalendarWriter]/
     * [PersistentPreferenceStore] state — never object-swapped mid-scenario
     * except where Scenario C's own "restart into a fresh process" step
     * explicitly calls for a fresh store instance (object-level; the
     * genuine `adb shell am force-stop` version of this exact preference is
     * covered separately in [ProactiveProcessDeathInstrumentedTest]).
     */
    @Test
    fun scenariosAThroughD_enabledDuplicatePreventionDisabledReenabled(): Unit = runBlocking {
        val preferenceStore = PersistentPreferenceStore.create(context, logger)
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        val calendarWriter = CalendarWriter(context, logger)
        stateStore.clear()
        preferenceStore.save(UserPreferences(proactiveAssistantEnabled = true))

        val now = System.currentTimeMillis()

        val overdueTask = taskStore.save(
            Task(
                id = taskStore.nextId(), title = "M4-D integrated overdue", status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2), updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )
        val futureTask = taskStore.save(
            Task(
                id = taskStore.nextId(), title = "M4-D integrated future", status = TaskStatus.PENDING,
                dueAtMillis = now + TimeUnit.HOURS.toMillis(1), createdAtMillis = now, updatedAtMillis = now,
            ),
        )
        val completedTask = taskStore.save(
            Task(
                id = taskStore.nextId(), title = "M4-D integrated completed", status = TaskStatus.COMPLETED,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1), createdAtMillis = now, updatedAtMillis = now,
            ),
        )
        val cancelledTask = taskStore.save(
            Task(
                id = taskStore.nextId(), title = "M4-D integrated cancelled", status = TaskStatus.CANCELLED,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1), createdAtMillis = now, updatedAtMillis = now,
            ),
        )

        val hasCalendar = hasCalendarPermission(calendarWriter)
        var oneTimeEventId: Long? = null
        var oneTimeStart: Long? = null
        var recurringEventId: Long? = null
        if (hasCalendar) {
            val target = calendarWriter.findWritableCalendar() as CalendarWriter.CalendarTarget.Found
            val start = now + TimeUnit.MINUTES.toMillis(30)
            oneTimeStart = start
            val created = calendarWriter.insertEvent(
                calendarId = target.calendarId, title = "M4-D integrated upcoming event",
                description = null, location = null, startMillis = start, endMillis = start + TimeUnit.HOURS.toMillis(1),
                allDay = false, timezone = TimeZone.getDefault().id,
            )
            assertTrue(created is CalendarWriter.InsertOutcome.Created)
            oneTimeEventId = (created as CalendarWriter.InsertOutcome.Created).eventId

            val seriesStart = now - TimeUnit.DAYS.toMillis(1) + TimeUnit.MINUTES.toMillis(45)
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, target.calendarId)
                put(CalendarContract.Events.TITLE, "M4-D integrated recurring event")
                put(CalendarContract.Events.DTSTART, seriesStart)
                put(CalendarContract.Events.DURATION, "PT10M")
                put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=10")
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            assertNotNull("Test fixture setup failed: calendar provider rejected the recurring event insert", uri)
            recurringEventId = ContentUris.parseId(uri!!)
        }

        var newOverdueTaskId: Int? = null

        try {
            // ================= Scenario A — proactive enabled =================
            val resultA = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
            assertTrue(resultA is androidx.work.ListenableWorker.Result.Success)

            val idsAfterA = activeNotificationIds()
            assertTrue("Eligible overdue task must notify", (2_000_000 + overdueTask.id) in idsAfterA)
            assertTrue("Future non-overdue task must never notify", (2_000_000 + futureTask.id) !in idsAfterA)
            assertTrue("Completed overdue task must never notify", (2_000_000 + completedTask.id) !in idsAfterA)
            assertTrue("Cancelled overdue task must never notify", (2_000_000 + cancelledTask.id) !in idsAfterA)

            val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, ZoneId.systemDefault())
            val overdueMarker = ProactiveRuleEvaluator.markerKey(overdueTask.id, dateKey)
            assertTrue("Marker written only for the notified task", overdueMarker in stateStore.load().notifiedMarkers)
            assertTrue(
                "No marker for any ineligible task",
                stateStore.load().notifiedMarkers.none {
                    it.contains(":${futureTask.id}:") || it.contains(":${completedTask.id}:") || it.contains(":${cancelledTask.id}:")
                },
            )

            var recurringTodaysOccurrenceBegin: Long? = null
            if (hasCalendar) {
                val discovery = calendarWriter.findUpcomingInstances(now, now + TimeUnit.MINUTES.toMillis(60), limit = 20)
                assertTrue(discovery is CalendarWriter.InstanceQueryOutcome.Found)
                val occurrences = (discovery as CalendarWriter.InstanceQueryOutcome.Found).occurrences

                val oneTimeMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(oneTimeEventId!!, oneTimeStart!!, ZoneId.systemDefault())
                assertTrue("Eligible upcoming one-time event must be marked notified", oneTimeMarker in stateStore.load().notifiedMarkers)
                assertTrue("Eligible upcoming one-time event must notify", activeNotificationIds().any { it in 3_000_000..(3_000_000 + 0x00FFFFFF) })

                val recurringOccurrence = occurrences.firstOrNull { it.sourceEventId == recurringEventId && it.beginMillis > now }
                assertNotNull("Recurring occurrence must be discovered through Instances", recurringOccurrence)
                recurringTodaysOccurrenceBegin = recurringOccurrence!!.beginMillis
                val recurringMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(recurringEventId!!, recurringTodaysOccurrenceBegin, ZoneId.systemDefault())
                assertTrue("Recurring occurrence must be marked notified", recurringMarker in stateStore.load().notifiedMarkers)
            }

            // ================= Scenario B — duplicate prevention =================
            val idsBeforeSecondRun = activeNotificationIds()
            val markersBeforeSecondRun = stateStore.load().notifiedMarkers

            val resultB = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
            assertTrue(resultB is androidx.work.ListenableWorker.Result.Success)

            assertEquals("No duplicate notification on an immediate second run", idsBeforeSecondRun, activeNotificationIds())
            assertEquals("Markers stable across a no-op second run", markersBeforeSecondRun, stateStore.load().notifiedMarkers)

            if (hasCalendar) {
                // Occurrence independence: a distinct future occurrence of the
                // SAME recurring series (tomorrow's) must remain eligible even
                // though today's occurrence is already marked.
                val tomorrow = now + TimeUnit.DAYS.toMillis(1)
                val resultTomorrow = ProactiveCheckWorker.runCheck(context, tomorrow, logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
                assertTrue(resultTomorrow is androidx.work.ListenableWorker.Result.Success)

                val tomorrowsMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(
                    recurringEventId!!, recurringTodaysOccurrenceBegin!! + TimeUnit.DAYS.toMillis(1), ZoneId.systemDefault(),
                )
                assertTrue(
                    "A distinct future occurrence of the same recurring series must remain independently eligible",
                    tomorrowsMarker in stateStore.load().notifiedMarkers,
                )
                assertTrue(
                    "Today's earlier occurrence marker must not be disturbed by tomorrow's eligibility",
                    ProactiveRuleEvaluator.eventOccurrenceMarkerKey(recurringEventId, recurringTodaysOccurrenceBegin, ZoneId.systemDefault()) in stateStore.load().notifiedMarkers,
                )
            }

            // ================= Scenario C — disabled mode =================
            preferenceStore.save(UserPreferences(proactiveAssistantEnabled = false))
            val idsBeforeDisabledRun = activeNotificationIds()
            val markersBeforeDisabledRun = stateStore.load().notifiedMarkers
            val lastCheckedBeforeDisabledRun = stateStore.load().lastCheckedAtMillis

            val resultC = ProactiveCheckWorker.runCheck(context, now + TimeUnit.MINUTES.toMillis(1), logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
            assertTrue(resultC is androidx.work.ListenableWorker.Result.Success)

            assertEquals("Disabled: zero new notifications", idsBeforeDisabledRun, activeNotificationIds())
            assertEquals("Disabled: zero marker mutation", markersBeforeDisabledRun, stateStore.load().notifiedMarkers)
            assertEquals(
                "Disabled: ProactiveStateStore.save() never called at all (lastCheckedAtMillis frozen)",
                lastCheckedBeforeDisabledRun,
                stateStore.load().lastCheckedAtMillis,
            )

            // "restart into a fresh process" (object level — the genuine
            // adb force-stop version of this is Phase 3's own job).
            val freshPreferenceStore = PersistentPreferenceStore.create(context, logger)
            val freshStateStore = ProactiveStateStore.create(context, logger)
            assertFalse("Disabled preference must survive fresh store reconstruction", freshPreferenceStore.load().proactiveAssistantEnabled)

            val resultCFresh = ProactiveCheckWorker.runCheck(context, now + TimeUnit.MINUTES.toMillis(2), logger, taskStore, freshStateStore, calendarWriter = calendarWriter, preferenceStore = freshPreferenceStore)
            assertTrue(resultCFresh is androidx.work.ListenableWorker.Result.Success)
            assertEquals("Disabled remains a no-op after fresh store reconstruction", idsBeforeDisabledRun, activeNotificationIds())
            assertEquals(markersBeforeDisabledRun, freshStateStore.load().notifiedMarkers)

            // ================= Scenario D — re-enable =================
            preferenceStore.save(UserPreferences(proactiveAssistantEnabled = true))

            // A NEW overdue task appears while re-enabling — proves M4-A resumes.
            val newOverdueTask = taskStore.save(
                Task(
                    id = taskStore.nextId(), title = "M4-D integrated re-enable overdue", status = TaskStatus.PENDING,
                    dueAtMillis = now - TimeUnit.MINUTES.toMillis(10),
                    createdAtMillis = now - TimeUnit.HOURS.toMillis(1), updatedAtMillis = now - TimeUnit.HOURS.toMillis(1),
                ),
            )
            newOverdueTaskId = newOverdueTask.id

            val resultD = ProactiveCheckWorker.runCheck(context, now + TimeUnit.MINUTES.toMillis(3), logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
            assertTrue(resultD is androidx.work.ListenableWorker.Result.Success)

            assertTrue("M4-A resumes: a new overdue task notifies after re-enable", (2_000_000 + newOverdueTask.id) in activeNotificationIds())
            assertTrue("Previously valid overdue marker is still respected", overdueMarker in stateStore.load().notifiedMarkers)
            assertTrue("Previously posted notification was never touched by the disabled interval", (2_000_000 + overdueTask.id) in activeNotificationIds())

            // Duplicate prevention still holds post re-enable.
            val idsAfterD = activeNotificationIds()
            val resultDAgain = ProactiveCheckWorker.runCheck(context, now + TimeUnit.MINUTES.toMillis(3), logger, taskStore, stateStore, calendarWriter = calendarWriter, preferenceStore = preferenceStore)
            assertTrue(resultDAgain is androidx.work.ListenableWorker.Result.Success)
            assertEquals("Duplicate prevention holds after re-enable", idsAfterD, activeNotificationIds())
        } finally {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(2_000_000 + overdueTask.id)
            nm.cancel(2_000_000 + futureTask.id)
            nm.cancel(2_000_000 + completedTask.id)
            nm.cancel(2_000_000 + cancelledTask.id)
            newOverdueTaskId?.let { nm.cancel(2_000_000 + it) }
            if (hasCalendar) {
                nm.activeNotifications.filter { it.id in 3_000_000..(3_000_000 + 0x00FFFFFF) }.forEach { nm.cancel(it.id) }
                oneTimeEventId?.let { calendarWriter.deleteEvent(it) }
                recurringEventId?.let { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null) }
            }
            taskStore.delete(overdueTask.id)
            taskStore.delete(futureTask.id)
            taskStore.delete(completedTask.id)
            taskStore.delete(cancelledTask.id)
            newOverdueTaskId?.let { taskStore.delete(it) }
            stateStore.clear()
            preferenceStore.save(UserPreferences(proactiveAssistantEnabled = true))
            // Guards against the documented async SharedPreferences.apply()
            // flush race on short-lived am instrument processes.
            delay(400)
        }
    }
}
