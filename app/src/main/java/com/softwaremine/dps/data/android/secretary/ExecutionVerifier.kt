package com.softwaremine.dps.data.android.secretary

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarEventReader
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.secretary.PendingVerification
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.withContext

/**
 * Independently observes and compares a create operation's claimed outcome
 * against the real state it was supposed to produce (M7).
 *
 * ## Why this exists apart from the tools themselves
 * [com.softwaremine.dps.domain.tool.ToolResult.Success] means "the tool's
 * `execute()` returned this" — decided entirely by the tool at the moment
 * it returns. This class answers a strictly later, separate question:
 * "does a fresh read of the record that call claims to have produced
 * actually match what was requested." Keeping it out of
 * [com.softwaremine.dps.data.android.tool.AndroidTaskTool]/
 * [com.softwaremine.dps.data.android.tool.AndroidCalendarTool] themselves
 * means those tools stay focused on performing their one action, and
 * reuses their own [TaskRepository]/[CalendarEventReader] collaborators
 * for observation rather than duplicating any read logic.
 *
 * ## Why [verify] returns `null` for most calls
 * Verification is scoped to exactly two (type, action) pairs — task create
 * and calendar-event create — per M7's own locked scope. Every other
 * intent (WhatsApp, email, phone, notification, reminder, meeting note,
 * work log, action item, memory, and every non-CREATE task/calendar
 * action) returns `null` here: "not applicable," never a fourth outcome
 * alongside [VerificationOutcome]'s own four. A caller must not confuse
 * `null` ("never attempted") with [VerificationOutcome.ObservationFailed]
 * ("attempted and inconclusive") — they are different facts.
 *
 * ## The persist-before-observe sequence
 * [verify] saves a [PendingVerification] record *before* reading anything
 * back, and clears it only once a [VerificationOutcome] has actually been
 * produced — mirroring [PersistentRecoveryStore.saveCheckpoint]/[PersistentRecoveryStore.clearCheckpoint]'s
 * own "durable before the risk window, cleared only on a confirmed
 * outcome" discipline exactly. A process death between those two calls
 * leaves the record for [resolvePendingVerificationIfAny] to find and
 * resolve on the next construction.
 *
 * ## Dependencies
 * [TaskRepository] (interface, JVM-fakeable), [CalendarEventReader]
 * (interface, JVM-fakeable — [com.softwaremine.dps.data.android.calendar.CalendarWriter]
 * is the real implementation), [PersistentRecoveryStore], [DispatcherProvider]
 * (both `TaskRepository.find`/`CalendarEventReader.readEventSnapshot` are
 * blocking calls — `SharedPreferences`/`ContentResolver` I/O — dispatched
 * on [DispatcherProvider.io], the same dispatcher
 * [com.softwaremine.dps.ai.tool.DefaultToolExecutor] already uses for
 * every tool call).
 */
class ExecutionVerifier(
    private val taskRepository: TaskRepository,
    private val calendarEventReader: CalendarEventReader,
    private val persistentRecoveryStore: PersistentRecoveryStore,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Verifies [result] against [intent], when the (type, action) pair is
     * within M7's locked scope — otherwise returns `null` (see this
     * class's own doc for why `null` is not a fifth outcome).
     */
    suspend fun verify(intent: DpsIntent, result: ToolResult.Success): VerificationOutcome? {
        val pending = pendingVerificationFor(intent, result) ?: return null

        persistentRecoveryStore.saveVerification(pending)
        val outcome = resolve(pending)
        persistentRecoveryStore.clearVerification()
        return outcome
    }

    /**
     * Resolves whatever [PendingVerification] a previous process left
     * behind, or `null` when nothing was pending. Read-only and
     * side-effect-free beyond the store itself — see [PendingVerification]'s
     * own doc for why this is always safe to run regardless of how much
     * time has passed.
     */
    suspend fun resolvePendingVerificationIfAny(): VerificationOutcome? {
        val pending = persistentRecoveryStore.loadVerification() ?: return null
        val outcome = resolve(pending)
        persistentRecoveryStore.clearVerification()
        return outcome
    }

    private fun pendingVerificationFor(intent: DpsIntent, result: ToolResult.Success): PendingVerification? {
        if (intent.action != IntentAction.CREATE) return null

        return when (intent.type) {
            IntentType.TASK -> {
                val taskId = result.data["task_id"]?.toIntOrNull() ?: return null
                val expectedTitle = intent.parameters.value(IntentField.TITLE) ?: return null
                PendingVerification.Task(
                    taskId = taskId,
                    expectedTitle = expectedTitle,
                    expectedNotes = result.data["notes"],
                    expectedPriority = result.data["priority"],
                    expectedDueMillis = result.data["due_millis"]?.toLongOrNull(),
                    requestedAtMillis = now(),
                )
            }

            IntentType.CALENDAR_EVENT -> {
                val eventId = result.data["event_id"]?.toLongOrNull() ?: return null
                val expectedTitle = intent.parameters.value(IntentField.TITLE) ?: return null
                val startMillis = result.data["start_millis"]?.toLongOrNull() ?: return null
                val endMillis = result.data["end_millis"]?.toLongOrNull() ?: return null
                PendingVerification.CalendarEvent(
                    eventId = eventId,
                    expectedTitle = expectedTitle,
                    expectedStartMillis = startMillis,
                    expectedEndMillis = endMillis,
                    requestedAtMillis = now(),
                )
            }

            else -> null
        }
    }

    private suspend fun resolve(pending: PendingVerification): VerificationOutcome = when (pending) {
        is PendingVerification.Task -> resolveTask(pending)
        is PendingVerification.CalendarEvent -> resolveCalendarEvent(pending)
    }

    /**
     * Compares [pending]'s expected fields against a fresh
     * [TaskRepository.find] read.
     *
     * Only the fields the user actually specified are compared — see
     * [PendingVerification.Task]'s own doc for why `null` means "never
     * asked for," not "expected to be absent." `title` is always checked;
     * it is the one field every `create_task` call requires.
     *
     * Normalization is deterministic and minimal: trim whitespace, exact
     * equality otherwise — no fuzzy matching, no case-folding (a task's
     * title is never mutated in case by [com.softwaremine.dps.data.android.productivity.AndroidTaskStore.save],
     * so an exact round-trip is the correct bar, unlike
     * [com.softwaremine.dps.data.android.tool.AndroidTaskTool.resolveTarget]'s
     * deliberately looser substring match for addressing a task by a
     * spoken reference).
     */
    private suspend fun resolveTask(pending: PendingVerification.Task): VerificationOutcome {
        val observed = try {
            withContext(dispatchers.io) { taskRepository.find(pending.taskId) }
        } catch (throwable: Throwable) {
            logger.w(TAG, "Task observation failed for id=${pending.taskId}", throwable)
            return VerificationOutcome.ObservationFailed(throwable.message ?: "Could not read the task.")
        } ?: return VerificationOutcome.NotFound

        val expected = buildMap {
            put("title", pending.expectedTitle.trim())
            pending.expectedNotes?.let { put("notes", it.trim()) }
            pending.expectedPriority?.let { put("priority", it.trim()) }
            pending.expectedDueMillis?.let { put("due_millis", it.toString()) }
        }
        val observedValues = mapOf(
            "title" to observed.title.trim(),
            "notes" to (observed.notes?.trim() ?: ""),
            "priority" to (observed.priority?.name ?: ""),
            "due_millis" to (observed.dueAtMillis?.toString() ?: ""),
        )
        val actual = expected.keys.associateWith { key -> observedValues.getValue(key) }

        return if (expected == actual) VerificationOutcome.Verified else VerificationOutcome.Mismatch(expected, actual)
    }

    /**
     * Compares [pending]'s expected fields against a fresh
     * [CalendarEventReader.readEventSnapshot] read.
     *
     * Exact `Long` equality for both timestamps — real-device evidence
     * (`CalendarWriterInstrumentedTest#readEventSnapshotPreservesExactStartAndEndMillisOnRealProviderRoundTrip`,
     * M7 Phase C) confirmed the Calendar Provider preserves millisecond
     * precision on `DTSTART`/`DTEND` exactly; no tolerance was invented.
     */
    private suspend fun resolveCalendarEvent(pending: PendingVerification.CalendarEvent): VerificationOutcome {
        val outcome = withContext(dispatchers.io) { calendarEventReader.readEventSnapshot(pending.eventId) }

        return when (outcome) {
            is CalendarEventReader.EventSnapshotOutcome.Found -> {
                val expected = mapOf(
                    "title" to pending.expectedTitle.trim(),
                    "start_millis" to pending.expectedStartMillis.toString(),
                    "end_millis" to pending.expectedEndMillis.toString(),
                )
                val actual = mapOf(
                    "title" to outcome.snapshot.title.trim(),
                    "start_millis" to outcome.snapshot.startMillis.toString(),
                    "end_millis" to outcome.snapshot.endMillis.toString(),
                )
                if (expected == actual) VerificationOutcome.Verified else VerificationOutcome.Mismatch(expected, actual)
            }

            CalendarEventReader.EventSnapshotOutcome.NotFound -> VerificationOutcome.NotFound

            is CalendarEventReader.EventSnapshotOutcome.Failed -> VerificationOutcome.ObservationFailed(outcome.reason)
        }
    }

    private companion object {
        const val TAG = "ExecutionVerifier"
    }
}
