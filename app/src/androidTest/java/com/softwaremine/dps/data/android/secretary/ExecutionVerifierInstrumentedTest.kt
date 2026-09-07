package com.softwaremine.dps.data.android.secretary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.concurrency.DefaultDispatcherProvider
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarEventReader
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device verification of [ExecutionVerifier]'s task path (M7) —
 * against the real [AndroidTaskStore] (`SharedPreferences`), not a fake.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionVerifierInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    /** Never exercised by a task-only test — see [verifier]'s own default. */
    private val unusedCalendarEventReader = object : CalendarEventReader {
        override fun readEventSnapshot(eventId: Long): CalendarEventReader.EventSnapshotOutcome =
            error("Not expected to be called by a task-only test")
    }

    private fun verifier(
        taskStore: AndroidTaskStore,
        calendarEventReader: CalendarEventReader = unusedCalendarEventReader,
    ): ExecutionVerifier = ExecutionVerifier(
        taskRepository = taskStore,
        calendarEventReader = calendarEventReader,
        persistentRecoveryStore = PersistentRecoveryStore.create(context, logger),
        dispatchers = DefaultDispatcherProvider(),
        logger = logger,
    )

    private fun createIntent(title: String) = DpsIntent(
        type = IntentType.TASK,
        action = IntentAction.CREATE,
        parameters = IntentParameters(title = title),
    )

    private fun createEventIntent(title: String) = DpsIntent(
        type = IntentType.CALENDAR_EVENT,
        action = IntentAction.CREATE,
        parameters = IntentParameters(title = title),
    )

    @Test
    fun aRealCreatedTaskVerifiesAgainstTheRealStore(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val id = taskStore.nextId()
        taskStore.save(Task(id = id, title = "M7 verifier check", createdAtMillis = 1L, updatedAtMillis = 1L))

        try {
            val result = ToolResult.Success("Task added.", mapOf("task_id" to id.toString()))
            val outcome = verifier(taskStore).verify(createIntent("M7 verifier check"), result)

            assertEquals(VerificationOutcome.Verified, outcome)
        } finally {
            taskStore.delete(id)
        }
    }

    @Test
    fun anExternallyMutatedRealTaskIsDetectedAsAMismatch(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val id = taskStore.nextId()
        taskStore.save(Task(id = id, title = "M7 verifier check", createdAtMillis = 1L, updatedAtMillis = 1L))

        try {
            // Simulates the record having actually landed with different
            // content than requested — the real read-back must catch it.
            taskStore.save(taskStore.find(id)!!.copy(title = "Mutated by another writer"))

            val result = ToolResult.Success("Task added.", mapOf("task_id" to id.toString()))
            val outcome = verifier(taskStore).verify(createIntent("M7 verifier check"), result)

            assertTrue("Expected Mismatch, got $outcome", outcome is VerificationOutcome.Mismatch)
        } finally {
            taskStore.delete(id)
        }
    }

    @Test
    fun aDeletedRealTaskIsReportedAsNotFound(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val id = taskStore.nextId()
        taskStore.save(Task(id = id, title = "M7 verifier check", createdAtMillis = 1L, updatedAtMillis = 1L))
        taskStore.delete(id)

        val result = ToolResult.Success("Task added.", mapOf("task_id" to id.toString()))
        val outcome = verifier(taskStore).verify(createIntent("M7 verifier check"), result)

        assertEquals(VerificationOutcome.NotFound, outcome)
    }

    // -----------------------------------------------------------------
    // Calendar (Phase E) — real Calendar Provider
    // -----------------------------------------------------------------

    private fun hasCalendarPermission(): Boolean {
        val manager = com.softwaremine.dps.data.android.permission.AndroidPermissionManager(context, logger)
        return manager.state(com.softwaremine.dps.domain.permission.DpsPermission.READ_CALENDAR).isUsable &&
            manager.state(com.softwaremine.dps.domain.permission.DpsPermission.WRITE_CALENDAR).isUsable
    }

    private fun eventResult(eventId: Long, startMillis: Long, endMillis: Long) = ToolResult.Success(
        "Added to your calendar.",
        mapOf(
            "event_id" to eventId.toString(),
            "start_millis" to startMillis.toString(),
            "end_millis" to endMillis.toString(),
        ),
    )

    @Test
    fun aRealCreatedCalendarEventVerifiesAgainstTheRealProvider(): Unit = runBlocking {
        if (!hasCalendarPermission()) return@runBlocking
        val writer = CalendarWriter(context, logger)
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return@runBlocking

        val start = System.currentTimeMillis() + 60_000L
        val end = start + 3_600_000L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M7 verifier calendar check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = java.util.TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val outcome = verifier(AndroidTaskStore(context, logger), writer)
                .verify(createEventIntent("M7 verifier calendar check"), eventResult(eventId, start, end))

            assertEquals(VerificationOutcome.Verified, outcome)
        } finally {
            writer.deleteEvent(eventId)
        }
    }

    @Test
    fun anExternallyMutatedRealCalendarEventIsDetectedAsAMismatch(): Unit = runBlocking {
        if (!hasCalendarPermission()) return@runBlocking
        val writer = CalendarWriter(context, logger)
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return@runBlocking

        val start = System.currentTimeMillis() + 60_000L
        val end = start + 3_600_000L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M7 verifier calendar check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = java.util.TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            // Simulates the real row having landed with a different time
            // than requested.
            writer.updateEvent(eventId, startMillis = start + 120_000L, endMillis = end + 120_000L)

            val outcome = verifier(AndroidTaskStore(context, logger), writer)
                .verify(createEventIntent("M7 verifier calendar check"), eventResult(eventId, start, end))

            assertTrue("Expected Mismatch, got $outcome", outcome is VerificationOutcome.Mismatch)
        } finally {
            writer.deleteEvent(eventId)
        }
    }

    @Test
    fun aDeletedRealCalendarEventIsReportedAsNotFound(): Unit = runBlocking {
        if (!hasCalendarPermission()) return@runBlocking
        val writer = CalendarWriter(context, logger)
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return@runBlocking

        val start = System.currentTimeMillis() + 60_000L
        val end = start + 3_600_000L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M7 verifier calendar check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = java.util.TimeZone.getDefault().id,
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId
        writer.deleteEvent(eventId)

        val outcome = verifier(AndroidTaskStore(context, logger), writer)
            .verify(createEventIntent("M7 verifier calendar check"), eventResult(eventId, start, end))

        assertEquals(VerificationOutcome.NotFound, outcome)
    }
}
