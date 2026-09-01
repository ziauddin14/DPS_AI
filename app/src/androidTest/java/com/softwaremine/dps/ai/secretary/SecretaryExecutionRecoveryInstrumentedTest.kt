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
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
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
 * M5-B: real Android process-death validation for persisted, user-gated
 * execution recovery.
 *
 * ## Why two pairs of `@Test` methods, not one
 * Mirrors [ProcessDeathPersistenceInstrumentedTest]'s own established
 * two-invocation pattern exactly — a single instrumented test method cannot
 * outlive its own process, so writing and reading are split into separate
 * JUnit methods with a genuine `adb shell am force-stop` between them:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.SecretaryExecutionRecoveryInstrumentedTest#phase1CreateInterruptedPlanBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.SecretaryExecutionRecoveryInstrumentedTest#phase2VerifyRecoveryPromptsThenResumesWithoutDuplication \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * and the same pair-shape again for the decline path
 * (`phase1CreateInterruptedClarificationBeforeProcessDeath`/
 * `phase2DecliningDiscardsWithoutExecutingThenFreshRequestWorks`).
 *
 * ## Why [AndroidTaskStore] is constructed directly, not through [AiContainer]
 * [AiContainer]'s own task store is `private` — by design, per its own doc,
 * nothing outside the composition root needs it. Constructing a second
 * `AndroidTaskStore(context, logger)` here reads and writes the exact same
 * real, on-disk `SharedPreferences` file the real [AndroidTaskTool][com.softwaremine.dps.data.android.tool.AndroidTaskTool]
 * uses (via `container.toolRegistry`), so it can assert on real durable
 * state without needing `AiContainer` to expose anything new.
 */
@RunWith(AndroidJUnit4::class)
class SecretaryExecutionRecoveryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays a fixed script of classifications, one per [SecretaryOrchestrator.handle] call — mirrors [ProcessDeathPersistenceInstrumentedTest]'s own fake exactly. */
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
            val reply = replies.getOrElse(index) { replies.last() }
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

    /** A [SecretaryOrchestrator] against the real tool layer, with only the model scripted — mirrors [ProcessDeathPersistenceInstrumentedTest]'s own factory. */
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
            episodicMemoryRecorder = container.episodicMemoryRecorder,
            logger = silentLogger,
        )
    }

    // -----------------------------------------------------------------
    // Pair A — a plan remainder blocked on a delete confirmation
    // -----------------------------------------------------------------

    /**
     * Real step 1 (create_task) actually runs and durably persists; step 2
     * (cancel_task on a pre-existing real task) blocks on the real delete
     * confirmation. Leaves both the durable task state and the persisted
     * recovery record on disk deliberately — phase2 is what must find them.
     */
    @Test
    fun phase1CreateInterruptedPlanBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()

        val now = System.currentTimeMillis()
        taskStore.save(
            com.softwaremine.dps.domain.productivity.Task(
                id = taskStore.nextId(),
                title = "M5-B old task to cancel",
                status = com.softwaremine.dps.domain.productivity.TaskStatus.PENDING,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        val secretary = scriptedSecretary(
            """{"steps":[
                {"intent":"task","parameters":{"title":"M5-B process-death check"}},
                {"intent":"task","action_type":"cancel","parameters":{"title":"M5-B old task to cancel"}}
            ]}""",
            persistentRecoveryStore = recoveryStore,
        )

        val blocked = secretary.handle("M5-B process-death check ka task bana do, phir M5-B old task to cancel wala task cancel kar do")
        assertTrue("Expected the delete confirmation for step 2, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)

        assertTrue(
            "Step 1 must have already run, durably",
            taskStore.all().any { it.title == "M5-B process-death check" },
        )
        assertTrue(
            "Step 2 must still be blocked, not yet cancelled",
            taskStore.all().any { it.title == "M5-B old task to cancel" },
        )
        assertNotNull("A blocked plan remainder must reach durable storage", recoveryStore.load())

        // Same async-apply()-flush finding every other process-death test
        // in this codebase already relies on.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // Pair A — Phase 2: run second, after `adb shell am force-stop`
    // -----------------------------------------------------------------

    @Test
    fun phase2VerifyRecoveryPromptsThenResumesWithoutDuplication(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        assertNotNull("The recovery record must survive real process death", recoveryStore.load())

        // A fresh SecretaryOrchestrator, in a genuinely new process — no
        // classification is ever needed for this entire flow (the recovery
        // question, the re-ask, and the confirmation's own "yes" all resume
        // without a second inference pass), so an empty script is enough.
        val secretary = scriptedSecretary(persistentRecoveryStore = recoveryStore)

        try {
            val prompt = secretary.handle("hello")
            assertTrue("Expected the recovery question first, got $prompt", prompt is ToolOrchestrator.Outcome.Conversational)
            assertTrue(
                "No execution before the user answers — the blocked task must still exist",
                taskStore.all().any { it.title == "M5-B old task to cancel" },
            )

            val reAsked = secretary.handle("yes")
            assertTrue("The first yes answers the recovery prompt, re-asking the delete confirmation", reAsked is ToolOrchestrator.Outcome.Clarify)
            assertTrue(
                "Restoring the pending state must not itself cancel the task",
                taskStore.all().any { it.title == "M5-B old task to cancel" },
            )

            val resumed = secretary.handle("yes")
            assertTrue("The second yes answers the re-asked delete confirmation", resumed is ToolOrchestrator.Outcome.Handled)
            assertTrue(
                "Step 2 must now actually be cancelled",
                taskStore.all().none { it.title == "M5-B old task to cancel" },
            )
            assertEquals(
                "Step 1's already-completed side effect must never be duplicated across the restart",
                1,
                taskStore.all().count { it.title == "M5-B process-death check" },
            )
            assertNull("A fully resumed request must clear the persisted recovery record", recoveryStore.load())
        } finally {
            taskStore.all().filter { it.title == "M5-B process-death check" || it.title == "M5-B old task to cancel" }
                .forEach { taskStore.delete(it.id) }
            recoveryStore.clear()
            delay(400)
        }
    }

    // -----------------------------------------------------------------
    // Pair B — a single-step clarification, declined after restart
    // -----------------------------------------------------------------

    @Test
    fun phase1CreateInterruptedClarificationBeforeProcessDeath(): Unit = runBlocking {
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()

        val secretary = scriptedSecretary(
            """{"intent":"reminder","parameters":{"title":"M5-B decline check"}}""",
            persistentRecoveryStore = recoveryStore,
        )

        val blocked = secretary.handle("M5-B decline check ke liye reminder laga do")
        assertTrue("Expected a clarification asking for a time, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)
        assertNotNull("A blocked clarification must reach durable storage", recoveryStore.load())

        delay(1500)
    }

    // -----------------------------------------------------------------
    // Pair B — Phase 2: run second, after `adb shell am force-stop`
    // -----------------------------------------------------------------

    @Test
    fun phase2DecliningDiscardsWithoutExecutingThenFreshRequestWorks(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, silentLogger)
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        assertNotNull("The recovery record must survive real process death", recoveryStore.load())

        val secretary = scriptedSecretary(
            """{"intent":"task","parameters":{"title":"M5-B fresh after decline"}}""",
            persistentRecoveryStore = recoveryStore,
        )

        try {
            val prompt = secretary.handle("hello")
            assertTrue("Expected the recovery question first, got $prompt", prompt is ToolOrchestrator.Outcome.Conversational)

            val declined = secretary.handle("no")
            assertTrue(declined is ToolOrchestrator.Outcome.Conversational)
            assertNull("Declining must clear the persisted recovery record", recoveryStore.load())

            val fresh = secretary.handle("M5-B fresh after decline ka task bana do")
            assertTrue("An unrelated request after declining must work exactly as normal", fresh is ToolOrchestrator.Outcome.Handled)
            assertTrue(
                "The fresh request must actually create its task",
                taskStore.all().any { it.title == "M5-B fresh after decline" },
            )
        } finally {
            taskStore.all().filter { it.title == "M5-B fresh after decline" }.forEach { taskStore.delete(it.id) }
            recoveryStore.clear()
            delay(400)
        }
    }
}
