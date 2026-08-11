package com.softwaremine.dps.ai.secretary

import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
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
import com.softwaremine.dps.domain.memory.ReminderMemory
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.secretary.SecretaryState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * End-to-end verification of the AI Secretary layer (Day 05 Phase E).
 *
 * ## What this proves that [ToolOrchestrator]'s own tests cannot
 * That a message classified in isolation ("30 minute pehle kar do", with no
 * reminder named) resolves against what a *previous* message in the same
 * conversation actually did — the whole point of Phase E. The tool layer
 * underneath is real (the actual [DefaultToolRegistry]/[DefaultToolExecutor]),
 * exactly as [ToolOrchestrator]'s own suite does it; only the model is
 * scripted, for the reasons documented there.
 */
class SecretaryOrchestratorTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val immediateDispatchers = object : com.softwaremine.dps.core.concurrency.DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val inference: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class ScriptedEngine(vararg replies: DpsResult<String>) : AiEngine {
        private val replies = replies.toList()
        var index = 0

        /** Every prompt this engine was asked to classify, in order (Day 08-B). */
        val capturedPrompts = mutableListOf<String>()

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
            capturedPrompts += request.prompt
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

        override suspend fun tokenCount(text: String): DpsResult<Int> = DpsResult.Success(text.length / 4)
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

    private class RecordingTool(
        override val id: ToolId,
        override val operations: Set<String>,
        override val requiredPermissions: Set<DpsPermission> = emptySet(),
        private val behaviour: suspend (ToolCall) -> ToolResult,
    ) : AndroidTool {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall): ToolResult {
            calls += call
            return behaviour(call)
        }
    }

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    private fun secretary(
        engine: AiEngine,
        tools: List<AndroidTool>,
        permissions: PermissionManager = FakePermissions(),
        now: () -> Long = System::currentTimeMillis,
    ): SecretaryOrchestrator {
        val registry = DefaultToolRegistry(silentLogger).apply { tools.forEach(::register) }
        val executor = DefaultToolExecutor(
            registry = registry,
            permissionManager = permissions,
            dispatchers = immediateDispatchers,
            logger = silentLogger,
            apiLevel = 35,
        )
        val toolOrchestrator = ToolOrchestrator(
            engine = engine,
            executor = executor,
            registry = registry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(zone = zone),
            responses = com.softwaremine.dps.ai.intent.ToolResponseGenerator(),
            logger = silentLogger,
        )

        return SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(zone = zone),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(zone = zone),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(zone = zone),
            logger = silentLogger,
            zone = zone,
            now = now,
        )
    }

    private fun reminderTool(
        behaviour: suspend (ToolCall) -> ToolResult = {
            when (it.operation) {
                "create_reminder" -> ToolResult.Success(
                    "Reminder set.",
                    mapOf("reminder_id" to "1001", "trigger_at" to "1000000", "exact" to "true"),
                )
                "update_reminder" -> ToolResult.Success(
                    "Reminder updated.",
                    mapOf("reminder_id" to (it.arguments["id"] ?: "1001"), "trigger_at" to "998200", "exact" to "true"),
                )
                "cancel_reminder" -> ToolResult.Success(
                    "Reminder cancelled.",
                    mapOf("reminder_id" to (it.arguments["id"] ?: "1001")),
                )
                else -> ToolResult.Unsupported("'${it.operation}' is not implemented.")
            }
        },
    ) = RecordingTool(
        id = ToolId.REMINDER,
        operations = setOf("create_reminder", "update_reminder", "cancel_reminder"),
        behaviour = behaviour,
    )

    // -----------------------------------------------------------------
    // Regression parity with ToolOrchestrator's own single-shot behaviour
    // -----------------------------------------------------------------

    @Test
    fun `a complete new request still runs exactly as it did in Phase D`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"reminder","parameters":{"title":"call the bank","time":"16:00"}}""",
            ),
        )

        val outcome = secretary(engine, listOf(tool)).handle("remind me to call the bank at 4")

        assertTrue(outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals("create_reminder", tool.calls.single().operation)
    }

    @Test
    fun `conversation still touches no tool`() = runTest {
        val tool = reminderTool()
        val outcome = secretary(ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}""")), listOf(tool))
            .handle("what can you do?")

        assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
        assertTrue(tool.calls.isEmpty())
    }

    // -----------------------------------------------------------------
    // The three named demo examples
    // -----------------------------------------------------------------

    @Test
    fun `demo 1 - creating a reminder that mentions a person`() = runTest {
        val reminders = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"reminder","parameters":{"person":"Abdul","title":"call Abdul","date":"2026-08-07","time":"16:00"}}""",
            ),
        )
        val orchestrator = secretary(engine, listOf(reminders))

        val outcome = orchestrator.handle("Kal 4 baje Abdul ko yaad dila dena.")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1001, orchestrator.memory.value.lastReminder?.id)
    }

    @Test
    fun `demo 2 - rescheduling the last reminder from memory alone`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"reminder","parameters":{"title":"call the bank","time":"16:00"}}""",
            ),
            // No reminder named — the follow-up must resolve it from memory.
            DpsResult.Success("""{"intent":"reminder","action_type":"update"}"""),
        )
        val orchestrator = secretary(engine, listOf(tool))

        orchestrator.handle("remind me to call the bank at 4")
        val second = orchestrator.handle("Uska reminder 30 minute pehle kar do.")

        assertTrue("Expected Handled, got $second", second is ToolOrchestrator.Outcome.Handled)
        assertEquals(2, tool.calls.size)
        val updateCall = tool.calls[1]
        assertEquals("update_reminder", updateCall.operation)
        assertEquals("1001", updateCall.arguments["id"])
        // Nothing invents a title while rescheduling.
        assertNull(updateCall.arguments["title"])
    }

    @Test
    fun `demo 3 - messaging the last contact from memory alone`() = runTest {
        val contacts = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            behaviour = {
                ToolResult.Success("Found Abdul.", mapOf("contact_id" to "42", "name" to "Abdul", "phone" to "+923001234567"))
            },
        )
        val whatsapp = RecordingTool(
            id = ToolId.WHATSAPP,
            operations = setOf("prepare_message"),
            behaviour = {
                ToolResult.Success(
                    "WhatsApp is open with your message to Abdul.",
                    mapOf("recipient" to "Abdul", "phone" to "923001234567", "message" to it.arguments["message"].orEmpty()),
                )
            },
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
            // No person named — "usko" must resolve to the just-looked-up contact.
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"message":"on my way"}}"""),
        )
        val orchestrator = secretary(engine, listOf(contacts, whatsapp))

        orchestrator.handle("Abdul ka number dhoondo.")
        val second = orchestrator.handle("Usko WhatsApp bhi bhej do.")

        assertTrue("Expected Handled, got $second", second is ToolOrchestrator.Outcome.Handled)
        assertEquals("Abdul", whatsapp.calls.single().arguments["contact"])
    }

    // -----------------------------------------------------------------
    // State machine wiring
    // -----------------------------------------------------------------

    @Test
    fun `state settles at completed after a successful action with no follow-up suggestion`() = runTest {
        // Notification carries no follow-up suggestion (Day 05 Phase E Stage 2
        // only offers one after REMINDER/CALENDAR_EVENT), so this is the plain
        // "nothing left pending" case; the reminder-specific case below covers
        // the state a suggestion now legitimately leaves things in.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"notification","parameters":{"message":"x"}}"""),
        )
        val notificationTool = RecordingTool(
            ToolId.NOTIFICATION, setOf("notify"),
        ) { ToolResult.Success("Done.", mapOf("notification_id" to "1", "channel" to "assistant")) }
        val orchestrator = secretary(engine, listOf(notificationTool))

        orchestrator.handle("notify me")

        assertEquals(SecretaryState.COMPLETED, orchestrator.state.value)
    }

    /**
     * On-device-shaped regression: a plain reminder create now legitimately
     * ends in WAITING_CONFIRMATION, not COMPLETED, because Stage 2's
     * follow-up suggestion ("...calendar event bhi bana doon?") fires right
     * after it — this is the brief's own worked example, not a regression.
     */
    @Test
    fun `a reminder create settles at waiting-confirmation because of its own follow-up suggestion`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me at 4")

        assertEquals(SecretaryState.WAITING_CONFIRMATION, orchestrator.state.value)
    }

    @Test
    fun `state settles at failed when the tool fails`() = runTest {
        // A resolvable target is required for the call to reach the tool at
        // all, so this first creates a reminder, then cancels it against a
        // tool that always reports failure.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel"}"""),
        )
        val failingTool = reminderTool { call ->
            when (call.operation) {
                "create_reminder" -> ToolResult.Success(
                    "Reminder set.",
                    mapOf("reminder_id" to "1001", "trigger_at" to "1000000", "exact" to "true"),
                )
                else -> ToolResult.Failure("no such reminder", retryable = false)
            }
        }
        val orchestrator = secretary(engine, listOf(failingTool))

        orchestrator.handle("remind me at 4")
        orchestrator.handle("cancel the reminder")

        assertEquals(SecretaryState.FAILED, orchestrator.state.value)
    }

    @Test
    fun `state returns to idle after conversation`() = runTest {
        val orchestrator = secretary(
            ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}""")),
            listOf(reminderTool()),
        )

        orchestrator.handle("hello")

        assertEquals(SecretaryState.IDLE, orchestrator.state.value)
    }

    @Test
    fun `state waits on missing information and returns to executing once answered`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"the meeting"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"time":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me about the meeting")
        assertEquals(SecretaryState.WAITING_MISSING_INFORMATION, orchestrator.state.value)

        orchestrator.handle("at 4pm")
        // Not COMPLETED: the reminder's own follow-up suggestion fires
        // immediately after it succeeds (Stage 2) — see the dedicated test
        // for that behavior.
        assertEquals(SecretaryState.WAITING_CONFIRMATION, orchestrator.state.value)
        // Day 08-C: unlike a confirmation or contact-selection reply, an
        // answer to a clarification question is open-ended natural language
        // ("at 4pm", "call it standup") with no closed, deterministically
        // parseable answer space this codebase has an extractor for — see
        // the Day 08-C completion notes for why no Fast Path was built for
        // this case. It must keep reaching the model, not be silently
        // short-circuited.
        assertEquals(2, engine.index)
    }

    // -----------------------------------------------------------------
    // Permission recovery
    // -----------------------------------------------------------------

    @Test
    fun `a missing permission moves to waiting-permission and resuming updates memory`() = runTest {
        var granted = false
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
            behaviour = {
                ToolResult.Success("Found Abdul.", mapOf("contact_id" to "42", "name" to "Abdul"))
            },
        )
        val permissions = object : PermissionManager {
            override fun state(permission: DpsPermission) =
                if (granted) PermissionState.GRANTED else PermissionState.DENIED

            override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
            override fun missing(permissions: Set<DpsPermission>) =
                permissions.filterNot { state(it).isUsable }.toSet()

            override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
        }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""),
        )
        val orchestrator = secretary(engine, listOf(tool), permissions)

        val first = orchestrator.handle("find Abdul")
        assertTrue(first is ToolOrchestrator.Outcome.NeedsPermission)
        assertEquals(SecretaryState.WAITING_PERMISSION, orchestrator.state.value)

        granted = true
        val resumed = orchestrator.onPermissionResult()

        assertTrue(resumed is ToolOrchestrator.Outcome.Handled)
        assertEquals("Abdul", orchestrator.memory.value.lastContact?.displayName)
        assertEquals(SecretaryState.COMPLETED, orchestrator.state.value)
    }

    /**
     * Requirement 8: never execute a destructive action twice because of a
     * retry. The most plausible source of a duplicate call in this codebase
     * is exactly this — a permission-granted callback firing more than once
     * (a genuine Android lifecycle possibility, e.g. a rapid double-tap on
     * the system dialog reaching the app twice) — so this is asserted
     * directly rather than left to follow from "it compiles."
     */
    @Test
    fun `resuming a permission grant twice only ever runs the tool once`() = runTest {
        val calls = mutableListOf<ToolCall>()
        var granted = false
        val tool = RecordingTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
            behaviour = {
                calls += it
                ToolResult.Success("Found Abdul.", mapOf("contact_id" to "42", "name" to "Abdul"))
            },
        )
        val permissions = object : PermissionManager {
            override fun state(permission: DpsPermission) =
                if (granted) PermissionState.GRANTED else PermissionState.DENIED

            override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
            override fun missing(permissions: Set<DpsPermission>) =
                permissions.filterNot { state(it).isUsable }.toSet()

            override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
        }
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"contact_lookup","parameters":{"person":"Abdul"}}"""))
        val orchestrator = secretary(engine, listOf(tool), permissions)

        orchestrator.handle("find Abdul")
        granted = true
        val firstResume = orchestrator.onPermissionResult()
        val secondResume = orchestrator.onPermissionResult()

        assertTrue(firstResume is ToolOrchestrator.Outcome.Handled)
        assertNull("A second resume with nothing held must be a no-op, not a repeat", secondResume)
        assertEquals("Tool ran more than once for a single grant", 1, calls.size)
    }

    @Test
    fun `resuming with nothing pending does nothing and returns to idle`() = runTest {
        val orchestrator = secretary(
            ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}""")),
            listOf(reminderTool()),
        )

        assertNull(orchestrator.onPermissionResult())
    }

    // -----------------------------------------------------------------
    // Reset
    // -----------------------------------------------------------------

    @Test
    fun `reset forgets memory and returns to idle`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me at 4")
        orchestrator.reset()

        assertEquals(SecretaryState.IDLE, orchestrator.state.value)
        assertNull(orchestrator.memory.value.lastReminder)
        assertNull(orchestrator.pendingQuestion())
        assertTrue(!orchestrator.hasPendingPermissionAction())
    }

    // -----------------------------------------------------------------
    // Never crashes
    // -----------------------------------------------------------------

    @Test
    fun `no message or classification ever throws`() = runTest {
        val classifications = listOf(
            "I'd be happy to help!",
            """{"intent":"reminder","parameters":{"title":"cut off""",
            """{"intent":"unknown_thing"}""",
            "",
        )

        classifications.forEach { classification ->
            val outcome = secretary(ScriptedEngine(DpsResult.Success(classification)), listOf(reminderTool()))
                .handle("do something")
            assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
        }
    }

    @Test
    fun `a classification failure is conversational and resets state`() = runTest {
        val orchestrator = secretary(
            ScriptedEngine(DpsResult.Failure(DpsError.Runtime.GenerationFailed("x"))),
            listOf(reminderTool()),
        )

        val outcome = orchestrator.handle("anything")

        assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
        assertEquals(SecretaryState.IDLE, orchestrator.state.value)
    }

    // -----------------------------------------------------------------
    // Day 08-B — a same-pass conversational reply, with a safe fallback
    // -----------------------------------------------------------------

    @Test
    fun `a conversation intent carrying a message is surfaced as a ready reply`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"Wa alaikum assalam! Sab theek hai."}}"""),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("Salam DPS, kya haal hai?")

        val conversational = outcome as? ToolOrchestrator.Outcome.Conversational
        assertTrue("Expected Conversational, got $outcome", conversational != null)
        assertEquals("Wa alaikum assalam! Sab theek hai.", conversational!!.replyText)
        // Still exactly one inference call — the reply rode along on the
        // classification pass rather than costing a second one.
        assertEquals(1, engine.index)
    }

    @Test
    fun `a bare conversation intent with no message leaves replyText null so the caller falls back`() = runTest {
        // No message field at all — the always-safe case: AiSessionManager
        // must fall back to its own second, streaming generation pass,
        // exactly as it did before this field existed.
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}"""))

        val outcome = secretary(engine, listOf(reminderTool())).handle("tell me something interesting")

        val conversational = outcome as? ToolOrchestrator.Outcome.Conversational
        assertTrue("Expected Conversational, got $outcome", conversational != null)
        assertNull(conversational!!.replyText)
    }

    @Test
    fun `a blank reply is treated the same as no reply`() = runTest {
        // A model that emits "reply":"" (or whitespace) must not produce a
        // blank assistant bubble; SecretaryOrchestrator trims and nulls this
        // out itself, since IntentParameters.reply is outside the value()
        // helper that does this for the other fields.
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"   "}}"""))

        val outcome = secretary(engine, listOf(reminderTool())).handle("hmm")

        val conversational = outcome as? ToolOrchestrator.Outcome.Conversational
        assertTrue("Expected Conversational, got $outcome", conversational != null)
        assertNull(conversational!!.replyText)
    }

    @Test
    fun `a reply that only echoes the user's own message is discarded`() = runTest {
        // The one real failure mode on-device measurement found (Day 08-B):
        // a model that restates the question instead of answering it. This
        // must fall back to the streaming pass exactly like an empty reply.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"Salam DPS, kya haal hai?"}}"""),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("Salam DPS, kya haal hai?")

        val conversational = outcome as? ToolOrchestrator.Outcome.Conversational
        assertTrue("Expected Conversational, got $outcome", conversational != null)
        assertNull(conversational!!.replyText)
    }

    @Test
    fun `an echo differing only by punctuation and case is still discarded`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"tell me something interesting"}}"""),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("Tell me something interesting!")

        val conversational = outcome as? ToolOrchestrator.Outcome.Conversational
        assertTrue("Expected Conversational, got $outcome", conversational != null)
        assertNull(conversational!!.replyText)
    }

    @Test
    fun `a genuine tool request is unaffected by the reply shortcut`() = runTest {
        // The schema addition must not change tool-routing behaviour at all:
        // no reply field is expected or read for a real action.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","time":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("remind me to call the bank at 4")

        val handled = outcome as? ToolOrchestrator.Outcome.Handled
        assertTrue("Expected Handled, got $outcome", handled != null)
        // startsWith rather than equality: a successful reminder creation may
        // carry a trailing follow-up suggestion (Day 05 Phase E Stage 2),
        // unrelated to this test's concern.
        assertTrue(handled!!.reply.startsWith("Reminder set."))
        assertEquals(1, engine.index)
    }

    @Test
    fun `recent context is included in the classification prompt when there is any`() = runTest {
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"Sure."}}"""))

        secretary(engine, listOf(reminderTool()))
            .handle("and after that?", recentContext = "User: What's on today?\nDPS: Three meetings.\n")

        assertTrue(
            "Expected the recent-context block in the prompt, got: ${engine.capturedPrompts.single()}",
            engine.capturedPrompts.single().contains("User: What's on today?\nDPS: Three meetings.\n"),
        )
    }

    @Test
    fun `no recent context block appears when there is none to give`() = runTest {
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"conversation","parameters":{"reply":"Hi."}}"""))

        secretary(engine, listOf(reminderTool())).handle("hi", recentContext = null)

        assertTrue(!engine.capturedPrompts.single().contains("Recent conversation:"))
    }

    // -----------------------------------------------------------------
    // Test doubles for Stage 2
    // -----------------------------------------------------------------

    private fun calendarTool(
        behaviour: suspend (ToolCall) -> ToolResult = {
            when (it.operation) {
                "create_event" -> ToolResult.Success(
                    "Added to your calendar.",
                    mapOf("event_id" to "500", "calendar" to "Personal", "start" to "2026-08-07T16:00", "end" to "2026-08-07T17:00"),
                )
                "update_event" -> ToolResult.Success("Updated the event.", mapOf("event_id" to (it.arguments["id"] ?: "500")))
                "delete_event" -> ToolResult.Success("Deleted the event.", mapOf("event_id" to (it.arguments["id"] ?: "500")))
                else -> ToolResult.Unsupported("not implemented")
            }
        },
    ) = RecordingTool(ToolId.CALENDAR, setOf("create_event", "update_event", "delete_event"), behaviour = behaviour)

    /** Always reports two "Abdul"s ambiguous, matching AndroidContactsTool's real Success-with-ambiguous shape. */
    private fun ambiguousContactsTool() = RecordingTool(
        ToolId.CONTACTS,
        setOf("find_contact"),
        requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        behaviour = {
            ToolResult.Success(
                "2 contacts match \"Abdul\". Which one?",
                mapOf(
                    "count" to "2",
                    "ambiguous" to "true",
                    "contact_0_id" to "1", "contact_0_name" to "Abdul Rahman", "contact_0_phone" to "+923001111111",
                    "contact_1_id" to "2", "contact_1_name" to "Abdul Rauf", "contact_1_phone" to "+923002222222",
                ),
            )
        },
    )

    private fun whatsAppTool(behaviour: suspend (ToolCall) -> ToolResult = {
        ToolResult.Success(
            "WhatsApp is open with your message to ${it.arguments["contact"]}.",
            mapOf("recipient" to it.arguments["contact"].orEmpty(), "phone" to it.arguments["phone"].orEmpty(), "message" to it.arguments["message"].orEmpty()),
        )
    }) = RecordingTool(ToolId.WHATSAPP, setOf("prepare_message", "send_message"), behaviour = behaviour)

    // -----------------------------------------------------------------
    // Multi-step planning (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `a compound request runs both steps and reports both`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting with Abdul","date":"2026-08-07","time":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting with Abdul"}}
                ]}""",
            ),
        )

        val outcome = secretary(engine, listOf(calendar, reminder))
            .handle("kal 4 baje meeting bana do aur reminder bhi laga do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.size)
        assertEquals(1, reminder.calls.size)
        assertEquals("create_event", calendar.calls.single().operation)
        assertEquals("create_reminder", reminder.calls.single().operation)
    }

    @Test
    fun `a reminder step with no time defaults to 30 minutes before the event step just created`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","date":"2026-08-07","time":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder)).handle("kal 4 baje meeting bana do aur reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 30)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    @Test
    fun `a required step failing stops the plan and reports honestly rather than claiming success`() = runTest {
        val calendar = calendarTool { ToolResult.Failure("No calendar on this device accepts new events.", retryable = false) }
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","date":"2026-08-07","time":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","time":"15:30"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        val outcome = secretary.handle("kal meeting bana do aur reminder laga do") as ToolOrchestrator.Outcome.Handled

        assertTrue(outcome.reply.contains("No calendar on this device"))
        // The second step must never have been attempted once the first, required, step failed.
        assertTrue("Reminder step ran despite the calendar step failing", reminder.calls.isEmpty())
        assertEquals(SecretaryState.FAILED, secretary.state.value)
    }

    @Test
    fun `only one inference pass is spent on a compound request`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"x","date":"2026-08-07","time":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"x","time":"15:30"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendarTool(), reminderTool()))
            .handle("kal meeting bana do aur reminder laga do")

        assertEquals(1, engine.index)
    }

    // -----------------------------------------------------------------
    // Contact disambiguation (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `an ambiguous contact asks which one instead of guessing`() = runTest {
        val whatsApp = whatsAppTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"person":"Abdul","message":"on my way"}}"""),
        )

        val outcome = secretary(engine, listOf(ambiguousContactsTool(), whatsApp))
            .handle("Abdul ko WhatsApp kar do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("Abdul Rahman"))
        assertTrue(outcome.question.contains("Abdul Rauf"))
        assertTrue("WhatsApp must not run before disambiguation", whatsApp.calls.isEmpty())
    }

    @Test
    fun `picking a candidate by number resumes without another inference pass`() = runTest {
        val whatsApp = whatsAppTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"person":"Abdul","message":"on my way"}}"""),
        )
        val secretary = secretary(engine, listOf(ambiguousContactsTool(), whatsApp))

        secretary.handle("Abdul ko WhatsApp kar do")
        val outcome = secretary.handle("2")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, whatsApp.calls.size)
        assertEquals("Abdul Rauf", whatsApp.calls.single().arguments["contact"])
        assertEquals("+923002222222", whatsApp.calls.single().arguments["phone"])
        // The classification pass ran once, for the original message — not again for "2".
        assertEquals(1, engine.index)
        assertEquals("Abdul Rauf", secretary.memory.value.lastContact?.displayName)
    }

    @Test
    fun `a calendar event naming an ambiguous person also asks, matching the brief's own example`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"person":"Abdul","title":"meeting","date":"2026-08-07","time":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(ambiguousContactsTool(), calendar))
            .handle("Kal 4 baje Abdul ke saath meeting schedule kar do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue("Calendar event must wait for disambiguation", calendar.calls.isEmpty())
    }

    @Test
    fun `a calendar event proceeds anyway when the named person has no contact match at all`() = runTest {
        val calendar = calendarTool()
        val noMatch = RecordingTool(
            ToolId.CONTACTS, setOf("find_contact"), setOf(DpsPermission.READ_CONTACTS),
        ) { ToolResult.Failure("No contact matches \"Ghost\".", retryable = false) }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"person":"Ghost","title":"meeting","date":"2026-08-07","time":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(noMatch, calendar)).handle("meeting with Ghost tomorrow at 4")

        // Grounding is best-effort for calendar events — a miss must not block
        // a request the calendar tool never needed the contact to fulfil.
        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.size)
    }

    // -----------------------------------------------------------------
    // Destructive delete confirmation (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `deleting a calendar event asks before doing anything`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 9")
        calendar.calls.clear()
        val outcome = secretary.handle("us meeting ko delete kar do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("standup"))
        assertTrue(outcome.question.contains("can't be undone"))
        assertTrue("Nothing may be deleted before confirmation", calendar.calls.isEmpty())
    }

    @Test
    fun `confirming a delete executes it`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 9")
        calendar.calls.clear()
        secretary.handle("us meeting ko delete kar do")
        val confirmed = secretary.handle("haan")

        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.size)
        assertEquals("delete_event", calendar.calls.single().operation)
    }

    @Test
    fun `declining a delete leaves it alone`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 9")
        calendar.calls.clear()
        secretary.handle("us meeting ko delete kar do")
        val callsBeforeDecline = engine.index
        val declined = secretary.handle("nahi")

        assertTrue("Expected Handled, got $declined", declined is ToolOrchestrator.Outcome.Handled)
        assertTrue("Declining must not delete anything", calendar.calls.isEmpty())
        // Day 08-C: closes the one gap in this file's existing inference-count
        // proofs (accept and contact-selection were already covered) —
        // ConfirmationParser.parse("nahi") answers this deterministically,
        // exactly like YES already does two tests above.
        assertEquals(
            "Declining a confirmation must not cost another inference pass.",
            callsBeforeDecline,
            engine.index,
        )
    }

    // -----------------------------------------------------------------
    // Follow-up suggestions (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `a created calendar event offers a reminder suggestion in the same reply`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendarTool()))

        val outcome = secretary.handle("standup tomorrow at 9") as ToolOrchestrator.Outcome.Handled

        assertTrue(outcome.reply.contains("reminder"))
        assertEquals(SecretaryState.WAITING_CONFIRMATION, secretary.state.value)
    }

    @Test
    fun `accepting a suggestion executes it without another inference pass`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        secretary.handle("standup tomorrow at 9")
        val accepted = secretary.handle("haan please")

        assertTrue("Expected Handled, got $accepted", accepted is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, reminder.calls.size)
        assertEquals(1, engine.index)
    }

    @Test
    fun `an unrelated next message is not trapped behind an unanswered suggestion`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        // The second script entry is what the *unrelated* follow-up message
        // classifies as — it must actually reach the model, proving the
        // pending suggestion did not silently swallow it as a yes/no answer.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07","time":"09:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"pay rent","time":"18:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        secretary.handle("standup tomorrow at 9")
        val continued = secretary.handle("remind me to pay rent at 6pm")

        assertTrue("Expected Handled, got $continued", continued is ToolOrchestrator.Outcome.Handled)
        assertEquals(2, engine.index)
        assertEquals("pay rent", reminder.calls.single().arguments["title"])
    }
}
