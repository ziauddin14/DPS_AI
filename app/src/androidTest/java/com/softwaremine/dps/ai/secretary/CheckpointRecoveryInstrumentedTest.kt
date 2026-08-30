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
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.data.android.reminder.ReminderScheduler
import com.softwaremine.dps.data.android.reminder.ReminderStore
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
import com.softwaremine.dps.data.android.tool.AndroidReminderTool
import com.softwaremine.dps.data.android.tool.AndroidTaskTool
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

/**
 * M5-C: real Android verification of the pre-dispatch [OperationCheckpoint]
 * mechanism added to [AndroidTaskTool] and [AndroidReminderTool].
 *
 * ## Two kinds of coverage in this file
 * The first group runs as ordinary single-invocation `connectedAndroidTest`
 * methods: real [AndroidTaskTool]/[AndroidReminderTool] against a real,
 * `Context`-backed [PersistentRecoveryStore], proving the happy-path
 * checkpoint lifecycle (written, then cleared on confirmed success) and the
 * "leftover checkpoint" recognition path.
 *
 * The second group is real two-invocation process-death coverage, mirroring
 * [SecretaryExecutionRecoveryInstrumentedTest]'s own established pattern
 * exactly:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.CheckpointRecoveryInstrumentedTest#phase1ACreateTaskCheckpointBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.CheckpointRecoveryInstrumentedTest#phase2AVerifyCheckpointSurvivedDeathWithNoDuplicateTaskCreated \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * and the same shape again for the `...B...` (create_reminder) and `...C...`
 * (successful completion must not resurrect) pairs.
 *
 * ## Honesty note: how "interrupted before the real write" is reproduced
 * [AndroidTaskTool.execute]/[AndroidReminderTool.execute] run their
 * checkpoint-write-then-real-write sequence synchronously, in one function
 * call, on one thread — there is no window in which an external `adb`
 * command can land strictly *between* the checkpoint write and the real
 * side effect. The "leftover checkpoint" tests below therefore reproduce
 * that exact on-disk state directly, using the *same* production
 * [PersistentRecoveryStore.saveCheckpoint] API the tools themselves call,
 * with an id genuinely reserved via the real [AndroidTaskStore.nextId]/
 * [ReminderStore.nextId] (which — like the tools' own code — is never
 * called a second time for the same operation, so the id is permanently
 * skipped exactly as it would be after a real interruption). What *is*
 * genuine in the process-death pairs is the process boundary itself: a real
 * `adb shell am force-stop` between phase 1 and phase 2, not an in-process
 * simulation of one.
 */
@RunWith(AndroidJUnit4::class)
class CheckpointRecoveryInstrumentedTest {

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

    /** A [SecretaryOrchestrator] against the real tool layer, with only the model scripted — mirrors [SecretaryExecutionRecoveryInstrumentedTest]'s own factory. */
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

    // -----------------------------------------------------------------
    // Single-invocation lifecycle tests — no process death involved
    // -----------------------------------------------------------------

    @Test
    fun createTaskLeavesNoCheckpointBehindAfterConfirmedSuccess() = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clearCheckpoint()
        val tool = AndroidTaskTool(taskStore, recoveryStore)
        val title = "M5-C lifecycle create_task"

        try {
            assertNull("No checkpoint should exist before any create runs", recoveryStore.loadCheckpoint())

            val result = tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to title)))
            assertTrue("Expected Success, got $result", result is ToolResult.Success)

            assertNull("A confirmed-successful create must leave no checkpoint behind", recoveryStore.loadCheckpoint())
            assertTrue("The task itself must actually exist", taskStore.all().any { it.title == title })
        } finally {
            taskStore.all().filter { it.title == title }.forEach { taskStore.delete(it.id) }
            recoveryStore.clearCheckpoint()
            // AndroidTaskStore.delete() writes via apply(), which is
            // asynchronous — the same flush finding every process-death test
            // in this codebase already relies on. Without this, ActivityManager
            // can force-stop this process (it always does, right after this
            // test method returns) before the delete reaches disk.
            delay(400)
        }
    }

    @Test
    fun createReminderLeavesNoCheckpointBehindAfterConfirmedSuccess() = runBlocking {
        val reminderStore = ReminderStore(context, silentLogger)
        val scheduler = ReminderScheduler(context, silentLogger, AndroidPermissionManager(context, silentLogger))
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clearCheckpoint()
        val tool = AndroidReminderTool(scheduler, reminderStore, recoveryStore)
        val title = "M5-C lifecycle create_reminder"

        var reminderId: Int? = null
        try {
            assertNull("No checkpoint should exist before any create runs", recoveryStore.loadCheckpoint())

            val triggerAt = System.currentTimeMillis() + 60 * 60 * 1000L
            val result = tool.execute(
                ToolCall(ToolId.REMINDER, "create_reminder", mapOf("title" to title, "time" to triggerAt.toString())),
            )
            assertTrue("Expected Success, got $result", result is ToolResult.Success)
            reminderId = (result as ToolResult.Success).data["reminder_id"]!!.toInt()

            assertNull("A confirmed-successful schedule must leave no checkpoint behind", recoveryStore.loadCheckpoint())
        } finally {
            reminderId?.let { id ->
                scheduler.cancel(id)
                reminderStore.remove(id)
            }
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    @Test
    fun anUnclearedCreateTaskCheckpointIsSurfacedOnceThenNeverAutoRetried() = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        val reservedId = taskStore.nextId()
        val title = "M5-C interrupted create_task"
        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_TASK,
                operationId = reservedId,
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
            assertTrue("Expected the notice to name the interrupted task, got: $text", text!!.contains(title))
            assertTrue(
                "The checkpoint must never trigger an automatic create — no task with this title may exist",
                taskStore.all().none { it.title == title },
            )
            assertNull("Surfacing the checkpoint once must clear it durably", recoveryStore.loadCheckpoint())

            val second = secretary.handle("hello")
            val secondText = (second as? ToolOrchestrator.Outcome.Conversational)?.replyText
            assertTrue(
                "A second call must not repeat the checkpoint notice",
                secondText == null || !secondText.contains(title),
            )
        } finally {
            taskStore.all().filter { it.title == title }.forEach { taskStore.delete(it.id) }
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    @Test
    fun anUnclearedCreateReminderCheckpointIsSurfacedOnceThenNeverAutoRetried() = runBlocking {
        val reminderStore = ReminderStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        val reservedId = reminderStore.nextId()
        val title = "M5-C interrupted create_reminder"
        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_REMINDER,
                operationId = reservedId,
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
            assertTrue("Expected the notice to name the interrupted reminder, got: $text", text!!.contains(title))
            assertNull("No reminder record may exist for an uncleared checkpoint's title", reminderStore.find(reservedId))
            assertNull("Surfacing the checkpoint once must clear it durably", recoveryStore.loadCheckpoint())
        } finally {
            reminderStore.remove(reservedId)
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Scenario A — create_task checkpoint survives real process death
    // -----------------------------------------------------------------

    @Test
    fun phase1ACreateTaskCheckpointBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        val reservedId = taskStore.nextId()
        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_TASK,
                operationId = reservedId,
                title = SCENARIO_A_TITLE,
                requestedAtMillis = System.currentTimeMillis(),
            ),
        )

        assertNotNull("Checkpoint must be durable before this process ends", recoveryStore.loadCheckpoint())
        assertTrue(
            "No task must exist yet — this reproduces death before the real save",
            taskStore.all().none { it.title == SCENARIO_A_TITLE },
        )

        // Same async-apply()-flush finding every other process-death test in
        // this codebase already relies on. saveCheckpoint()/clear() already
        // use commit() (M5-C's own window-D fix), but nextId()'s own counter
        // write still uses apply(), so this delay still matters here.
        delay(1500)
    }

    @Test
    fun phase2AVerifyCheckpointSurvivedDeathWithNoDuplicateTaskCreated(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
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
                "A real process death must never result in an automatically created duplicate",
                taskStore.all().none { it.title == SCENARIO_A_TITLE },
            )
            assertNull("The checkpoint must be cleared after being surfaced once", recoveryStore.loadCheckpoint())
        } finally {
            taskStore.all().filter { it.title == SCENARIO_A_TITLE }.forEach { taskStore.delete(it.id) }
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Scenario B — create_reminder checkpoint survives real process death
    // -----------------------------------------------------------------

    @Test
    fun phase1BCreateReminderCheckpointBeforeProcessDeath(): Unit = runBlocking {
        val reminderStore = ReminderStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        val reservedId = reminderStore.nextId()
        recoveryStore.saveCheckpoint(
            OperationCheckpoint(
                operationType = OperationType.CREATE_REMINDER,
                operationId = reservedId,
                title = SCENARIO_B_TITLE,
                requestedAtMillis = System.currentTimeMillis(),
            ),
        )

        assertNotNull("Checkpoint must be durable before this process ends", recoveryStore.loadCheckpoint())
        assertNull("No reminder record must exist yet — this reproduces death before the real schedule", reminderStore.find(reservedId))

        delay(1500)
    }

    @Test
    fun phase2BVerifyCheckpointSurvivedDeathWithNoDuplicateReminderCreated(): Unit = runBlocking {
        val reminderStore = ReminderStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        val survived = recoveryStore.loadCheckpoint()
        assertNotNull("The checkpoint must survive real process death", survived)
        assertEquals(SCENARIO_B_TITLE, survived?.title)
        val reservedId = survived!!.operationId

        try {
            val secretary = scriptedSecretary("not valid json", persistentRecoveryStore = recoveryStore)
            val prompt = secretary.handle("hello")
            assertTrue("Expected the checkpoint notice, got $prompt", prompt is ToolOrchestrator.Outcome.Conversational)
            val text = (prompt as ToolOrchestrator.Outcome.Conversational).replyText
            assertNotNull(text)
            assertTrue(text!!.contains(SCENARIO_B_TITLE))
            assertNull(
                "A real process death must never result in an automatically scheduled duplicate",
                reminderStore.find(reservedId),
            )
            assertNull("The checkpoint must be cleared after being surfaced once", recoveryStore.loadCheckpoint())
        } finally {
            reminderStore.remove(reservedId)
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Scenario C — a confirmed-successful create must not resurrect
    // anything after real process death (Window-D's own required proof)
    // -----------------------------------------------------------------

    @Test
    fun phase1CSuccessfulTaskCreationBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()
        recoveryStore.clearCheckpoint()

        // The real production tool, run to genuine, confirmed completion —
        // not a simulated intermediate state, unlike scenarios A and B.
        val tool = AndroidTaskTool(taskStore, recoveryStore)
        val result = tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to SCENARIO_C_TITLE)))
        assertTrue("Expected Success, got $result", result is ToolResult.Success)

        assertNull("A confirmed success must already have cleared its own checkpoint", recoveryStore.loadCheckpoint())
        assertTrue(taskStore.all().any { it.title == SCENARIO_C_TITLE })

        delay(1500)
    }

    @Test
    fun phase2CVerifyCompletedCreationIsNotResurrectedAsAPendingCheckpoint(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        assertNull("A completed create_task must never resurrect as a checkpoint after death", recoveryStore.loadCheckpoint())
        assertNull("It must also never resurrect as an M5-B execution-recovery prompt", recoveryStore.load())

        try {
            val secretary = scriptedSecretary("not valid json", persistentRecoveryStore = recoveryStore)
            val greeting = secretary.handle("hello")
            assertTrue("Expected an ordinary conversational reply, got $greeting", greeting is ToolOrchestrator.Outcome.Conversational)
            val text = (greeting as ToolOrchestrator.Outcome.Conversational).replyText
            assertTrue(
                "A clean restart with nothing pending must never surface a checkpoint/recovery notice",
                text == null || !text.contains(SCENARIO_C_TITLE),
            )
            assertEquals(
                "The task created in phase1 must exist exactly once — no duplicate from the restart",
                1,
                taskStore.all().count { it.title == SCENARIO_C_TITLE },
            )
        } finally {
            taskStore.all().filter { it.title == SCENARIO_C_TITLE }.forEach { taskStore.delete(it.id) }
            recoveryStore.clearCheckpoint()
            delay(400)
        }
    }

    private companion object {
        const val SCENARIO_A_TITLE = "M5-C scenario A create_task"
        const val SCENARIO_B_TITLE = "M5-C scenario B create_reminder"
        const val SCENARIO_C_TITLE = "M5-C scenario C completed create_task"
    }
}
