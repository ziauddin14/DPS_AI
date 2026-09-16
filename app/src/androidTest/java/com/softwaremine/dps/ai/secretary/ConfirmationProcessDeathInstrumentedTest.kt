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
import com.softwaremine.dps.data.android.memory.DpsMemoryDatabase
import com.softwaremine.dps.data.android.memory.LongTermMemoryStore
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
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
 * M8: real Android process-death validation for a generalized
 * [RiskLevel.CONFIRM_REQUIRED] confirmation, using [IntentType.FORGET_FACT]
 * — the one M8 actually *added* to the confirm-required set — rather than
 * re-proving [IntentType.TASK]/[IntentType.CALENDAR_EVENT] delete
 * confirmation, which [SecretaryExecutionRecoveryInstrumentedTest]'s own
 * Pair A already covers with genuine process death (M5-B) and remains
 * green, unmodified, under M8.
 *
 * ## Why this is still worth a dedicated file
 * The *persistence mechanism* under test — [PendingConfirmation] →
 * [com.softwaremine.dps.domain.secretary.PersistedPendingState.Confirmation]
 * → recovery — is entirely inherited from M5-B and is not new. What M8
 * actually changed is *which* intents reach that mechanism
 * ([com.softwaremine.dps.domain.secretary.RiskPolicy] in place of the old
 * hardcoded `Set`s) and the new risk/permission-precheck gate ahead of it.
 * This file proves the whole M8 gate — RiskPolicy classification through to
 * confirmation persistence, restart, and exactly-once execution — survives
 * a genuine `adb shell am force-stop`, using the one intent type that only
 * exists in the confirm-required set because of M8.
 *
 * ## Why two `@Test` methods, not one
 * Mirrors [SecretaryExecutionRecoveryInstrumentedTest]'s own established
 * two-invocation pattern exactly:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.ConfirmationProcessDeathInstrumentedTest#phase1AskForgetFactConfirmationBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.ConfirmationProcessDeathInstrumentedTest#phase2RecoverConfirmationAfterProcessDeathAndExecuteExactlyOnce \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * ## Why [DpsMemoryDatabase] is constructed directly, not through [AiContainer]
 * [AiContainer]'s own `longTermMemoryStore` is `private` — by design,
 * nothing outside the composition root needs it. Constructing a second
 * [DpsMemoryDatabase] here reads and writes the exact same real, on-disk
 * Room database the real [com.softwaremine.dps.data.android.tool.AndroidMemoryTool]
 * uses (via `container.toolRegistry`), so this file can assert on real
 * durable state without needing `AiContainer` to expose anything new —
 * mirrors [SecretaryExecutionRecoveryInstrumentedTest]'s own
 * `AndroidTaskStore(context, logger)` precedent exactly.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationProcessDeathInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun memoryStore(): LongTermMemoryStore = LongTermMemoryStore(
        DpsMemoryDatabase.create(context).semanticFactDao(),
        DpsMemoryDatabase.create(context).episodicMemoryDao(),
        silentLogger,
    )

    /** Replays a fixed script of classifications — mirrors [SecretaryExecutionRecoveryInstrumentedTest]'s own fake exactly. */
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
            episodicMemoryRecorder = container.episodicMemoryRecorder,
            executionVerifier = container.executionVerifier,
            automationVerifier = container.automationVerifier,
            permissionManager = container.permissionManager,
            toolRegistry = container.toolRegistry,
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )
    }

    /**
     * Seeds a real fact, asks a real `forget_fact` confirmation against it —
     * step 2 of the master prompt's mandatory scenario — and leaves both the
     * durable fact and the persisted confirmation record on disk
     * deliberately: phase2 is what must find them (step 3).
     */
    @Test
    fun phase1AskForgetFactConfirmationBeforeProcessDeath(): Unit = runBlocking {
        val memory = memoryStore()
        memory.recallFacts(SUBJECT).forEach { memory.forgetFact(it.id) } // clean slate from any prior run
        memory.rememberFact(SUBJECT, FACT_TEXT, sourceUtterance = null)

        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)
        recoveryStore.clear()

        val secretary = scriptedSecretary(
            """{"intent":"forget_fact","parameters":{"title":"$SUBJECT"}}""",
            persistentRecoveryStore = recoveryStore,
        )

        val asked = secretary.handle("$SUBJECT ke baare mein bhool jao")
        assertTrue("Expected the forget_fact confirmation, got $asked", asked is ToolOrchestrator.Outcome.Clarify)

        assertTrue(
            "Nothing may be forgotten before confirmation",
            memory.recallFacts(SUBJECT).isNotEmpty(),
        )
        assertNotNull("A pending confirmation must reach durable storage", recoveryStore.load())

        // Same async-apply()-flush finding every other process-death test in
        // this codebase already relies on.
        delay(1500)
    }

    /**
     * Fresh process. Verifies, in order, every one of the master prompt's
     * mandatory-scenario assertions: the confirmation is recovered (step
     * 6), the action is not auto-executed (step 7), an explicit "yes"
     * executes it (step 8) exactly once (step 9), and no stale confirmation
     * remains afterward (step 10).
     */
    @Test
    fun phase2RecoverConfirmationAfterProcessDeathAndExecuteExactlyOnce(): Unit = runBlocking {
        val memory = memoryStore()
        val recoveryStore = PersistentRecoveryStore.create(context, silentLogger)

        assertNotNull("The pending confirmation must survive real process death", recoveryStore.load())

        // A fresh SecretaryOrchestrator, in a genuinely new process — no
        // classification is ever needed for this entire flow (the recovery
        // question, the re-ask, and the confirmation's own "yes" all resume
        // without a second inference pass), so an empty script is enough.
        val secretary = scriptedSecretary(persistentRecoveryStore = recoveryStore)

        try {
            val prompt = secretary.handle("hello")
            assertTrue("Expected the recovery question first, got $prompt", prompt is ToolOrchestrator.Outcome.Conversational)
            assertTrue(
                "No execution before the user answers — the fact must still be remembered",
                memory.recallFacts(SUBJECT).isNotEmpty(),
            )

            val reAsked = secretary.handle("yes")
            assertTrue("The first yes answers the recovery prompt, re-asking the forget_fact confirmation", reAsked is ToolOrchestrator.Outcome.Clarify)
            assertTrue(
                "Restoring the pending state must not itself forget the fact",
                memory.recallFacts(SUBJECT).isNotEmpty(),
            )

            val resumed = secretary.handle("yes")
            assertTrue("The second yes answers the re-asked confirmation", resumed is ToolOrchestrator.Outcome.Handled)
            assertEquals(
                "The fact must now be forgotten exactly once — no duplicate, nothing left behind",
                0,
                memory.recallFacts(SUBJECT).size,
            )
            assertNull("A fully resumed confirmation must clear the persisted recovery record", recoveryStore.load())
        } finally {
            memory.recallFacts(SUBJECT).forEach { memory.forgetFact(it.id) }
            recoveryStore.clear()
            delay(400)
        }
    }

    private companion object {
        const val SUBJECT = "M8-ProcessDeath-Contact"
        const val FACT_TEXT = "mera developer hai"
    }
}
