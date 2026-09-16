package com.softwaremine.dps.data.android.secretary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.concurrency.DefaultDispatcherProvider
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.secretary.PendingVerification
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

/**
 * M7: real Android process-death validation for [PendingVerification] — the
 * M3-D/M5-C/M6 methodology
 * ([com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest],
 * [com.softwaremine.dps.ai.secretary.CheckpointRecoveryInstrumentedTest])
 * applied to M7's own persisted record instead.
 *
 * ## Why two `@Test` methods per scenario, run as separate `am instrument` invocations
 * See [com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest]'s
 * own doc for the full reasoning — a single JUnit method cannot outlive its
 * own process. Run as:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.secretary.ExecutionVerifierProcessDeathInstrumentedTest#phase1ASaveTaskVerificationBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.secretary.ExecutionVerifierProcessDeathInstrumentedTest#phase2AResolveTaskVerificationAfterProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * and the same shape again for the `...B...` (calendar) pair.
 *
 * ## Why phase1 saves the `PendingVerification` directly, not via a real `handle()` call
 * [ExecutionVerifier.verify]'s own save-then-observe-then-clear sequence
 * runs, start to finish, inside one synchronous suspend call with no
 * `adb`-reachable window in between — the identical honesty note
 * [CheckpointRecoveryInstrumentedTest]'s own class doc already makes for
 * [com.softwaremine.dps.domain.secretary.OperationCheckpoint]. What is
 * genuine here is the same thing that is genuine there: a real
 * `adb shell am force-stop` between a real, on-disk
 * [PersistentRecoveryStore.saveVerification] write (via the same
 * production API [ExecutionVerifier] itself calls) and the read that
 * resolves it in a completely fresh process.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionVerifierProcessDeathInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun verifier(): ExecutionVerifier = ExecutionVerifier(
        taskRepository = AndroidTaskStore(context, logger),
        calendarEventReader = CalendarWriter(context, logger),
        persistentRecoveryStore = PersistentRecoveryStore.create(context, logger),
        dispatchers = DefaultDispatcherProvider(),
        logger = logger,
    )

    // -----------------------------------------------------------------
    // Scenario A — task
    // -----------------------------------------------------------------

    @Test
    fun phase1ASaveTaskVerificationBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        taskStore.save(Task(id = TASK_ID, title = TASK_TITLE, createdAtMillis = 1L, updatedAtMillis = 1L))

        PersistentRecoveryStore.create(context, logger).saveVerification(
            PendingVerification.Task(taskId = TASK_ID, expectedTitle = TASK_TITLE, requestedAtMillis = 1_000L),
        )

        // Confirmed durable before the process is killed externally.
        assertEquals(
            PendingVerification.Task(taskId = TASK_ID, expectedTitle = TASK_TITLE, requestedAtMillis = 1_000L),
            PersistentRecoveryStore.create(context, logger).loadVerification(),
        )
    }

    @Test
    fun phase2AResolveTaskVerificationAfterProcessDeath(): Unit = runBlocking {
        // A fresh AndroidDpsLogger, a fresh AndroidTaskStore, a fresh
        // PersistentRecoveryStore.create() call — nothing here is the same
        // in-process object phase1 used. Only the on-disk SharedPreferences
        // files connect them, exactly as a real process restart would.
        val recoveryStore = PersistentRecoveryStore.create(context, logger)
        val pendingBeforeResolution = recoveryStore.loadVerification()
        assertEquals(
            "The pending verification written before process death must survive it",
            PendingVerification.Task(taskId = TASK_ID, expectedTitle = TASK_TITLE, requestedAtMillis = 1_000L),
            pendingBeforeResolution,
        )

        val outcome = verifier().resolvePendingVerificationIfAny()

        assertEquals("The real task survived, matching, so this must resolve as Verified", VerificationOutcome.Verified, outcome)
        assertNull("The pending verification must be cleared once resolved", recoveryStore.loadVerification())

        // Cleanup, now that the survival is proven.
        AndroidTaskStore(context, logger).delete(TASK_ID)
    }

    // -----------------------------------------------------------------
    // Scenario B — calendar event
    // -----------------------------------------------------------------

    @Test
    fun phase1BSaveCalendarVerificationBeforeProcessDeath(): Unit = runBlocking {
        val writer = CalendarWriter(context, logger)
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return@runBlocking // no writable calendar on this run

        val start = System.currentTimeMillis() + 60_000L
        val end = start + 3_600_000L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = EVENT_TITLE,
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        if (created !is CalendarWriter.InsertOutcome.Created) return@runBlocking

        PersistentRecoveryStore.create(context, logger).saveVerification(
            PendingVerification.CalendarEvent(
                eventId = created.eventId,
                expectedTitle = EVENT_TITLE,
                expectedStartMillis = start,
                expectedEndMillis = end,
                requestedAtMillis = 2_000L,
            ),
        )
    }

    @Test
    fun phase2BResolveCalendarVerificationAfterProcessDeath(): Unit = runBlocking {
        val recoveryStore = PersistentRecoveryStore.create(context, logger)
        // The real event id phase1B created travels across the process
        // boundary inside the persisted PendingVerification record itself
        // — no separate hand-off mechanism needed.
        val pending = recoveryStore.loadVerification() as? PendingVerification.CalendarEvent
            ?: return@runBlocking // phase1B returned early (no writable calendar) — nothing to verify

        val outcome = verifier().resolvePendingVerificationIfAny()

        assertEquals("The real event survived, matching, so this must resolve as Verified", VerificationOutcome.Verified, outcome)
        assertNull("The pending verification must be cleared once resolved", recoveryStore.loadVerification())

        // Cleanup, now that the survival is proven.
        CalendarWriter(context, logger).deleteEvent(pending.eventId)
    }

    private companion object {
        const val TASK_ID = 900_001
        const val TASK_TITLE = "M7-ProcessDeath-Task"
        const val EVENT_TITLE = "M7-ProcessDeath-Event"
    }
}
