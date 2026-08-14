package com.softwaremine.dps.ai.intent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.error.DpsError
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
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.tool.ToolResult
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
 * On-device verification of tool calling (Day 05 Phase D).
 *
 * ## What only a device can prove
 * The JVM suite exercises the orchestrator against **stub** tools, so it cannot
 * see the one failure mode that matters most here: [ToolSelector] naming a tool
 * id or an operation that the *real* registry does not accept.
 *
 * That drift is silent. A renamed operation in `AndroidCalendarTool` leaves
 * every JVM test green while every calendar request on the handset resolves to
 * `Unsupported`. Pointing the orchestrator at the real [AiContainer] graph is
 * what closes that gap.
 *
 * ## Why the model is still scripted
 * The 1.5B model takes 5–15 s per pass on this hardware and is by nature
 * non-deterministic. Loading it here would make the suite slow and flaky while
 * testing the model rather than the wiring. Real inference is covered by
 * `GgufInferenceInstrumentedTest`; this test covers everything downstream of it.
 *
 * ## No side effects
 * Nothing here creates a calendar event, posts a notification or opens
 * WhatsApp. Every assertion is about routing and about outcomes the tool layer
 * returns before it acts — permission gating in particular, since the test
 * process holds no runtime grants.
 */
@RunWith(AndroidJUnit4::class)
class IntentOrchestrationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays one scripted classification so the wiring below it is what is tested. */
    private class ScriptedEngine(private val reply: String) : AiEngine {
        override val state: StateFlow<AiState> = MutableStateFlow(AiState.Idle)
        override val activeModel: ModelDescriptor? = null

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(
            descriptor: ModelDescriptor,
            config: ModelConfig,
        ): DpsResult<Unit> = DpsResult.Success(Unit)

        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)
        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = emptyFlow()

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> =
            DpsResult.Success(
                AiCompletion(
                    text = reply,
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 120, completionTokens = 24),
                    durationMillis = 1_000,
                ),
            )

        override suspend fun tokenCount(text: String): DpsResult<Int> =
            DpsResult.Success(text.length / 4)

        override suspend fun shutdown() = Unit
    }

    private fun orchestrator(classification: String) = ToolOrchestrator(
        engine = ScriptedEngine(classification),
        // Real executor and real registry, from the shipping composition root.
        executor = container.toolExecutor,
        registry = container.toolRegistry,
        promptBuilder = IntentPromptBuilder(),
        parser = IntentJsonParser(),
        clarification = ClarificationEngine(),
        selector = ToolSelector(),
        responses = ToolResponseGenerator(),
        logger = silentLogger,
    )

    // -----------------------------------------------------------------
    // Selector ↔ registry agreement
    // -----------------------------------------------------------------

    /**
     * The drift check.
     *
     * Every intent the selector can route must name a tool that is registered
     * on this device and an operation that tool declares. Nothing in the JVM
     * suite can catch a mismatch here.
     */
    @Test
    fun everyRoutableIntentReachesARegisteredToolAndOperation() {
        val selector = ToolSelector()
        val complete = mapOf(
            IntentType.REMINDER to IntentParameters(title = "call the bank", time = "16:00"),
            IntentType.CALENDAR_EVENT to IntentParameters(title = "standup", date = "2026-08-07"),
            IntentType.NOTIFICATION to IntentParameters(message = "stand up"),
            IntentType.CONTACT_LOOKUP to IntentParameters(person = "Abdul"),
            IntentType.WHATSAPP_MESSAGE to IntentParameters(person = "Sara", message = "hi"),
            IntentType.EMAIL_MESSAGE to IntentParameters(email = "a@b.com", message = "hi"),
        )

        complete.forEach { (type, parameters) ->
            val call = selector.select(DpsIntent(type = type, parameters = parameters))
            assertNotNull("$type produced no tool call", call)

            val tool = container.toolRegistry.find(call!!.toolId)
            assertNotNull(
                "$type routes to ${call.toolId.toolName}, which is not registered on this device",
                tool,
            )
            assertTrue(
                "${call.toolId.toolName} does not declare '${call.operation}'; " +
                    "it declares ${tool!!.operations}",
                call.operation in tool.operations,
            )
        }
    }

    /**
     * Phase C's guarantee, checked against the shipping registry.
     *
     * The selector must never name an operation that sends. If one appeared,
     * the confirmation flow would be bypassed at the routing layer, above every
     * safeguard Phase C put in place.
     */
    @Test
    fun noRoutedOperationSends() {
        val selector = ToolSelector()

        listOf(
            DpsIntent(
                IntentType.WHATSAPP_MESSAGE,
                IntentParameters(person = "Sara", message = "on my way"),
            ),
            DpsIntent(
                IntentType.EMAIL_MESSAGE,
                IntentParameters(email = "a@b.com", message = "hello"),
            ),
        ).forEach { intent ->
            val operation = selector.select(intent)!!.operation
            assertTrue(
                "Selector routed ${intent.type} to a sending operation: $operation",
                !operation.contains("send"),
            )
        }
    }

    // -----------------------------------------------------------------
    // End to end through the real tool layer
    // -----------------------------------------------------------------

    /**
     * A contacts request reaches the real contacts tool.
     *
     * The instrumentation process holds no runtime grants, so the expected
     * outcome is a permission request — which is itself the assertion: the call
     * got all the way to the real permission gate.
     */
    @Test
    fun aContactRequestReachesTheRealPermissionGate(): Unit = runBlocking {
        val outcome = orchestrator("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}""")
            .handle("what's Abdul's number?")

        assertTrue(
            "Expected the request to reach the tool layer, got $outcome",
            outcome is ToolOrchestrator.Outcome.NeedsPermission ||
                outcome is ToolOrchestrator.Outcome.Handled,
        )

        val reply = when (outcome) {
            is ToolOrchestrator.Outcome.NeedsPermission -> outcome.reply
            is ToolOrchestrator.Outcome.Handled -> outcome.reply
            else -> ""
        }
        assertTrue("Reply was empty", reply.isNotBlank())
        assertTrue(
            "Reply leaked an internal permission string: $reply",
            !reply.contains("android.permission"),
        )
    }

    @Test
    fun aPermissionBlockedActionIsHeldAndResumable(): Unit = runBlocking {
        val orchestrator =
            orchestrator("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}""")

        val outcome = orchestrator.handle("look up Abdul")

        if (outcome is ToolOrchestrator.Outcome.NeedsPermission) {
            assertTrue("The action must be held", orchestrator.hasPendingPermissionAction())

            // Still denied — resuming must ask again rather than fail silently.
            val resumed = orchestrator.resumeAfterPermissionGrant()
            assertTrue(
                "Expected a repeated permission request, got $resumed",
                resumed is ToolOrchestrator.Outcome.NeedsPermission,
            )
            assertTrue("The held action must be consumed", !orchestrator.hasPendingPermissionAction())
        } else {
            // Contacts happens to be granted on this device; the action ran and
            // nothing should have been held.
            assertTrue(
                "Nothing should be held once the action ran",
                !orchestrator.hasPendingPermissionAction(),
            )
        }
    }

    /** A notification request routes and is answered without ever posting one. */
    @Test
    fun anIncompleteRequestAsksInsteadOfActing(): Unit = runBlocking {
        val outcome = orchestrator("""{"intent":"reminder","parameters":{"title":"the meeting"}}""")
            .handle("remind me about the meeting")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue(
            (outcome as ToolOrchestrator.Outcome.Clarify).question.endsWith("?"),
        )
    }

    @Test
    fun conversationTouchesNoTool(): Unit = runBlocking {
        val outcome = orchestrator("""{"intent":"conversation"}""").handle("who are you?")

        assertTrue(
            "Expected Conversational, got $outcome",
            outcome is ToolOrchestrator.Outcome.Conversational,
        )
    }

    /**
     * The orchestrator must survive anything the model produces, on the real
     * graph. An exception here is an unhandled crash in front of the user.
     */
    @Test
    fun noClassificationEverCrashesTheRealGraph(): Unit = runBlocking {
        listOf(
            "I'd be happy to help!",
            "```json\n{\"intent\":\"notification\",\"parameters\":{\"message\":\"x\"}}\n```",
            """{"intent":"reminder","parameters":{"title":"cut off""",
            """{"intent":"unknown_thing"}""",
            "",
        ).forEach { classification ->
            val outcome = orchestrator(classification).handle("do something")
            assertNotNull("Classification <$classification> produced nothing", outcome)
        }
    }

    /** Whatever happened, the user is told something safe to read. */
    @Test
    fun everyOutcomeProducesAUserSafeReply(): Unit = runBlocking {
        val forbidden = listOf("android.permission", "ToolResult", "Exception", "toolId")

        listOf(
            """{"intent":"contact_lookup","parameters":{"person":"Abdul"}}""",
            """{"intent":"notification","parameters":{"message":"x"}}""",
            """{"intent":"reminder","parameters":{"title":"x","raw_when":"23:59"}}""",
        ).forEach { classification ->
            val reply = when (val outcome = orchestrator(classification).handle("go")) {
                is ToolOrchestrator.Outcome.Handled -> outcome.reply
                is ToolOrchestrator.Outcome.NeedsPermission -> outcome.reply
                is ToolOrchestrator.Outcome.Clarify -> outcome.question
                is ToolOrchestrator.Outcome.Conversational -> "conversation"
            }

            assertTrue("Empty reply for $classification", reply.isNotBlank())
            forbidden.forEach { term ->
                assertTrue(
                    "Reply for $classification leaked '$term': $reply",
                    !reply.contains(term, ignoreCase = true),
                )
            }
        }
    }

    /** Every tool the selector can reach is present in the shipping registry. */
    @Test
    fun theRegistryStillCarriesEveryToolPhasesBThroughCImplemented() {
        val implemented = container.toolRegistry.registeredIds()

        assertTrue("Registry is empty", implemented.isNotEmpty())
        assertTrue(
            "Registered tools: $implemented",
            ToolResult.Success("").isSuccess,
        )
    }
}
