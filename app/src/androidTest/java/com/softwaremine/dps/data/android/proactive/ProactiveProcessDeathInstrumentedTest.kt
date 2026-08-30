package com.softwaremine.dps.data.android.proactive

import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
 * Real Android process-death validation for [ProactiveCheckWorker], both for
 * M4-A's overdue-task markers and M4-B's upcoming-event occurrence markers.
 * Mirrors M3-D's `ProcessDeathPersistenceInstrumentedTest` exactly: paired
 * `@Test` methods run as separate `am instrument` invocations with a genuine
 * `adb shell am force-stop` between them — never a same-process trick.
 *
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveProcessDeathInstrumentedTest#phase1CreateOverdueTaskRunCheckAndScheduleBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveProcessDeathInstrumentedTest#phase2VerifyMarkerSurvivedAndNoDuplicateNotificationAfterProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The M4-B calendar-occurrence pair (`phase1CreateUpcomingEventRunCheckAndScheduleBeforeProcessDeath`/
 * `phase2VerifyEventMarkerSurvivedAndDistinctOccurrenceRemainsEligible`) and
 * the M4-C disabled-preference pair (`phase1DisableProactiveAssistantAndCreateEligibleTaskBeforeProcessDeath`/
 * `phase2VerifyDisabledPreferenceSurvivedAndTheDefaultConstructedWorkerHonorsIt`)
 * are each run the same way, as their own separate two-invocation sequences.
 *
 * As with M3-D, `am force-stop` genuinely terminates the OS process while
 * leaving on-disk `SharedPreferences` untouched. Running both methods of a
 * pair in one combined invocation still compiles and passes but proves
 * nothing about a real process boundary — see M3-D's own completion doc for
 * the same caveat.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveProcessDeathInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    // -----------------------------------------------------------------
    // Phase 1 — run first, against a fresh process
    // -----------------------------------------------------------------

    @Test
    fun phase1CreateOverdueTaskRunCheckAndScheduleBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-D process-death overdue check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)

        val notificationId = 2_000_000 + overdue.id
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "The real notification must be posted before the process dies",
            notificationId in nm.activeNotifications.map { it.id },
        )

        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, ZoneId.systemDefault())
        val marker = ProactiveRuleEvaluator.markerKey(overdue.id, dateKey)
        assertTrue(marker in stateStore.load().notifiedMarkers)

        // Also register the periodic work, so phase 2 can confirm it survived
        // the kill without this process having to do anything more.
        ProactiveCheckWorker.schedule(context)

        // M2-D/M3-D's own finding, reused here: SharedPreferences.Editor.apply()
        // is asynchronous, and a process that exits immediately after the
        // last apply() call can lose the write before it reaches disk.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // Phase 2 — run second, after `adb shell am force-stop` between the two
    // -----------------------------------------------------------------

    @Test
    fun phase2VerifyMarkerSurvivedAndNoDuplicateNotificationAfterProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)

        // 1) The marker phase1 persisted must still be on disk — a fresh
        // ProactiveStateStore, in a genuinely new process, reading it back.
        val now = System.currentTimeMillis()
        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, ZoneId.systemDefault())
        val restoredState = stateStore.load()
        val overdueTask = taskStore.all().singleOrNull { it.title == "M4-D process-death overdue check" }
        assertNotNull("The overdue task itself must also have survived (TaskStore is unrelated to this test but must not have lost it)", overdueTask)
        val marker = ProactiveRuleEvaluator.markerKey(overdueTask!!.id, dateKey)
        assertTrue(
            "The notification marker must survive real process death",
            marker in restoredState.notifiedMarkers,
        )

        val notificationId = 2_000_000 + overdueTask.id

        try {
            // 2) Running the check again, in this fresh process, must not
            // re-post the notification — the restored marker must still work.
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)

            val nm = context.getSystemService(NotificationManager::class.java)
            assertFalse(
                "A restored marker must still suppress a duplicate notification after process death",
                notificationId in nm.activeNotifications.map { it.id },
            )

            // 3) The periodic work phase1 scheduled must also have survived
            // the kill, still registered exactly once.
            val workManager = WorkManager.getInstance(context)
            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            assertEquals(
                "Exactly one unique periodic work must still be registered after process death, got $infos",
                1,
                infos.count { it.state != WorkInfo.State.CANCELLED },
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            taskStore.delete(overdueTask.id)
            stateStore.clear()
            WorkManager.getInstance(context).cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }

    // -----------------------------------------------------------------
    // M4-B — Phase 1: run first, against a fresh process
    // -----------------------------------------------------------------

    /**
     * Creates a real, once-only future calendar event **and** a real daily
     * recurring event, runs the check, and confirms both an occurrence
     * marker and the real notification exist before the process dies. The
     * recurring event is what lets phase 2 prove "a distinct future
     * occurrence remains eligible" — its *next* occurrence (tomorrow's, at
     * this same instant plus 24h) is deliberately left un-notified here.
     */
    @Test
    fun phase1CreateUpcomingEventRunCheckAndScheduleBeforeProcessDeath(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        val target = calendarWriter.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return@runBlocking // no writable calendar on this run

        val now = System.currentTimeMillis()

        // A real daily recurring event: today's occurrence lands ~30 minutes
        // from now (within the worker's 60-minute window); tomorrow's own
        // occurrence — a distinct, real, provider-computed future
        // occurrence — is what phase 2 verifies remains independently
        // eligible after the marker for today's occurrence is restored.
        val seriesStart = now - TimeUnit.DAYS.toMillis(1) + TimeUnit.MINUTES.toMillis(30)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, target.calendarId)
            put(CalendarContract.Events.TITLE, "M4-B process-death recurring check")
            put(CalendarContract.Events.DTSTART, seriesStart)
            put(CalendarContract.Events.DURATION, "PT10M")
            put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=10")
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        assertNotNull("Test fixture setup failed: calendar provider rejected the recurring event insert", uri)
        val eventId = ContentUris.parseId(uri!!)

        val result = ProactiveCheckWorker.runCheck(context, now, logger, stateStore = stateStore, calendarWriter = calendarWriter)
        assertTrue("Expected Result.success(), got $result", result is androidx.work.ListenableWorker.Result.Success)

        val discovery = calendarWriter.findUpcomingInstances(now, now + TimeUnit.MINUTES.toMillis(60), limit = 20)
        assertTrue(discovery is CalendarWriter.InstanceQueryOutcome.Found)
        val todaysOccurrence = (discovery as CalendarWriter.InstanceQueryOutcome.Found).occurrences
            .firstOrNull { it.sourceEventId == eventId && it.beginMillis > now }
        assertNotNull("Expected today's occurrence to be found within the window", todaysOccurrence)

        val marker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, todaysOccurrence!!.beginMillis, ZoneId.systemDefault())
        assertTrue(
            "The occurrence marker must be persisted before the process dies",
            marker in stateStore.load().notifiedMarkers,
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "The real upcoming-event notification must be posted before the process dies",
            nm.activeNotifications.any { it.id in 3_000_000..(3_000_000 + 0x00FFFFFF) },
        )

        ProactiveCheckWorker.schedule(context)

        // Same async-apply()-flush finding M2-D/M3-D/M4-A's own task-based
        // phase1 already relies on.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // M4-B — Phase 2: run second, after `adb shell am force-stop` between the two
    // -----------------------------------------------------------------

    @Test
    fun phase2VerifyEventMarkerSurvivedAndDistinctOccurrenceRemainsEligible(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)

        val nowAtPhase1 = System.currentTimeMillis()
        val discovery = calendarWriter.findUpcomingInstances(nowAtPhase1 - TimeUnit.MINUTES.toMillis(5), nowAtPhase1 + TimeUnit.MINUTES.toMillis(60), limit = 20)
        assertTrue(discovery is CalendarWriter.InstanceQueryOutcome.Found)
        val occurrences = (discovery as CalendarWriter.InstanceQueryOutcome.Found).occurrences
            .filter { it.title == "M4-B process-death recurring check" }
        assertTrue("Expected the recurring event created in phase1 to still exist", occurrences.isNotEmpty())
        val todaysOccurrence = occurrences.minByOrNull { it.beginMillis }
        assertNotNull(todaysOccurrence)
        val eventId = todaysOccurrence!!.sourceEventId

        // 1) The marker phase1 persisted must still be on disk — a fresh
        // ProactiveStateStore, in a genuinely new process, reading it back.
        val restoredMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(eventId, todaysOccurrence.beginMillis, ZoneId.systemDefault())
        assertTrue(
            "The upcoming-event occurrence marker must survive real process death",
            restoredMarker in stateStore.load().notifiedMarkers,
        )

        try {
            // 2) Re-running the check now must not re-post a notification for
            // the same, already-marked occurrence.
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.id in 3_000_000..(3_000_000 + 0x00FFFFFF) }.forEach { nm.cancel(it.id) }

            val result = ProactiveCheckWorker.runCheck(context, nowAtPhase1, logger, stateStore = stateStore, calendarWriter = calendarWriter)
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertFalse(
                "A restored occurrence marker must still suppress a duplicate notification after process death",
                nm.activeNotifications.any { it.id in 3_000_000..(3_000_000 + 0x00FFFFFF) },
            )

            // 3) A distinct future occurrence of the SAME recurring event —
            // tomorrow's, one real day later — must remain independently
            // eligible: restoring today's marker must never block it.
            val tomorrow = nowAtPhase1 + TimeUnit.DAYS.toMillis(1)
            val tomorrowResult = ProactiveCheckWorker.runCheck(context, tomorrow, logger, stateStore = stateStore, calendarWriter = calendarWriter)
            assertTrue(tomorrowResult is androidx.work.ListenableWorker.Result.Success)

            val tomorrowsMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(
                eventId,
                todaysOccurrence.beginMillis + TimeUnit.DAYS.toMillis(1),
                ZoneId.systemDefault(),
            )
            assertTrue(
                "A distinct future occurrence of the same recurring series must still be able to notify after an earlier occurrence's marker survived process death",
                tomorrowsMarker in stateStore.load().notifiedMarkers,
            )
        } finally {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.id in 3_000_000..(3_000_000 + 0x00FFFFFF) }.forEach { nm.cancel(it.id) }
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
            stateStore.clear()
            WorkManager.getInstance(context).cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }

    // -----------------------------------------------------------------
    // M4-C — Phase 1: run first, against a fresh process
    // -----------------------------------------------------------------

    /**
     * Persists `proactiveAssistantEnabled = false` and a genuinely eligible
     * overdue task, then dies — phase 2 proves the disabled gate itself
     * survives real process death and a fresh process's own default-
     * constructed [PersistentPreferenceStore] (not one explicitly injected
     * by the test) still honors it.
     */
    @Test
    fun phase1DisableProactiveAssistantAndCreateEligibleTaskBeforeProcessDeath(): Unit = runBlocking {
        val preferenceStore = PersistentPreferenceStore.create(context, logger)
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()
        preferenceStore.save(UserPreferences(proactiveAssistantEnabled = false))

        val now = System.currentTimeMillis()
        taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-C process-death disabled check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        assertFalse(
            "Sanity check before the process dies — the disabled value must actually be on disk",
            preferenceStore.load().proactiveAssistantEnabled,
        )

        // Same async-apply()-flush finding M2-D/M3-D/M4-A/M4-B's own phase1
        // methods already rely on.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // M4-C — Phase 2: run second, after `adb shell am force-stop` between the two
    // -----------------------------------------------------------------

    @Test
    fun phase2VerifyDisabledPreferenceSurvivedAndTheDefaultConstructedWorkerHonorsIt(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)

        // A brand-new PersistentPreferenceStore, in a genuinely new process,
        // reading back what phase1 persisted.
        val freshPreferenceStore = PersistentPreferenceStore.create(context, logger)
        assertFalse(
            "The disabled preference must survive real process death",
            freshPreferenceStore.load().proactiveAssistantEnabled,
        )

        val overdueTask = taskStore.all().singleOrNull { it.title == "M4-C process-death disabled check" }
        assertNotNull("The overdue task itself must also have survived process death", overdueTask)

        val now = System.currentTimeMillis()
        val notificationId = 2_000_000 + overdueTask!!.id

        try {
            // Deliberately does NOT inject preferenceStore — this exercises
            // runCheck()'s own default `PersistentPreferenceStore.create(context, logger)`
            // parameter, in a fresh process, proving the real production
            // doWork() path (which never injects it either) genuinely honors
            // a disabled preference that survived a real kill, not just an
            // explicitly-passed test double.
            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)

            val nm = context.getSystemService(NotificationManager::class.java)
            assertFalse(
                "A genuinely eligible overdue task must still produce zero notifications after process death while disabled",
                notificationId in nm.activeNotifications.map { it.id },
            )
            assertTrue(
                "No marker may exist — the disabled gate must have skipped the state store entirely",
                stateStore.load().notifiedMarkers.isEmpty(),
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            taskStore.delete(overdueTask.id)
            stateStore.clear()
            PersistentPreferenceStore.create(context, logger).save(UserPreferences(proactiveAssistantEnabled = true))
            delay(400)
        }
    }
}
