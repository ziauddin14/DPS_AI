package com.softwaremine.dps.data.android.secretary

import android.content.SharedPreferences
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarEventReader
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskPriority
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of [ExecutionVerifier] — M7 Phase D (task) and Phase F/G's
 * own general-purpose coverage (M7 Phase E adds the calendar-specific
 * tests once [ExecutionVerifier]'s calendar trigger is wired).
 *
 * ## Why the doubles are hand-written fakes, not a mocking framework
 * Mirrors this codebase's own established convention — every other test in
 * this project (`LongTermMemoryStoreTest`, `SecretaryOrchestratorTest`, and
 * so on) uses a small in-memory fake behind the real interface rather than
 * a mocking library, none of which this project depends on.
 */
class ExecutionVerifierTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val immediateDispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val inference = Dispatchers.Unconfined
    }

    private class FakeTaskRepository : TaskRepository {
        private val tasks = mutableMapOf<Int, Task>()
        var throwOnFind: Throwable? = null

        fun seed(task: Task) {
            tasks[task.id] = task
        }

        override fun all(): List<Task> = tasks.values.toList()

        override fun find(id: Int): Task? {
            throwOnFind?.let { throw it }
            return tasks[id]
        }

        override fun save(task: Task): Task {
            tasks[task.id] = task
            return task
        }

        override fun delete(id: Int): Boolean = tasks.remove(id) != null

        override fun nextId(): Int = (tasks.keys.maxOrNull() ?: 0) + 1
    }

    /** Never exercised by the task-only tests in this file — see this file's own class doc. */
    private class UnusedCalendarEventReader : CalendarEventReader {
        override fun readEventSnapshot(eventId: Long): CalendarEventReader.EventSnapshotOutcome =
            error("Not expected to be called by a task-only test")
    }

    private class FakeCalendarEventReader : CalendarEventReader {
        private val events = mutableMapOf<Long, CalendarEventReader.EventSnapshot>()
        var throwOnRead: String? = null

        fun seed(eventId: Long, snapshot: CalendarEventReader.EventSnapshot) {
            events[eventId] = snapshot
        }

        override fun readEventSnapshot(eventId: Long): CalendarEventReader.EventSnapshotOutcome {
            throwOnRead?.let { return CalendarEventReader.EventSnapshotOutcome.Failed(it) }
            val snapshot = events[eventId] ?: return CalendarEventReader.EventSnapshotOutcome.NotFound
            return CalendarEventReader.EventSnapshotOutcome.Found(snapshot)
        }
    }

    /** Minimal in-memory fake of [SharedPreferences], mirroring every other store test's own. */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var cleared = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVE_MARKER }
            override fun clear() = apply { cleared = true }
            override fun commit(): Boolean { applyPending(); return true }
            override fun apply() = applyPending()
            private fun applyPending() {
                if (cleared) values.clear()
                pending.forEach { (key, value) -> if (value === REMOVE_MARKER) values.remove(key) else values[key] = value }
                pending.clear()
            }
        }

        private companion object {
            val REMOVE_MARKER = Any()
        }
    }

    private fun verifier(
        taskRepository: TaskRepository = FakeTaskRepository(),
        calendarEventReader: CalendarEventReader = UnusedCalendarEventReader(),
        recoveryStore: PersistentRecoveryStore = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger),
    ) = ExecutionVerifier(
        taskRepository = taskRepository,
        calendarEventReader = calendarEventReader,
        persistentRecoveryStore = recoveryStore,
        dispatchers = immediateDispatchers,
        logger = silentLogger,
        now = { 1_000L },
    )

    private fun createTaskIntent(title: String = "Submit report") = DpsIntent(
        type = IntentType.TASK,
        action = IntentAction.CREATE,
        parameters = IntentParameters(title = title),
    )

    private fun createTaskResult(vararg extra: Pair<String, String>) = ToolResult.Success(
        summary = "Task added.",
        data = mapOf("task_id" to "7") + extra.toMap(),
    )

    private fun createEventIntent(title: String = "Team sync") = DpsIntent(
        type = IntentType.CALENDAR_EVENT,
        action = IntentAction.CREATE,
        parameters = IntentParameters(title = title),
    )

    private fun createEventResult(startMillis: Long = 2_000L, endMillis: Long = 5_600_000L) = ToolResult.Success(
        summary = "Added to your calendar.",
        data = mapOf(
            "event_id" to "99",
            "start_millis" to startMillis.toString(),
            "end_millis" to endMillis.toString(),
        ),
    )

    // -----------------------------------------------------------------
    // Task verification (Phase D)
    // -----------------------------------------------------------------

    @Test
    fun `a matching task verifies`() = runTest {
        val repository = FakeTaskRepository().apply {
            seed(Task(id = 7, title = "Submit report", createdAtMillis = 1L, updatedAtMillis = 1L))
        }

        val outcome = verifier(taskRepository = repository).verify(createTaskIntent(), createTaskResult())

        assertEquals(VerificationOutcome.Verified, outcome)
    }

    @Test
    fun `a task title mismatch is detected`() = runTest {
        val repository = FakeTaskRepository().apply {
            seed(Task(id = 7, title = "Something else entirely", createdAtMillis = 1L, updatedAtMillis = 1L))
        }

        val outcome = verifier(taskRepository = repository).verify(createTaskIntent(), createTaskResult())

        assertTrue(outcome is VerificationOutcome.Mismatch)
        val mismatch = outcome as VerificationOutcome.Mismatch
        assertEquals("Submit report", mismatch.expected["title"])
        assertEquals("Something else entirely", mismatch.observed["title"])
    }

    @Test
    fun `an explicitly requested field mismatch is detected even when the title matches`() = runTest {
        // The tool echoed back "priority" -> "HIGH" (what create_task actually
        // resolved and asked to be stored), but the persisted task somehow
        // ended up NORMAL — title-only verification would wrongly say VERIFIED.
        val repository = FakeTaskRepository().apply {
            seed(
                Task(
                    id = 7,
                    title = "Submit report",
                    priority = TaskPriority.NORMAL,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
        }

        val outcome = verifier(taskRepository = repository)
            .verify(createTaskIntent(), createTaskResult("priority" to "HIGH"))

        assertTrue("Expected Mismatch, got $outcome", outcome is VerificationOutcome.Mismatch)
        val mismatch = outcome as VerificationOutcome.Mismatch
        assertEquals("HIGH", mismatch.expected["priority"])
        assertEquals("NORMAL", mismatch.observed["priority"])
    }

    @Test
    fun `a due date is verified only when it was explicitly requested`() = runTest {
        val repository = FakeTaskRepository().apply {
            seed(Task(id = 7, title = "Submit report", dueAtMillis = 9_999L, createdAtMillis = 1L, updatedAtMillis = 1L))
        }

        // The create_task call never echoed a "due_millis" key — the user
        // never asked for a due date — so a due date that exists anyway
        // (e.g. set by some other path) must not be flagged as a mismatch.
        val outcome = verifier(taskRepository = repository).verify(createTaskIntent(), createTaskResult())

        assertEquals(VerificationOutcome.Verified, outcome)
    }

    @Test
    fun `a task that cannot be found is reported as not found`() = runTest {
        val outcome = verifier().verify(createTaskIntent(), createTaskResult())

        assertEquals(VerificationOutcome.NotFound, outcome)
    }

    @Test
    fun `a task observation failure is reported as observation failed, never as verified or not found`() = runTest {
        val repository = FakeTaskRepository().apply { throwOnFind = RuntimeException("storage exploded") }

        val outcome = verifier(taskRepository = repository).verify(createTaskIntent(), createTaskResult())

        assertTrue(outcome is VerificationOutcome.ObservationFailed)
        assertEquals("storage exploded", (outcome as VerificationOutcome.ObservationFailed).reason)
    }

    // -----------------------------------------------------------------
    // Calendar verification (Phase E)
    // -----------------------------------------------------------------

    @Test
    fun `a matching calendar event verifies`() = runTest {
        val reader = FakeCalendarEventReader().apply {
            seed(99L, CalendarEventReader.EventSnapshot("Team sync", 2_000L, 5_600_000L))
        }

        val outcome = verifier(calendarEventReader = reader).verify(createEventIntent(), createEventResult())

        assertEquals(VerificationOutcome.Verified, outcome)
    }

    @Test
    fun `a calendar event title mismatch is detected`() = runTest {
        val reader = FakeCalendarEventReader().apply {
            seed(99L, CalendarEventReader.EventSnapshot("Wrong title", 2_000L, 5_600_000L))
        }

        val outcome = verifier(calendarEventReader = reader).verify(createEventIntent(), createEventResult())

        assertTrue(outcome is VerificationOutcome.Mismatch)
        val mismatch = outcome as VerificationOutcome.Mismatch
        assertEquals("Team sync", mismatch.expected["title"])
        assertEquals("Wrong title", mismatch.observed["title"])
    }

    @Test
    fun `a calendar event start mismatch is detected — exact millis, no tolerance`() = runTest {
        val reader = FakeCalendarEventReader().apply {
            // One millisecond off — real-device evidence (M7 Phase C) proved
            // the Calendar Provider preserves exact precision, so even this
            // must be flagged, never silently accepted as "close enough."
            seed(99L, CalendarEventReader.EventSnapshot("Team sync", 2_001L, 5_600_000L))
        }

        val outcome = verifier(calendarEventReader = reader).verify(createEventIntent(), createEventResult())

        assertTrue(outcome is VerificationOutcome.Mismatch)
        val mismatch = outcome as VerificationOutcome.Mismatch
        assertEquals("2000", mismatch.expected["start_millis"])
        assertEquals("2001", mismatch.observed["start_millis"])
    }

    @Test
    fun `a calendar event end mismatch is detected`() = runTest {
        val reader = FakeCalendarEventReader().apply {
            seed(99L, CalendarEventReader.EventSnapshot("Team sync", 2_000L, 5_600_001L))
        }

        val outcome = verifier(calendarEventReader = reader).verify(createEventIntent(), createEventResult())

        assertTrue(outcome is VerificationOutcome.Mismatch)
        val mismatch = outcome as VerificationOutcome.Mismatch
        assertEquals("5600000", mismatch.expected["end_millis"])
        assertEquals("5600001", mismatch.observed["end_millis"])
    }

    @Test
    fun `a calendar event that cannot be found is reported as not found`() = runTest {
        val outcome = verifier(calendarEventReader = FakeCalendarEventReader())
            .verify(createEventIntent(), createEventResult())

        assertEquals(VerificationOutcome.NotFound, outcome)
    }

    @Test
    fun `a calendar provider observation failure is reported as observation failed`() = runTest {
        val reader = FakeCalendarEventReader().apply { throwOnRead = "Calendar access was denied." }

        val outcome = verifier(calendarEventReader = reader).verify(createEventIntent(), createEventResult())

        assertTrue(outcome is VerificationOutcome.ObservationFailed)
        assertEquals("Calendar access was denied.", (outcome as VerificationOutcome.ObservationFailed).reason)
    }

    // -----------------------------------------------------------------
    // General (M7 locked contract)
    // -----------------------------------------------------------------

    @Test
    fun `a non-verifiable intent type is never attempted, and never reported as a fifth outcome`() = runTest {
        val whatsappIntent = DpsIntent(
            type = IntentType.WHATSAPP_MESSAGE,
            action = IntentAction.CREATE,
            parameters = IntentParameters(person = "Ali", message = "on my way"),
        )
        val result = ToolResult.Success(summary = "ready", data = emptyMap())

        val outcome = verifier().verify(whatsappIntent, result)

        assertNull("Not applicable must be null, never one of the four VerificationOutcome cases", outcome)
    }

    @Test
    fun `a non-CREATE task action is never attempted`() = runTest {
        val listIntent = DpsIntent(type = IntentType.TASK, action = IntentAction.LIST, parameters = IntentParameters())
        val result = ToolResult.Success(summary = "You have 1 pending task.", data = mapOf("count" to "1"))

        val outcome = verifier().verify(listIntent, result)

        assertNull(outcome)
    }

    @Test
    fun `ToolResult Success and VerificationOutcome Verified are distinct types, never conflated`() = runTest {
        val repository = FakeTaskRepository().apply {
            seed(Task(id = 7, title = "Submit report", createdAtMillis = 1L, updatedAtMillis = 1L))
        }
        val result = createTaskResult()

        val outcome = verifier(taskRepository = repository).verify(createTaskIntent(), result)

        // result.isSuccess (ToolResult's own concept) says nothing about
        // whether verification ran or what it found — both are true here,
        // but from two entirely separate types with no shared supertype.
        assertTrue(result.isSuccess)
        assertEquals(VerificationOutcome.Verified, outcome)
    }

    @Test
    fun `no outcome ever triggers a second tool call`() = runTest {
        // ExecutionVerifier has no reference to any AndroidTool, ToolExecutor
        // or ToolCall constructor — structurally, it cannot invoke a second
        // execution attempt regardless of what it observes.
        val repository = FakeTaskRepository() // task never seeded -> NotFound

        verifier(taskRepository = repository).verify(createTaskIntent(), createTaskResult())

        assertTrue("FakeTaskRepository.save must never be called by verification", repository.all().isEmpty())
    }
}
