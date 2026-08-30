package com.softwaremine.dps.ai.secretary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolResponseGenerator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
import com.softwaremine.dps.data.android.tool.AndroidCalendarTool
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.secretary.OperationCheckpoint
import com.softwaremine.dps.domain.secretary.OperationType
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * M5-E: real Android verification of `create_event` checkpointing, extending
 * [CheckpointRecoveryInstrumentedTest]'s own pattern (M5-C) to the third
 * Category-C operation.
 *
 * ## No reconciliation — a platform restriction, not a design choice
 * The original M5-E design also minted a correlation id and asked
 * [CalendarWriter] to tag the created event with it via
 * `CalendarContract.ExtendedProperties`, so a leftover checkpoint could be
 * definitively confirmed rather than merely surfaced. That write throws
 * `IllegalArgumentException: Only sync adapters may write using
 * content://com.android.calendar/extendedproperties` on a real device — a
 * platform-enforced restriction no ordinary app can work around. See
 * [OperationCheckpoint]'s own doc for the full account. `create_event`'s
 * checkpoint therefore behaves exactly like `create_task`'s/`create_reminder`'s
 * own: detect, notify once, clear, never reconcile, never auto-retry — and
 * this file's tests mirror [CheckpointRecoveryInstrumentedTest]'s own
 * structure for exactly that reason, one operation type narrower.
 *
 * ## Honesty note (mirrors [CheckpointRecoveryInstrumentedTest]'s own)
 * [AndroidCalendarTool.execute]'s checkpoint-write-then-real-write sequence
 * is synchronous, in one function call — there is no window an external
 * `adb` command can land inside. The "interrupted before the real write"
 * scenario below reproduces the exact on-disk state such an interruption
 * would leave, using the same production
 * [PersistentRecoveryStore.saveCheckpoint] API the tool itself calls. What
 * is genuine is the process boundary itself: a real `adb shell am
 * force-stop`, confirmed via `pidof`, between phase 1 and phase 2.
 */
@RunWith(AndroidJUnit4::class)
class CalendarCheckpointRecoveryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays a fixed script of classifications — mirrors every other file in this suite. */
    private class ScriptedEngine(vararg replies: String) : AiEngine {
        private val replies = replies.toList()
        private var index = 0

        override val state: StateFlow<AiState> = MutableStateFlow(AiState.Idle)
        override val activeModel: ModelDescriptor? = null

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(descriptor: ModelDescriptor, config: ModelConfig): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)
        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = emptyFlow()

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> {
            val reply = replies.getOrElse(index) { replies.lastOrNull() ?: "" }
            index++
            return DpsResult.Success(
                AiCompletion(
                    text = reply,
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 100, completionTokens = 20),
                    durationMillis = 1_000,
                ),
            )
        }

        override suspend fun tokenCount(text: String): DpsResult<Int> = DpsResult.Success(text.length / 4)
        override suspend fun shutdown() = Unit
    }

    /** A [SecretaryOrchestrator] against the real tool layer, with only the model scripted — mirrors [CheckpointRecoveryInstrumentedTest]'s own factory. */
    private fun scriptedSecretary(
        vararg classifications: String,
        persistentRecoveryStore: PersistentRecoveryStore,
    ): SecretaryOrchestrator {
        val toolOrchestrator = ToolOrchestrator(
            engine = ScriptedEngine(*classifications),
            executor = container.toolExecutor,
            registry = container.toolRegistry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )

        return SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(),
            temporalPhraseResolver = TemporalPhraseResolver(),
            temporalGroundingGuard = TemporalGroundingGuard(),
            temporalStepAttributor = TemporalStepAttributor(TemporalPhraseSpanFinder(), TemporalGroundingGuard()),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            persistentMemoryStore = PersistentMemoryStore.create(context, silentLogger),
            persistentPreferenceStore = PersistentPreferenceStore.create(context, silentLogger),
            persistentRecoveryStore = persistentRecoveryStore,
            logger = silentLogger,
        )
    }

    /** A wide, title-filtered lookup — avoids depending on any single fixed time window. */
    private fun eventsTitled(writer: CalendarWriter, title: String): List<CalendarWriter.EventSummary> {
        val from = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        val to = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)
        val found = writer.findEvents(from, to, 50)
        return (found as? CalendarWriter.QueryOutcome.Found)?.events.orEmpty().filter { it.title == title }
    }

    private fun deleteEventsTitled(writer: CalendarWriter, title: String): Int {
        val matches = eventsTitled(writer, title)
        matches.forEach { writer.deleteEvent(it.id) }
        return matches.size
    }

    // -----------------------------------------------------------------
    // Single-invocation lifecycle tests — no process death involved
    // -----------------------------------------------------------------

    @Test
    fun createEventLeavesNoCheckpointBehindAfterConfirmedSuccess() = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clearCheckpoint()
        val tool = AndroidCalendarTool(calendarWriter, recoveryStore)
        val title = "M5-E lifecycle create_event"
        val start = System.currentTimeMillis() + 60 * 60 * 1000L

        var eventId: Long? = null
        try {
            assertNull("No checkpoint should exist before any create runs", recoveryStore.loadCheckpoint())

            val result = tool.execute(
                ToolCall(ToolId.CALENDAR, "create_event", mapOf("title" to title, "start" to start.toString())),
            )
            assertTrue("Expected Success, got $result", result is ToolResult.Success)
            eventId = (result as ToolResult.Success).data["event_id"]!!.toLong()

            assertNull("A confirmed-successful create must leave no checkpoint behind", recoveryStore.loadCheckpoint())
        } finally {
            eventId?.let { calendarWriter.deleteEvent(it) }
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    @Test
    fun anUnclearedCreateEventCheckpointIsSurfacedOnceThenNeverAutoRetried() = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        val title = "M5-E interrupted create_event"
        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_EVENT,
                operationId = OperationCheckpoint.UNUSED_OPERATION_ID,
                title = title,
                requestedAtMillis = System.currentTimeMillis(),
            ),
        )

        try {
            val secretary = scriptedSecretary("not valid json", persistentRecoveryStore = recoveryStore)

            val first = secretary.handle("hello")
            assertTrue("Expected the checkpoint notice, got $first", first is ToolOrchestrator.Outcome.Conversational)
            val text = (first as ToolOrchestrator.Outcome.Conversational).replyText
            assertNotNull("Expected the notice to carry reply text", text)
            assertTrue("Expected the notice to name the interrupted event, got: $text", text!!.contains(title))
            assertTrue(
                "The checkpoint must never trigger an automatic create — no event with this title may exist",
                eventsTitled(calendarWriter, title).isEmpty(),
            )
            assertNull("Surfacing the checkpoint once must clear it durably", recoveryStore.loadCheckpoint())

            val second = secretary.handle("hello")
            val secondText = (second as? ToolOrchestrator.Outcome.Conversational)?.replyText
            assertTrue(
                "A second call must not repeat the checkpoint notice",
                secondText == null || !secondText.contains(title),
            )
        } finally {
            deleteEventsTitled(calendarWriter, title)
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Scenario A — checkpoint survives real process death, event never
    // created, no automatic retry
    // -----------------------------------------------------------------

    @Test
    fun phase1ACreateEventCheckpointBeforeProcessDeath(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_EVENT,
                operationId = OperationCheckpoint.UNUSED_OPERATION_ID,
                title = SCENARIO_A_TITLE,
                requestedAtMillis = System.currentTimeMillis(),
            ),
        )

        assertNotNull("Checkpoint must be durable before this process ends", recoveryStore.loadCheckpoint())
        assertTrue(
            "No event must exist yet — this reproduces death before the real insert",
            eventsTitled(calendarWriter, SCENARIO_A_TITLE).isEmpty(),
        )

        delay(1500)
    }

    @Test
    fun phase2AVerifyCheckpointSurvivedDeathWithNoDuplicateEventCreated(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        val survived = recoveryStore.loadCheckpoint()
        assertNotNull("The checkpoint must survive real process death", survived)
        assertEquals(SCENARIO_A_TITLE, survived?.title)

        try {
            val secretary = scriptedSecretary("not valid json", persistentRecoveryStore = recoveryStore)
            val prompt = secretary.handle("hello")
            assertTrue("Expected the checkpoint notice, got $prompt", prompt is ToolOrchestrator.Outcome.Conversational)
            val text = (prompt as ToolOrchestrator.Outcome.Conversational).replyText
            assertNotNull(text)
            assertTrue(text!!.contains(SCENARIO_A_TITLE))
            assertTrue(
                "A real process death must never result in an automatically created event",
                eventsTitled(calendarWriter, SCENARIO_A_TITLE).isEmpty(),
            )
            assertNull("The checkpoint must be cleared after being surfaced once", recoveryStore.loadCheckpoint())
        } finally {
            deleteEventsTitled(calendarWriter, SCENARIO_A_TITLE)
            recoveryStore.clearCheckpoint()
        }
    }

    // -----------------------------------------------------------------
    // Scenario B — a confirmed-successful create must not resurrect
    // anything after real process death
    // -----------------------------------------------------------------

    @Test
    fun phase1BSuccessfulEventCreationBeforeProcessDeath(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        // The real production tool, run to genuine, confirmed completion.
        val tool = AndroidCalendarTool(calendarWriter, recoveryStore)
        val start = System.currentTimeMillis() + 60 * 60 * 1000L
        val result = tool.execute(
            ToolCall(ToolId.CALENDAR, "create_event", mapOf("title" to SCENARIO_B_TITLE, "start" to start.toString())),
        )
        assertTrue("Expected Success, got $result", result is ToolResult.Success)

        assertNull("A confirmed success must already have cleared its own checkpoint", recoveryStore.loadCheckpoint())
        assertEquals(1, eventsTitled(calendarWriter, SCENARIO_B_TITLE).size)

        delay(1500)
    }

    @Test
    fun phase2BVerifyCompletedEventCreationIsNotResurrectedAsAPendingCheckpoint(): Unit = runBlocking {
        val calendarWriter = CalendarWriter(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        assertNull("A completed create_event must never resurrect as a checkpoint after death", recoveryStore.loadCheckpoint())
        assertNull("It must also never resurrect as an M5-B execution-recovery prompt", recoveryStore.load())

        try {
            val secretary = scriptedSecretary("not valid json", persistentRecoveryStore = recoveryStore)
            val greeting = secretary.handle("hello")
            assertTrue("Expected an ordinary conversational reply, got $greeting", greeting is ToolOrchestrator.Outcome.Conversational)
            val text = (greeting as ToolOrchestrator.Outcome.Conversational).replyText
            assertTrue(
                "A clean restart with nothing pending must never surface a checkpoint/recovery notice",
                text == null || !text.contains(SCENARIO_B_TITLE),
            )
            assertEquals(
                "The event created in phase1 must exist exactly once — no duplicate from the restart",
                1,
                eventsTitled(calendarWriter, SCENARIO_B_TITLE).size,
            )
        } finally {
            deleteEventsTitled(calendarWriter, SCENARIO_B_TITLE)
            recoveryStore.clearCheckpoint()
        }
    }

    private companion object {
        const val SCENARIO_A_TITLE = "M5-E scenario A create_event"
        const val SCENARIO_B_TITLE = "M5-E scenario B completed create_event"
    }
}
