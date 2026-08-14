package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.intent.PendingPermissionAction
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of the end-to-end tool calling pipeline (Day 05 Phase D).
 *
 * ## Why a scripted model rather than a real one
 * The orchestrator's job is sequencing, and every decision it makes is a
 * consequence of what the model said. Scripting that output is the only way to
 * test the branches that matter — a truncated reply, a refusal, an intent with
 * a missing field — deliberately and repeatably. A real 1.5B model produces
 * those cases eventually and never on demand.
 *
 * The tool layer underneath is **real**: the actual [DefaultToolRegistry] and
 * [DefaultToolExecutor], so permission gating and failure containment are
 * exercised as they ship rather than simulated.
 *
 * ## The property under test throughout
 * Every path ends in an [ToolOrchestrator.Outcome], never in an exception. An
 * exception escaping here reaches the user as a crash instead of as something
 * the assistant can explain.
 */
class ToolOrchestratorTest {

    // -----------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val immediateDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val inference: CoroutineDispatcher = Dispatchers.Unconfined
    }

    /** An engine that replays a script, one reply per classification pass. */
    private class ScriptedEngine(vararg replies: DpsResult<String>) : AiEngine {
        private val replies = replies.toList()
        val prompts = mutableListOf<String>()
        val configs = mutableListOf<ModelConfig>()
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
            prompts += request.prompt
            configs += request.config
            val reply = replies.getOrElse(index) { replies.last() }
            index++

            return when (reply) {
                is DpsResult.Success -> DpsResult.Success(
                    AiCompletion(
                        text = reply.value,
                        finishReason = FinishReason.END_OF_TURN,
                        usage = TokenUsage(promptTokens = 100, completionTokens = 20),
                        durationMillis = 1_000,
                    ),
                )

                is DpsResult.Failure -> reply
            }
        }

        override suspend fun tokenCount(text: String): DpsResult<Int> =
            DpsResult.Success(text.length / 4)

        override suspend fun shutdown() = Unit
    }

    private class FakePermissions(
        private val states: Map<DpsPermission, PermissionState> = emptyMap(),
        private val default: PermissionState = PermissionState.GRANTED,
    ) : PermissionManager {
        override fun state(permission: DpsPermission) = states[permission] ?: default
        override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
        override fun missing(permissions: Set<DpsPermission>) =
            permissions.filterNot { state(it).isUsable }.toSet()

        override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
    }

    /** Records what it was asked to do, so the call itself can be asserted on. */
    private class RecordingTool(
        override val id: ToolId,
        override val operations: Set<String>,
        override val requiredPermissions: Set<DpsPermission> = emptySet(),
        override val minApiLevel: Int? = null,
        private val behaviour: suspend (ToolCall) -> ToolResult = {
            ToolResult.Success("Done.")
        },
    ) : AndroidTool {
        val calls = mutableListOf<ToolCall>()

        override suspend fun execute(call: ToolCall): ToolResult {
            calls += call
            return behaviour(call)
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /** Thursday 6 August 2026, 14:30 local. */
    private val fixedNow = LocalDateTime.of(2026, 8, 6, 14, 30)

    private var clockMillis = 1_000_000L

    private fun orchestrator(
        engine: AiEngine,
        tools: List<AndroidTool>,
        permissions: PermissionManager = FakePermissions(),
    ): ToolOrchestrator {
        val registry = DefaultToolRegistry(silentLogger).apply { tools.forEach(::register) }

        return ToolOrchestrator(
            engine = engine,
            executor = DefaultToolExecutor(
                registry = registry,
                permissionManager = permissions,
                dispatchers = immediateDispatchers,
                logger = silentLogger,
                apiLevel = 35,
            ),
            registry = registry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(zone = zone, now = { fixedNow }),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
            now = { clockMillis },
        )
    }

    private fun reminderTool(
        behaviour: suspend (ToolCall) -> ToolResult = { ToolResult.Success("Reminder set.") },
    ) = RecordingTool(
        id = ToolId.REMINDER,
        operations = setOf("create_reminder"),
        behaviour = behaviour,
    )

    /**
     * A title-only intent (Day 06) — used in place of REMINDER wherever a
     * test's actual point is "a complete request runs" rather than date/time
     * itself (Day 08-E). REMINDER can no longer be completed by
     * [ToolOrchestrator] alone: `date`/`time` are resolved by
     * [com.softwaremine.dps.ai.memory.TemporalPhraseResolver], which only
     * runs inside [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]
     * — see [IntentJsonParserTest]'s "date and time supplied directly by the
     * model are always ignored" for the parser-level half of this.
     */
    private fun taskTool(
        behaviour: suspend (ToolCall) -> ToolResult = { ToolResult.Success("Task added.") },
    ) = RecordingTool(
        id = ToolId.TASK,
        operations = setOf("create_task"),
        behaviour = behaviour,
    )

    /** A two-field intent (PERSON+MESSAGE) — see [taskTool]'s doc for why REMINDER/TIME no longer fits here. */
    private fun whatsAppTool(
        behaviour: suspend (ToolCall) -> ToolResult = { ToolResult.Success("Message ready.") },
    ) = RecordingTool(
        id = ToolId.WHATSAPP,
        operations = setOf("prepare_message"),
        behaviour = behaviour,
    )

    // -----------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------

    @Test
    fun `a complete request runs the tool and reports what happened`() = runTest {
        val tool = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"task","parameters":{"title":"call the bank"}}""",
            ),
        )

        val outcome = orchestrator(engine, listOf(tool)).handle("add a task to call the bank")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals("Task added.", (outcome as ToolOrchestrator.Outcome.Handled).reply)
        assertEquals(1, tool.calls.size)
        assertEquals("create_task", tool.calls.single().operation)
        assertEquals("call the bank", tool.calls.single().arguments["title"])
    }

    @Test
    fun `ordinary conversation runs no tool at all`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}"""))

        val outcome = orchestrator(engine, listOf(tool)).handle("what can you do?")

        assertTrue("Expected Conversational, got $outcome", outcome is ToolOrchestrator.Outcome.Conversational)
        assertTrue("A conversation must not touch a tool", tool.calls.isEmpty())
    }

    /**
     * The direction every fallback runs in.
     *
     * Answering conversationally when an action was wanted merely disappoints.
     * Acting when only conversation was wanted can create an event the user
     * never asked for.
     */
    @Test
    fun `unusable model output falls back to conversation and never to an action`() = runTest {
        listOf(
            "I'd be happy to help with that!",
            """{"intent":"reminder","parameters":{"title":"call the""",
            """{"intent":"order_pizza","parameters":{"person":"Ali"}}""",
            "",
        ).forEach { badOutput ->
            val tool = reminderTool()
            val outcome = orchestrator(ScriptedEngine(DpsResult.Success(badOutput)), listOf(tool))
                .handle("remind me to call the bank at 4")

            assertTrue(
                "Output <$badOutput> should be conversational, got $outcome",
                outcome is ToolOrchestrator.Outcome.Conversational,
            )
            assertTrue("Output <$badOutput> reached a tool", tool.calls.isEmpty())
        }
    }

    @Test
    fun `a failed generation is conversation, not a crash`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Failure(DpsError.Runtime.GenerationFailed("context overflow")),
        )

        val outcome = orchestrator(engine, listOf(tool)).handle("remind me at 4")

        assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
        assertTrue(tool.calls.isEmpty())
    }

    @Test
    fun `an intent whose tool is not registered does not crash`() = runTest {
        // The notification tool is absent from the registry entirely. A
        // title alone satisfies NOTIFICATION's completeness check, so this
        // reaches execution rather than stalling on a Clarify first.
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"notification","parameters":{"title":"standup"}}""",
            ),
        )

        val outcome = orchestrator(engine, listOf(reminderTool())).handle("remind everyone about standup")

        assertTrue("Expected Conversational, got $outcome", outcome is ToolOrchestrator.Outcome.Conversational)
    }

    // -----------------------------------------------------------------
    // Clarification
    // -----------------------------------------------------------------

    @Test
    fun `an incomplete request asks rather than guesses`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"the meeting"}}"""),
        )
        val orchestrator = orchestrator(engine, listOf(tool))

        val outcome = orchestrator.handle("remind me about the meeting")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertEquals(
            "When should I remind you?",
            (outcome as ToolOrchestrator.Outcome.Clarify).question,
        )
        assertTrue("Nothing may run before the answer", tool.calls.isEmpty())
        assertEquals("When should I remind you?", orchestrator.pendingQuestion())
    }

    @Test
    fun `answering the question completes the original request`() = runTest {
        // REMINDER can no longer be completed by ToolOrchestrator alone
        // (see taskTool's doc), so this uses WHATSAPP_MESSAGE's two
        // required fields (PERSON, MESSAGE) to exercise the same
        // one-field-known-first-one-supplied-by-answer shape.
        val tool = whatsAppTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"person":"Sara"}}"""),
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"message":"running late"}}"""),
        )
        val orchestrator = orchestrator(engine, listOf(tool))

        orchestrator.handle("message Sara")
        val outcome = orchestrator.handle("running late")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        // Sara survived the follow-up: the user answered a question rather
        // than repeating themselves.
        assertEquals("Sara", tool.calls.single().arguments["contact"])
        assertEquals("running late", tool.calls.single().arguments["message"])
        assertNull("The question should be cleared once answered", orchestrator.pendingQuestion())
    }

    @Test
    fun `the follow-up question is given to the model so a bare answer makes sense`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"person":"Sara"}}"""),
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"message":"running late"}}"""),
        )
        val orchestrator = orchestrator(engine, listOf(whatsAppTool()))

        orchestrator.handle("message Sara")
        orchestrator.handle("running late")

        assertTrue(
            "Second prompt lacked the pending question",
            engine.prompts[1].contains("What would you like the message to say?"),
        )
    }

    /**
     * A bare answer often classifies as conversation — "running late" on its
     * own is not a request to do anything. Discarding the pending intent at
     * that point would lose the request the user is in the middle of making.
     */
    @Test
    fun `an answer the model reads as conversation still fills the pending request`() = runTest {
        val tool = whatsAppTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"person":"Sara"}}"""),
            DpsResult.Success("""{"intent":"conversation","parameters":{"message":"running late"}}"""),
        )
        val orchestrator = orchestrator(engine, listOf(tool))

        orchestrator.handle("message Sara")
        val outcome = orchestrator.handle("running late")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals("Sara", tool.calls.single().arguments["contact"])
    }

    @Test
    fun `reset discards a question in flight`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"the meeting"}}"""),
        )
        val orchestrator = orchestrator(engine, listOf(reminderTool()))

        orchestrator.handle("remind me about the meeting")
        orchestrator.reset()

        assertNull(orchestrator.pendingQuestion())
    }

    // -----------------------------------------------------------------
    // Permission recovery
    // -----------------------------------------------------------------

    @Test
    fun `a missing permission holds the action and explains itself`() = runTest {
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val orchestrator = orchestrator(
            engine,
            listOf(tool),
            FakePermissions(mapOf(DpsPermission.READ_CONTACTS to PermissionState.DENIED)),
        )

        val outcome = orchestrator.handle("what's Abdul's number?")

        assertTrue("Expected NeedsPermission, got $outcome", outcome is ToolOrchestrator.Outcome.NeedsPermission)
        assertTrue(
            (outcome as ToolOrchestrator.Outcome.NeedsPermission).reply.contains("your contacts"),
        )
        assertTrue("Nothing may run without permission", tool.calls.isEmpty())
        assertTrue("The action must be held for resumption", orchestrator.hasPendingPermissionAction())
    }

    @Test
    fun `granting the permission resumes the action without a second inference pass`() = runTest {
        var granted = false
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
            behaviour = { ToolResult.Success("Abdul — +923001234567") },
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val permissions = object : PermissionManager {
            override fun state(permission: DpsPermission) =
                if (granted) PermissionState.GRANTED else PermissionState.DENIED

            override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
            override fun missing(permissions: Set<DpsPermission>) =
                permissions.filterNot { state(it).isUsable }.toSet()

            override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
        }
        val orchestrator = orchestrator(engine, listOf(tool), permissions)

        orchestrator.handle("what's Abdul's number?")
        granted = true
        val outcome = orchestrator.resumeAfterPermissionGrant()

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals("Abdul — +923001234567", (outcome as ToolOrchestrator.Outcome.Handled).reply)
        assertEquals("The user should not repeat themselves", 1, engine.prompts.size)
        assertEquals("Abdul", tool.calls.single().arguments["name"])
        assertTrue(!orchestrator.hasPendingPermissionAction())
    }

    @Test
    fun `a permission still refused on resume asks again rather than failing silently`() = runTest {
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val orchestrator = orchestrator(
            engine,
            listOf(tool),
            FakePermissions(mapOf(DpsPermission.READ_CONTACTS to PermissionState.DENIED)),
        )

        orchestrator.handle("what's Abdul's number?")
        val outcome = orchestrator.resumeAfterPermissionGrant()

        assertTrue(outcome is ToolOrchestrator.Outcome.NeedsPermission)
    }

    /**
     * A permission granted ten minutes after the request no longer implies the
     * user still wants the action taken — they may have gone to Settings for an
     * entirely unrelated reason.
     */
    @Test
    fun `a stale held action is discarded rather than performed`() = runTest {
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val orchestrator = orchestrator(
            engine,
            listOf(tool),
            FakePermissions(mapOf(DpsPermission.READ_CONTACTS to PermissionState.DENIED)),
        )

        orchestrator.handle("what's Abdul's number?")
        clockMillis += PendingPermissionAction.FRESHNESS_WINDOW_MILLIS + 1

        assertNull(orchestrator.resumeAfterPermissionGrant())
        assertTrue(tool.calls.isEmpty())
    }

    @Test
    fun `resuming with nothing held does nothing`() = runTest {
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}"""))

        assertNull(orchestrator(engine, listOf(reminderTool())).resumeAfterPermissionGrant())
    }

    @Test
    fun `reset discards a held action`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val orchestrator = orchestrator(
            engine,
            listOf(
                RecordingTool(
                    id = ToolId.CONTACTS,
                    operations = setOf("find_contact"),
                    requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
                ),
            ),
            FakePermissions(mapOf(DpsPermission.READ_CONTACTS to PermissionState.DENIED)),
        )

        orchestrator.handle("what's Abdul's number?")
        orchestrator.reset()

        assertTrue(!orchestrator.hasPendingPermissionAction())
        assertNull(orchestrator.resumeAfterPermissionGrant())
    }

    // -----------------------------------------------------------------
    // Failure recovery
    // -----------------------------------------------------------------

    @Test
    fun `a tool that throws produces an apology, not a crash`() = runTest {
        val tool = taskTool { error("storage layer exploded") }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"task","parameters":{"title":"x"}}"""),
        )

        val outcome = orchestrator(engine, listOf(tool)).handle("add a task")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        val handled = outcome as ToolOrchestrator.Outcome.Handled
        assertTrue(handled.result is ToolResult.Error)
        assertTrue("The exception must not reach the user", !handled.reply.contains("exploded"))
        assertTrue(handled.reply.isNotBlank())
    }

    @Test
    fun `a tool that fails passes the reason through unchanged`() = runTest {
        val tool = taskTool {
            ToolResult.Failure("I couldn't reach storage.", retryable = true)
        }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"task","parameters":{"title":"x"}}"""),
        )

        val outcome = orchestrator(engine, listOf(tool)).handle("add a task")
            as ToolOrchestrator.Outcome.Handled

        assertTrue(outcome.reply.startsWith("I couldn't reach storage."))
        assertTrue(outcome.reply.contains("try again"))
    }

    // -----------------------------------------------------------------
    // Cost discipline
    // -----------------------------------------------------------------

    /**
     * One pass, not two.
     *
     * Prompt ingestion is 76% of inference cost on this hardware. A second pass
     * to phrase the reply would roughly double the latency of every action —
     * and would let the model narrate an outcome it did not observe.
     */
    @Test
    fun `exactly one inference pass is spent per user message`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"task","parameters":{"title":"call the bank"}}""",
            ),
        )

        orchestrator(engine, listOf(taskTool())).handle("add a task to call the bank")

        assertEquals(1, engine.prompts.size)
    }

    @Test
    fun `classification is deterministic and capped`() = runTest {
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}"""))

        orchestrator(engine, listOf(reminderTool())).handle("hello")

        val config = engine.configs.single()
        // Classification wants exactly one answer, not a creative one.
        assertEquals(0f, config.temperature, 0.0f)
        assertEquals(1, config.topK)
        assertTrue(
            "Output must be capped: an uncapped model keeps explaining itself",
            config.maxOutputTokens <= 256,
        )
    }

    /**
     * The inverse of what this test asserted before Day 08-E. A prompt that
     * once needed the current date injected now needs none at all — see
     * [com.softwaremine.dps.ai.intent.IntentPromptBuilder]'s class doc for
     * why, and [com.softwaremine.dps.ai.intent.IntentPromptBuilderTest] for
     * the unit-level coverage of the builder in isolation. This checks the
     * same invariant holds for the exact prompt this orchestrator actually
     * sends to the engine.
     */
    @Test
    fun `the classification prompt sent to the engine never contains Now`() = runTest {
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}"""))

        orchestrator(engine, listOf(reminderTool())).handle("remind me tomorrow")

        assertNotNull(engine.prompts.singleOrNull())
        assertTrue(!engine.prompts.single().contains("Now:"))
    }

    /** Nothing that reaches the orchestrator may escape as an exception. */
    @Test
    fun `no user message ever throws`() = runTest {
        val messages = listOf(
            "", "   ", "}{", "remind me", "send Abdul a message",
            " ", "a".repeat(4_000), "🦅",
        )

        messages.forEach { message ->
            val outcome = orchestrator(
                ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}""")),
                listOf(reminderTool()),
            ).handle(message)

            assertNotNull("handle(<${message.take(20)}>) returned nothing", outcome)
        }
    }
}
