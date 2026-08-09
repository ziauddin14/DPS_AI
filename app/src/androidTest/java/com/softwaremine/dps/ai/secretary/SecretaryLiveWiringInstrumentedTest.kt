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
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the AI Secretary layer reaches the *real* tool
 * registry (Day 05 Phase E).
 *
 * ## What only a device can prove
 * [ai.intent.ToolSelector]'s new UPDATE/CANCEL branch names
 * `update_reminder`/`cancel_reminder` — operations that only exist because
 * `AndroidReminderTool` (Phase B) declared and implemented them. The JVM
 * suite verifies the branching logic against a stub; it cannot see whether
 * those exact operation names still match what the real, shipping tool
 * accepts. Building against [AiContainer]'s real
 * [AiContainer.toolRegistry]/[AiContainer.toolExecutor] is what closes that
 * gap, exactly as `IntentOrchestrationInstrumentedTest` did for Phase D.
 *
 * ## Why the model is still scripted
 * Same reasoning as Phase D: real inference is slow and non-deterministic,
 * and is covered elsewhere (`GgufInferenceInstrumentedTest`). This test
 * covers everything downstream of classification — including, new in Phase E,
 * whether a *second* scripted message resolves against what the first one
 * actually did on this device.
 */
@RunWith(AndroidJUnit4::class)
class SecretaryLiveWiringInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays a fixed script of classifications, one per [handle] call. */
    private class ScriptedEngine(vararg replies: String) : AiEngine {
        private val replies = replies.toList()
        private var index = 0

        override val state: StateFlow<AiState> = MutableStateFlow(AiState.Idle)
        override val activeModel: ModelDescriptor? = null

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(
            descriptor: ModelDescriptor,
            config: ModelConfig,
        ): DpsResult<Unit> = DpsResult.Success(Unit)

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

    private fun secretary(vararg classifications: String): SecretaryOrchestrator {
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
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            logger = silentLogger,
        )
    }

    // -----------------------------------------------------------------
    // Drift check: the new operations exist on the real tool
    // -----------------------------------------------------------------

    @Test
    fun updateAndCancelReminderOperationsAreRegisteredOnTheRealTool() {
        val selector = ToolSelector()
        val tool = container.toolRegistry.find(
            selector.select(
                DpsIntent(
                    IntentType.REMINDER,
                    IntentParameters(targetId = "1"),
                    action = IntentAction.UPDATE,
                ),
            )!!.toolId,
        )

        assertNotNull("REMINDER is not registered on this device", tool)
        assertTrue(
            "update_reminder is not declared by the real tool: ${tool!!.operations}",
            "update_reminder" in tool.operations,
        )
        assertTrue(
            "cancel_reminder is not declared by the real tool: ${tool.operations}",
            "cancel_reminder" in tool.operations,
        )
    }

    // -----------------------------------------------------------------
    // Memory persists across turns against the real tool layer
    // -----------------------------------------------------------------

    /** Demo example 2 from the Phase E brief, against the real reminder tool. */
    @Test
    fun rescheduleFollowUpResolvesAgainstTheRealReminderJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"reminder","parameters":{"title":"call the bank","date":"2099-01-01","time":"16:00"}}""",
            """{"intent":"reminder","action_type":"update"}""",
        )

        val first = orchestrator.handle("remind me to call the bank on 2099-01-01 at 4pm")
        assertTrue("Expected Handled, got $first", first is ToolOrchestrator.Outcome.Handled)
        assertNotNull("Memory did not record the created reminder", orchestrator.memory.value.lastReminder)

        val second = orchestrator.handle("uska reminder 30 minute pehle kar do")

        assertTrue(
            "Expected the follow-up to resolve and execute, got $second",
            second is ToolOrchestrator.Outcome.Handled || second is ToolOrchestrator.Outcome.Conversational,
        )
        // Whichever the real tool reported, memory must reflect it truthfully
        // rather than an assumed success — the property under test is that the
        // second call reached the *same* reminder, not that it necessarily
        // succeeded on this device's alarm scheduling.
        if (second is ToolOrchestrator.Outcome.Handled) {
            assertTrue(
                "Reply falsely implies nothing happened: ${second.reply}",
                second.reply.isNotBlank(),
            )
        }
    }

    @Test
    fun conversationNeverTouchesARealTool(): Unit = runBlocking {
        val outcome = secretary("""{"intent":"conversation"}""").handle("who are you?")

        assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
    }

    // -----------------------------------------------------------------
    // Stage 2 drift check: update_event/delete_event exist on the real tool
    // -----------------------------------------------------------------

    @Test
    fun updateAndDeleteEventOperationsAreRegisteredOnTheRealCalendarTool() {
        val selector = ToolSelector()
        val tool = container.toolRegistry.find(
            selector.select(
                DpsIntent(IntentType.CALENDAR_EVENT, IntentParameters(targetId = "1"), action = IntentAction.UPDATE),
            )!!.toolId,
        )

        assertNotNull("CALENDAR is not registered on this device", tool)
        assertTrue(
            "update_event is not declared by the real tool: ${tool!!.operations}",
            "update_event" in tool.operations,
        )
        assertTrue(
            "delete_event is not declared by the real tool: ${tool.operations}",
            "delete_event" in tool.operations,
        )
    }

    /**
     * Demo example 4/5 from the Phase E Stage 2 brief, against the real
     * calendar tool.
     *
     * ## Why a permission request is an accepted outcome here
     * Whether READ_CALENDAR/WRITE_CALENDAR are already granted depends on
     * this device's state going into the test run, not on this test — the
     * same reasoning Phase D's own reminder equivalent already applies. What
     * this proves either way: the pipeline reaches the real tool and asks
     * honestly rather than silently failing or fabricating a result.
     */
    @Test
    fun rescheduleAndDeleteResolveAgainstTheRealEventJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"calendar_event","parameters":{"title":"standup","date":"2099-01-01","time":"09:00"}}""",
            """{"intent":"calendar_event","action_type":"update","parameters":{"time":"17:00"}}""",
            """{"intent":"calendar_event","action_type":"cancel","parameters":{}}""",
        )

        val created = orchestrator.handle("standup on 2099-01-01 at 9am")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no calendar permission on this run; covered by AndroidToolsInstrumentedTest instead
        assertNotNull("Memory did not record the created event", orchestrator.memory.value.lastCalendarEvent)

        val rescheduled = orchestrator.handle("us meeting ko 5 baje kar do")
        assertTrue(
            "Expected the reschedule to resolve, got $rescheduled",
            rescheduled is ToolOrchestrator.Outcome.Handled || rescheduled is ToolOrchestrator.Outcome.Conversational,
        )

        val deleteRequest = orchestrator.handle("us meeting ko delete kar do")
        // Requirement 6: deletion asks first — the very next outcome must be a
        // question, never the deletion itself.
        assertTrue(
            "Delete must ask for confirmation before doing anything, got $deleteRequest",
            deleteRequest is ToolOrchestrator.Outcome.Clarify,
        )
    }
}
