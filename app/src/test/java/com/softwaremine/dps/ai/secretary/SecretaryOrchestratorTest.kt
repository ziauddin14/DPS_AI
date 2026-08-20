package com.softwaremine.dps.ai.secretary

import android.content.SharedPreferences
import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.ReminderMemory
import com.softwaremine.dps.domain.memory.TaskMemory
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.preferences.UserPreferences
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
import java.time.LocalDateTime
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

    /**
     * Minimal in-memory fake of [SharedPreferences] (M3-B), mirroring the one
     * [com.softwaremine.dps.data.android.memory.PersistentMemoryStoreTest]
     * already uses — only what [PersistentMemoryStore] actually calls needs
     * to behave correctly.
     */
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

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    private fun secretary(
        engine: AiEngine,
        tools: List<AndroidTool>,
        permissions: PermissionManager = FakePermissions(),
        now: () -> Long = System::currentTimeMillis,
        temporalNow: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now(zone) },
        persistentMemoryStore: PersistentMemoryStore = PersistentMemoryStore(FakeSharedPreferences(), silentLogger),
        persistentPreferenceStore: PersistentPreferenceStore = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger),
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
            selector = ToolSelector(zone = zone, now = temporalNow),
            responses = com.softwaremine.dps.ai.intent.ToolResponseGenerator(),
            logger = silentLogger,
        )

        val temporalPhraseResolver = TemporalPhraseResolver(zone = zone, now = temporalNow)
        val temporalGroundingGuard = TemporalGroundingGuard()

        return SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(zone = zone),
            temporalPhraseResolver = temporalPhraseResolver,
            temporalGroundingGuard = temporalGroundingGuard,
            temporalStepAttributor = TemporalStepAttributor(
                spanFinder = TemporalPhraseSpanFinder(resolver = temporalPhraseResolver),
                groundingGuard = temporalGroundingGuard,
            ),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(zone = zone),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(zone = zone),
            persistentMemoryStore = persistentMemoryStore,
            persistentPreferenceStore = persistentPreferenceStore,
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
                """{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}""",
            ),
        )

        val outcome = secretary(engine, listOf(tool)).handle("remind me to call the bank at 16:00")

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
                """{"intent":"reminder","parameters":{"person":"Abdul","title":"call Abdul","raw_when":"16:00"}}""",
            ),
        )
        val orchestrator = secretary(engine, listOf(reminders))

        val outcome = orchestrator.handle("Kal 16:00 Abdul ko yaad dila dena.")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1001, orchestrator.memory.value.lastReminder?.id)
    }

    @Test
    fun `demo 2 - rescheduling the last reminder from memory alone`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}""",
            ),
            // No reminder named — the follow-up must resolve it from memory.
            DpsResult.Success("""{"intent":"reminder","action_type":"update"}"""),
        )
        val orchestrator = secretary(engine, listOf(tool))

        orchestrator.handle("remind me to call the bank at 16:00")
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
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","raw_when":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me at 16:00")

        assertEquals(SecretaryState.WAITING_CONFIRMATION, orchestrator.state.value)
    }

    @Test
    fun `state settles at failed when the tool fails`() = runTest {
        // A resolvable target is required for the call to reach the tool at
        // all, so this first creates a reminder, then cancels it against a
        // tool that always reports failure. Phase 5 — Reminder Cancel
        // Confirmation — inserted a confirmation turn between the cancel
        // request and the tool actually running, so a "haan" is now needed
        // to reach the failing tool; ConfirmationParser answers it
        // deterministically, so the engine still only needs its original
        // two scripted replies.
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","raw_when":"16:00"}}"""),
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

        orchestrator.handle("remind me at 16:00")
        orchestrator.handle("cancel the reminder")
        orchestrator.handle("haan")

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
            DpsResult.Success("""{"intent":"reminder","parameters":{"raw_when":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me about the meeting")
        assertEquals(SecretaryState.WAITING_MISSING_INFORMATION, orchestrator.state.value)

        orchestrator.handle("at 16:00")
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
    // Cross-turn clarification merge (Track A — Calendar Delete/Update
    // Classification Reliability investigation)
    //
    // The merge in handleSingleStep used to fire whenever a clarification
    // was pending, regardless of whether the new message's classified type
    // matched what the question was actually about. A live-model
    // investigation caught this directly: a dangling "when should I remind
    // you?" clarification's leftover `message` field satisfied a completely
    // unrelated NOTIFICATION request's required-field check on the next
    // turn, letting it execute instead of asking its own question. The fix
    // gates the merge on the new classification's type matching the pending
    // question's type (or falling back to bare CONVERSATION, which carries
    // no fields of its own to leak).
    // -----------------------------------------------------------------

    @Test
    fun `an answer classified as the same intent type still merges with the pending clarification`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"raw_when":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminder))

        val first = orchestrator.handle("remind me to call the bank")
        assertTrue("Expected Clarify asking for a time, got $first", first is ToolOrchestrator.Outcome.Clarify)

        val second = orchestrator.handle("at 16:00")

        assertTrue("Expected Handled once both title and time are known, got $second", second is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, reminder.calls.size)
        assertEquals("create_reminder", reminder.calls.single().operation)
        assertEquals("call the bank", reminder.calls.single().arguments["title"])
    }

    @Test
    fun `a response classified as a different intent type does not inherit the pending clarification's parameters`() = runTest {
        val notification = RecordingTool(
            id = ToolId.NOTIFICATION,
            operations = setOf("notify", "cancel"),
            behaviour = { ToolResult.Success("Done.", mapOf("notification_id" to "1")) },
        )
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"message":"call the bank"}}"""),
            DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""),
        )
        val orchestrator = secretary(engine, listOf(notification))

        val first = orchestrator.handle("remind me to call the bank")
        assertTrue("Expected Clarify asking for a time, got $first", first is ToolOrchestrator.Outcome.Clarify)

        val second = orchestrator.handle("cancel the meeting")

        // Must ask the notification's own missing-field question — never
        // silently execute using "call the bank" left over from the
        // abandoned reminder clarification.
        assertTrue("Expected Clarify, got $second", second is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, notification.calls.size)
    }

    @Test
    fun `a reminder clarification followed by a calendar request does not inherit the reminder's title`() = runTest {
        val reminder = reminderTool()
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"the meeting"}}"""),
            DpsResult.Success(
                """{"intent":"calendar_event","action_type":"create","parameters":{"raw_when":"tomorrow at 1pm"}}""",
            ),
        )
        val orchestrator = secretary(engine, listOf(reminder, calendar))

        val first = orchestrator.handle("remind me about the meeting")
        assertTrue("Expected Clarify asking for a time, got $first", first is ToolOrchestrator.Outcome.Clarify)

        val second = orchestrator.handle("schedule lunch tomorrow at 1pm")

        // Must ask for the event's own title — never silently reuse "the
        // meeting" left over from the abandoned reminder clarification.
        assertTrue("Expected Clarify asking for a title, got $second", second is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, calendar.calls.size)
        assertEquals(0, reminder.calls.size)
    }

    @Test
    fun `a calendar clarification followed by a reminder request does not inherit the event's title`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","action_type":"create","parameters":{"title":"team sync"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"create","parameters":{"raw_when":"17:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(calendar, reminder))

        val first = orchestrator.handle("schedule a team sync")
        assertTrue("Expected Clarify asking for a time, got $first", first is ToolOrchestrator.Outcome.Clarify)

        val second = orchestrator.handle("remind me at 5pm")

        // Must ask what the reminder is about — never silently reuse "team
        // sync" left over from the abandoned calendar clarification.
        assertTrue("Expected Clarify, got $second", second is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, reminder.calls.size)
        assertEquals(0, calendar.calls.size)
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
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","raw_when":"16:00"}}"""),
        )
        val orchestrator = secretary(engine, listOf(reminderTool()))

        orchestrator.handle("remind me at 16:00")
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
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("remind me to call the bank at 16:00")

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

    private fun taskTool(behaviour: suspend (ToolCall) -> ToolResult = {
        ToolResult.Success("Task \"${it.arguments["title"]}\" added.", mapOf("task_id" to "1"))
    }) = RecordingTool(ToolId.TASK, setOf("create_task"), behaviour = behaviour)

    /** A single exact match for "Bilal", mirroring AndroidContactsTool's real single-match shape. */
    private fun singleContactTool(phone: String? = "+923001234567") = RecordingTool(
        ToolId.CONTACTS,
        setOf("find_contact"),
        requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        behaviour = {
            val data = buildMap {
                put("contact_id", "9")
                put("name", "Bilal Developer")
                phone?.let { put("phone", it) }
            }
            ToolResult.Success("Found Bilal Developer.", data)
        },
    )

    private fun noContactTool() = RecordingTool(
        ToolId.CONTACTS,
        setOf("find_contact"),
        requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
        behaviour = { ToolResult.Failure("No contact named \"Bilal\".", retryable = false) },
    )

    private fun callTool(behaviour: suspend (ToolCall) -> ToolResult = {
        ToolResult.Success(
            "The dialer is open with ${it.arguments["contact"]}'s number.",
            mapOf("recipient" to it.arguments["contact"].orEmpty(), "phone" to it.arguments["phone"].orEmpty()),
        )
    }) = RecordingTool(ToolId.PHONE, setOf("place_call"), behaviour = behaviour)

    // -----------------------------------------------------------------
    // Temporal grounding guard (Day 08-E follow-up)
    //
    // The real-device investigation found the classification model
    // reliably producing raw_when="kal shaam 7 baje" for messages that
    // never mentioned a time at all — reproduced with a fresh model reload
    // and zero session history, ruling out cache/session contamination.
    // TemporalGroundingGuard is the fix: these tests reproduce the exact
    // failure through the full pipeline, scripted engine standing in for
    // the real model's actual (wrong) output.
    // -----------------------------------------------------------------

    /**
     * The critical regression test: byte-for-byte the real-device failure.
     * A model that hallucinates a temporal phrase for a message that never
     * had one must never let it reach TemporalPhraseResolver, ToolSelector,
     * or the tool layer — not as a date, not as a time, not as a due date.
     */
    @Test
    fun `a raw_when the model hallucinated for a non-temporal task is discarded entirely`() = runTest {
        val tool = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"task","parameters":{"title":"Buy milk","raw_when":"kal shaam 7 baje"}}""",
            ),
        )

        val outcome = secretary(engine, listOf(tool)).handle("Buy milk ka task bana do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        val handled = outcome as ToolOrchestrator.Outcome.Handled
        assertNull("Ungrounded raw_when must not survive", handled.intent.parameters.rawWhen)
        assertNull("No date may be resolved from an ungrounded raw_when", handled.intent.parameters.date)
        assertNull("No time may be resolved from an ungrounded raw_when", handled.intent.parameters.time)
        assertTrue(
            "A fabricated due date must never reach the tool call",
            !tool.calls.single().arguments.containsKey("due"),
        )
    }

    @Test
    fun `a raw_when the model hallucinated for a conversational message is also discarded`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"conversation","parameters":{"raw_when":"kal shaam 7 baje","reply":"I am fine, thanks!"}}""",
            ),
        )

        val outcome = secretary(engine, listOf(reminderTool())).handle("Salam DPS, kya haal hai?")

        // Conversation never routes through withResolvedTemporalPhrase at
        // all, but this pins that a bogus raw_when riding along on an
        // unrelated conversational reply causes no harm either.
        assertTrue("Expected Conversational, got $outcome", outcome is ToolOrchestrator.Outcome.Conversational)
    }

    @Test
    fun `a genuinely grounded raw_when still resolves normally through the guard`() = runTest {
        val tool = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}""",
            ),
        )

        val outcome = secretary(engine, listOf(tool)).handle("call the bank at 16:00")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        val handled = outcome as ToolOrchestrator.Outcome.Handled
        assertEquals("16:00", handled.intent.parameters.time)
    }

    /**
     * The regression test for the vulnerability the Day 08-E audit found and
     * [TemporalStepAttributor] was built to close: a hallucinated `raw_when`
     * on one step must never pass just because a *different* step of the
     * same compound message genuinely said those words. Byte for byte the
     * audit's own worked example (with a period word added so the phrase
     * actually resolves — the audit's literal "kal 7 baje" is a bare hour,
     * correctly refused by [TemporalPhraseResolver] regardless of this fix,
     * which would stop the plan at step 1 and never reach step 2 at all).
     *
     * Before [TemporalStepAttributor] existed, this exact scenario passed
     * both steps (see git history for the "KNOWN LIMITATION" test this
     * replaces). It must now reject step 2.
     */
    @Test
    fun `a hallucinated raw_when copying a sibling step's genuine phrase is now rejected`() = runTest {
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal shaam 7 baje"}},
                    {"intent":"task","parameters":{"title":"khareedna","raw_when":"kal shaam 7 baje"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(reminder, task))
            .handle("kal shaam 7 baje Ali ko call karna aur milk khareedna")

        // Step 1 legitimately said "kal shaam 7 baje" — claims the one
        // occurrence in the message, resolves normally.
        assertEquals(1, reminder.calls.size)
        assertTrue("Step 1's genuine phrase must still resolve", reminder.calls.single().arguments.containsKey("time"))
        // Step 2 never mentioned a time — its identical raw_when finds no
        // still-unclaimed occurrence (the only one is already claimed by
        // step 1), so it is discarded. No fabricated due date.
        assertEquals(1, task.calls.size)
        assertTrue(
            "Step 2's raw_when must be rejected — the one occurrence of this phrase belongs to step 1",
            !task.calls.single().arguments.containsKey("due"),
        )
    }

    /**
     * Multi-step investigation Case G: a phrase absent from the *entire*
     * compound message — not just one step — must still be rejected on
     * both steps. This already held under whole-message grounding and
     * continues to hold under [TemporalStepAttributor]: with no genuine
     * occurrence anywhere in the message, [TemporalPhraseSpanFinder] finds
     * no span at all for either step to claim.
     *
     * Both steps are TASK, not REMINDER: TASK's completeness never depends
     * on a time ([com.softwaremine.dps.domain.intent.requiredFields] only
     * needs a title), so a rejected `raw_when` does not stop the plan at
     * step 1 on a clarification question — both steps genuinely execute,
     * which is what actually exercises the guard on step 2's raw_when
     * rather than the plan never reaching it.
     */
    @Test
    fun `multi-step Case G - a phrase absent from the whole message is rejected on every step`() = runTest {
        val first = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"task","parameters":{"title":"meeting follow-up","raw_when":"kal shaam 7 baje"}},
                    {"intent":"task","parameters":{"title":"milk","raw_when":"kal shaam 7 baje"}}
                ]}""",
            ),
        )

        val outcome = secretary(engine, listOf(first))
            .handle("meeting follow-up ka task bana do aur milk ka task bana do")

        // Both steps route to the one registered TASK tool, so both calls
        // land on `first` — the point is that the hallucinated phrase is
        // absent from the whole message, so TemporalPhraseSpanFinder finds
        // no span for either step to claim.
        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(2, first.calls.size)
        first.calls.forEach { call ->
            assertTrue(
                "A phrase absent from the whole message must be rejected on every step: $call",
                !call.arguments.containsKey("due"),
            )
        }
    }

    /** Multi-step investigation Example 1: the temporal phrase belongs to step 1 only. */
    @Test
    fun `multi-step - a temporal phrase on the first step does not reach the second`() = runTest {
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal shaam 7 baje"}},
                    {"intent":"task","parameters":{"title":"milk"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(reminder, task))
            .handle("kal shaam 7 baje Ali ko call karne ka reminder laga do aur milk khareedne ka task bana do")

        assertEquals(1, reminder.calls.size)
        assertTrue(reminder.calls.single().arguments.containsKey("time"))
        assertEquals(1, task.calls.size)
        assertTrue("Step 2 never had a raw_when at all — no due should appear", !task.calls.single().arguments.containsKey("due"))
    }

    /** Multi-step investigation Example 2: the temporal phrase belongs to step 2 only. */
    @Test
    fun `multi-step - a temporal phrase on the second step does not reach the first`() = runTest {
        val task = taskTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"task","parameters":{"title":"milk"}},
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal shaam 7 baje"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(task, reminder))
            .handle("milk khareedne ka task bana do aur kal shaam 7 baje Ali ko call karne ka reminder laga do")

        assertTrue("Step 1 never had a raw_when at all — no due should appear", !task.calls.single().arguments.containsKey("due"))
        assertEquals(1, reminder.calls.size)
        assertTrue(reminder.calls.single().arguments.containsKey("time"))
    }

    /** Multi-step investigation Example 3: two distinct genuine phrases, each independently attributed. */
    @Test
    fun `multi-step - two different genuine phrases resolve independently`() = runTest {
        val first = reminderTool()
        val second = RecordingTool(ToolId.REMINDER, setOf("create_reminder")) {
            ToolResult.Success("Reminder set.", mapOf("reminder_id" to "2", "trigger_at" to "2000000", "exact" to "true"))
        }
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"first reminder","raw_when":"kal shaam 7 baje"}},
                    {"intent":"reminder","parameters":{"title":"second reminder","raw_when":"kal raat 11 baje"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(first))
            .handle("kal shaam 7 baje reminder laga do aur kal raat 11 baje doosra reminder laga do")

        // Both steps route to the one registered REMINDER tool.
        assertEquals(2, first.calls.size)
        first.calls.forEach { call ->
            assertTrue("Each genuinely distinct phrase must resolve on its own step: $call", call.arguments.containsKey("time"))
        }
    }

    /** Multi-step investigation Case F: no temporal phrase anywhere, nothing is invented for either step. */
    @Test
    fun `multi-step - neither step has a temporal phrase`() = runTest {
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"task","parameters":{"title":"meeting follow-up"}},
                    {"intent":"task","parameters":{"title":"milk"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(task))
            .handle("meeting follow-up ka task bana do aur milk ka task bana do")

        assertEquals(2, task.calls.size)
        task.calls.forEach { call -> assertTrue(!call.arguments.containsKey("due")) }
    }

    /** Multi-step investigation: punctuation around the boundary must not break attribution. */
    @Test
    fun `multi-step - punctuation around the phrase does not prevent attribution`() = runTest {
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal shaam 7 baje"}},
                    {"intent":"task","parameters":{"title":"milk"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(reminder, task))
            .handle("Kal, shaam 7 baje, Ali ko call karne ka reminder laga do — aur milk khareedne ka task bana do.")

        assertTrue(reminder.calls.single().arguments.containsKey("time"))
        assertTrue(!task.calls.single().arguments.containsKey("due"))
    }

    /** Multi-step investigation: a dropped ko/ke/ki filler word still attributes correctly. */
    @Test
    fun `multi-step - a raw_when that dropped a filler word still attributes to its own step`() = runTest {
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal shaam 7 baje"}},
                    {"intent":"task","parameters":{"title":"milk"}}
                ]}""",
            ),
        )

        // The user's own message says "kal shaam ko 7 baje"; the model's
        // raw_when dropped "ko" — must still be accepted for step 1 and
        // still never appear on step 2.
        secretary(engine, listOf(reminder, task))
            .handle("kal shaam ko 7 baje Ali ko call karne ka reminder laga do aur milk khareedne ka task bana do")

        assertTrue(reminder.calls.single().arguments.containsKey("time"))
        assertTrue(!task.calls.single().arguments.containsKey("due"))
    }

    /**
     * Multi-step investigation: the deliberately unsolved case — a phrase
     * genuinely meant to apply to two steps. "Kal subah 9 baje dono kaam
     * karne hain: Ali ko call karo aur uska task bana do" (tomorrow 9am both
     * things need doing — a full period+hour phrase, not the bare "kal
     * subah" the task's own example uses, because a bare period word with
     * no hour never resolves a time at all and would block the plan at step
     * 1's own clarification before step 2 is ever reached, never exercising
     * the behavior this test exists to prove). The phrase occurs exactly
     * once in the message; a step whose raw_when could refer to it, but
     * whose occurrence was already claimed by the other step, must fail
     * closed rather than silently inherit — per this task's explicit
     * instruction not to invent shared-ownership semantics.
     */
    @Test
    fun `multi-step - a phrase intended for two steps is not silently shared, fails closed`() = runTest {
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"reminder","parameters":{"title":"call Ali","raw_when":"kal subah 9 baje"}},
                    {"intent":"task","parameters":{"title":"uska task","raw_when":"kal subah 9 baje"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(reminder, task))
            .handle("Kal subah 9 baje dono kaam karne hain: Ali ko call karo aur uska task bana do")

        // Step 1 claims the one genuine occurrence.
        assertTrue(reminder.calls.single().arguments.containsKey("time"))
        // Step 2's identical claim finds no unclaimed occurrence left —
        // rejected, not silently granted the shared meaning.
        assertTrue(
            "A phrase meant for two steps must not be silently shared — this is deliberately unsolved",
            !task.calls.single().arguments.containsKey("due"),
        )
    }

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
                    {"intent":"calendar_event","parameters":{"title":"meeting with Abdul","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting with Abdul"}}
                ]}""",
            ),
        )

        val outcome = secretary(engine, listOf(calendar, reminder))
            .handle("kal 16:00 meeting bana do aur reminder bhi laga do")

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
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder)).handle("kal 16:00 meeting bana do aur reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 30)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    // -----------------------------------------------------------------
    // M2-A — generalized cross-step reminder offset
    //
    // anchorToPriorEvent's fixed 30-minute default is now only a fallback:
    // a stated offset ("15 minutes before") on the reminder step's own
    // raw_when is read (via ReferenceResolver.findRelativeOffsetMillis,
    // widened to internal for this reuse) before TemporalStepAttributor
    // strips it as an unrecognised absolute phrase, and used instead.
    // -----------------------------------------------------------------

    @Test
    fun `an explicit 15-minute offset is used instead of the 30-minute default`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"15 minutes before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder))
            .handle("kal 16:00 meeting bana do aur 15 minutes before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 45)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    /** Proves the parser reads the stated amount rather than always answering 15. */
    @Test
    fun `an explicit 10-minute offset is used, not hardcoded to 15`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"10 minutes before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder))
            .handle("kal 16:00 meeting bana do aur 10 minutes before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 50)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    /** The Roman Urdu form ReferenceResolver already supports for single-turn rescheduling. */
    @Test
    fun `an explicit Roman Urdu offset (10 minutes pehle) is understood the same way`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"10 minutes pehle"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder))
            .handle("kal 16:00 meeting bana do aur 10 minutes pehle reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 50)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    /**
     * The isolation guarantee TemporalStepAttributor exists for (Day 08-E)
     * must hold for offsets too: a sibling step's own, different offset
     * phrase must never leak into this reminder's calculation.
     */
    @Test
    fun `a sibling step's different offset never leaks into this reminder's own offset`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"task","parameters":{"title":"prep notes","raw_when":"45 minutes before"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"10 minutes before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, task, reminder))
            .handle("kal 16:00 meeting bana do, 45 minutes before prep notes ka task bana do, aur 10 minutes before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 50)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(
            "The reminder must use its own 10-minute offset, never the task step's 45-minute phrase",
            expected,
            reminder.calls.single().arguments["time"],
        )
    }

    @Test
    fun `an unsupported offset shape falls back to the existing 30-minute default rather than inventing a value`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"shortly before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder))
            .handle("kal 16:00 meeting bana do aur shortly before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 30)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    // -----------------------------------------------------------------
    // M3-C finalization — three-way default precedence for anchorToPriorEvent
    //
    // explicit request offset > stored user preference > 30-minute default.
    // The explicit-offset behavior itself (tested exhaustively above) is
    // unchanged — statedOffsetMillis still wins by construction, first in
    // the ?: chain, before preferredLeadMillis() is ever consulted.
    // -----------------------------------------------------------------

    @Test
    fun `an explicit request offset beats a stored preference`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val prefs = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger)
        prefs.save(UserPreferences(defaultReminderLeadMinutes = 15))
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"10 minutes before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder), persistentPreferenceStore = prefs)
            .handle("kal 16:00 meeting bana do aur 10 minutes before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 50)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    @Test
    fun `a stored default lead time is used when no explicit offset is stated`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val prefs = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger)
        prefs.save(UserPreferences(defaultReminderLeadMinutes = 15))
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder), persistentPreferenceStore = prefs)
            .handle("kal 16:00 meeting bana do aur reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 45)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(
            "With no explicit offset, the stored 15-minute preference must be used instead of the 30-minute default",
            expected,
            reminder.calls.single().arguments["time"],
        )
    }

    @Test
    fun `the 30-minute default applies when neither an explicit offset nor a stored preference exists`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val prefs = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger) // EMPTY — nothing saved
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder), persistentPreferenceStore = prefs)
            .handle("kal 16:00 meeting bana do aur reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 30)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
        assertEquals(
            "Falling back to the default must never write anything back as a user preference",
            UserPreferences.EMPTY,
            prefs.load(),
        )
    }

    @Test
    fun `an explicit 5-minute offset beats a stored 15-minute preference`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val prefs = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger)
        prefs.save(UserPreferences(defaultReminderLeadMinutes = 15))
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"5 minutes before"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder), persistentPreferenceStore = prefs)
            .handle("kal 16:00 meeting bana do aur 5 minutes before reminder bhi laga do")

        val expected = java.time.LocalDateTime.of(2026, 8, 7, 15, 55)
            .atZone(zone).toInstant().toEpochMilli().toString()
        assertEquals(expected, reminder.calls.single().arguments["time"])
    }

    @Test
    fun `clearing a stored preference restores the 30-minute default`() = runTest {
        val calendar = calendarTool() // start=2026-08-07T16:00
        val reminder = reminderTool()
        val prefs = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger)
        prefs.save(UserPreferences(defaultReminderLeadMinutes = 15))
        prefs.clear()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendar, reminder), persistentPreferenceStore = prefs)
            .handle("kal 16:00 meeting bana do aur reminder bhi laga do")

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
                    {"intent":"calendar_event","parameters":{"title":"meeting","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"meeting","raw_when":"15:30"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        val outcome = secretary.handle("kal 16:00 meeting bana do aur reminder laga do") as ToolOrchestrator.Outcome.Handled

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
                    {"intent":"calendar_event","parameters":{"title":"x","raw_when":"16:00"}},
                    {"intent":"reminder","parameters":{"title":"x","raw_when":"15:30"}}
                ]}""",
            ),
        )

        secretary(engine, listOf(calendarTool(), reminderTool()))
            .handle("kal 16:00 meeting bana do aur reminder laga do")

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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"person":"Abdul","title":"meeting","raw_when":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(ambiguousContactsTool(), calendar))
            .handle("Kal 16:00 Abdul ke saath meeting schedule kar do")

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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"person":"Ghost","title":"meeting","raw_when":"16:00"}}"""),
        )

        val outcome = secretary(engine, listOf(noMatch, calendar)).handle("meeting with Ghost tomorrow at 16:00")

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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 09:00")
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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 09:00")
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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup tomorrow at 09:00")
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
    // Calendar update/reschedule (Phase 5 — Calendar Update/Delete Reliability, Gap C)
    //
    // Calendar CREATE and CANCEL both had end-to-end orchestrator coverage;
    // UPDATE (reschedule) had none — update_event only ever appeared inside
    // calendarTool()'s own behaviour stub, never in an actual test scenario.
    // No confirmation gate applies to UPDATE (only CANCEL is in
    // DELETE_CONFIRMATION_TYPES), so these mirror the reminder-reschedule
    // shape (create, then a reference-only follow-up) rather than the
    // delete-confirmation shape.
    // -----------------------------------------------------------------

    @Test
    fun `rescheduling a calendar event resolves the remembered event and routes to update_event`() = runTest {
        val calendar = calendarTool()
        val fixedNow = { LocalDateTime.of(2026, 8, 10, 9, 0) }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"update","parameters":{"raw_when":"17:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar), temporalNow = fixedNow)

        secretary.handle("standup at 09:00")
        calendar.calls.clear()
        val outcome = secretary.handle("us meeting ko 17:00 kar do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.size)
        val call = calendar.calls.single()
        assertEquals("update_event", call.operation)
        // The id calendarTool()'s create_event stub returns — proves
        // ReferenceResolver actually grounded "us meeting" against the
        // event just created in memory, not a guess.
        assertEquals("500", call.arguments["id"])
        // The grounded, resolved new time — never the model's raw "17:00"
        // string — reaches the tool as a real epoch instant.
        assertEquals(
            LocalDateTime.of(2026, 8, 10, 17, 0).atZone(zone).toInstant().toEpochMilli().toString(),
            call.arguments["start"],
        )
        // Successful update reaches the expected final state: memory still
        // names the same event, not cleared or replaced.
        assertEquals("500", secretary.memory.value.lastCalendarEvent?.id?.toString())
    }

    /**
     * The Day 08-E invariant applied to this milestone, mirroring the
     * equivalent reminder-cancel regression test: a targetId is never
     * readable from the model's own JSON ([IntentJsonParser] maps no such
     * key), but this pins the end-to-end guarantee — even a model that
     * tries to smuggle an id-shaped value into `parameters` cannot make it
     * to the tool call. Only [ReferenceResolver]'s memory-grounded id ever
     * reaches `update_event`.
     */
    @Test
    fun `a model-supplied target id never survives calendar-update grounding`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success(
                """{"intent":"calendar_event","action_type":"update","parameters":{"raw_when":"17:00","target_id":"9999"}}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar))

        secretary.handle("standup at 09:00")
        calendar.calls.clear()
        secretary.handle("us meeting ko 17:00 kar do")

        assertEquals(1, calendar.calls.size)
        assertEquals(
            "The reference-resolved id must win over anything the model supplied",
            "500",
            calendar.calls.single().arguments["id"],
        )
    }

    // -----------------------------------------------------------------
    // Reminder cancel confirmation (Phase 5 — Reminder Cancel Confirmation)
    //
    // Reminder cancellation previously ran immediately once its target was
    // resolved — the one destructive action in this codebase with no "are
    // you sure." These tests mirror the calendar-delete tests immediately
    // above verbatim in shape: the same PendingConfirmation/ConfirmationParser
    // machinery, the same describeDeletionTarget/DELETE_TARGET_LABELS seam,
    // just DELETE_CONFIRMATION_TYPES now also covering IntentType.REMINDER.
    // -----------------------------------------------------------------

    @Test
    fun `deleting a reminder asks before doing anything`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(reminder))

        secretary.handle("remind me to call the bank at 16:00")
        reminder.calls.clear()
        val outcome = secretary.handle("mera reminder cancel kar do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("call the bank"))
        assertTrue(outcome.question.contains("can't be undone"))
        assertTrue("Nothing may be cancelled before confirmation", reminder.calls.isEmpty())
    }

    @Test
    fun `confirming a reminder cancellation executes it exactly once`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(reminder))

        secretary.handle("remind me to call the bank at 16:00")
        reminder.calls.clear()
        secretary.handle("mera reminder cancel kar do")
        val confirmed = secretary.handle("haan")

        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, reminder.calls.size)
        assertEquals("cancel_reminder", reminder.calls.single().operation)
        // The id created two turns ago — proves the grounded reference, not
        // anything the model said in the cancel turn itself, is what's cancelled.
        assertEquals("1001", reminder.calls.single().arguments["id"])
    }

    @Test
    fun `declining a reminder cancellation leaves it alone`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(reminder))

        secretary.handle("remind me to call the bank at 16:00")
        reminder.calls.clear()
        secretary.handle("mera reminder cancel kar do")
        val callsBeforeDecline = engine.index
        val declined = secretary.handle("nahi")

        assertTrue("Expected Handled, got $declined", declined is ToolOrchestrator.Outcome.Handled)
        assertTrue("Declining must not cancel anything", reminder.calls.isEmpty())
        assertEquals(
            "Declining a confirmation must not cost another inference pass.",
            callsBeforeDecline,
            engine.index,
        )
    }

    /**
     * The Day 08-E invariant applied to this milestone: a targetId is never
     * readable from the model's own JSON at all ([IntentJsonParser] maps no
     * such key), but this pins the end-to-end guarantee anyway — even a
     * model that tries to smuggle an id-shaped value into `parameters`
     * cannot make it to the tool call. Only [ReferenceResolver]'s
     * memory-grounded id ever reaches `cancel_reminder`.
     */
    @Test
    fun `a model-supplied target id never survives reminder-cancel grounding`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel","parameters":{"target_id":"9999"}}"""),
        )
        val secretary = secretary(engine, listOf(reminder))

        secretary.handle("remind me to call the bank at 16:00")
        reminder.calls.clear()
        secretary.handle("mera reminder cancel kar do")
        val confirmed = secretary.handle("haan")

        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, reminder.calls.size)
        assertEquals(
            "The reference-resolved id must win over anything the model supplied",
            "1001",
            reminder.calls.single().arguments["id"],
        )
    }

    @Test
    fun `a reminder cancellation with no resolvable target asks which one rather than guessing`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel","parameters":{}}"""),
        )

        val outcome = secretary(engine, listOf(reminder)).handle("reminder cancel kar do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("Which reminder"))
        assertTrue("Nothing may be cancelled without a resolved target", reminder.calls.isEmpty())
    }

    // -----------------------------------------------------------------
    // Calling (Contacts + Calling milestone) — grounded phone, explicit
    // confirmation, ACTION_DIAL only.
    //
    // Reuses the exact contact-grounding and PendingConfirmation machinery
    // already proven above for WhatsApp and calendar-delete; the only new
    // rule is *where* confirmation is asked (after the phone number is
    // grounded, before place_call ever runs) and that the grounded number
    // always wins over anything the model supplied directly.
    // -----------------------------------------------------------------

    @Test
    fun `an exact contact match asks to confirm before dialing`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Bilal"}}"""),
        )

        val outcome = secretary(engine, listOf(singleContactTool(), call)).handle("Bilal ko call laga do")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        val question = (outcome as ToolOrchestrator.Outcome.Clarify).question
        assertTrue(question.contains("Bilal Developer"))
        assertTrue(question.contains("+923001234567"))
        assertTrue("Nothing may dial before confirmation", call.calls.isEmpty())
    }

    @Test
    fun `confirming a call opens the dialer with the grounded number`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Bilal"}}"""),
        )
        val secretary = secretary(engine, listOf(singleContactTool(), call))

        secretary.handle("Bilal ko call laga do")
        val confirmed = secretary.handle("haan")

        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, call.calls.size)
        assertEquals("place_call", call.calls.single().operation)
        assertEquals("Bilal Developer", call.calls.single().arguments["contact"])
        assertEquals("+923001234567", call.calls.single().arguments["phone"])
    }

    @Test
    fun `declining a call leaves the dialer untouched`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Bilal"}}"""),
        )
        val secretary = secretary(engine, listOf(singleContactTool(), call))

        secretary.handle("Bilal ko call laga do")
        val declined = secretary.handle("nahi")

        assertTrue("Expected Handled, got $declined", declined is ToolOrchestrator.Outcome.Handled)
        assertTrue("Declining must never open the dialer", call.calls.isEmpty())
    }

    @Test
    fun `an ambiguous name asks which contact instead of guessing before ever asking to call`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Abdul"}}"""),
        )

        val outcome = secretary(engine, listOf(ambiguousContactsTool(), call)).handle("Abdul ko call karo")

        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("Abdul Rahman"))
        assertTrue(outcome.question.contains("Abdul Rauf"))
        assertTrue("Nothing may dial before disambiguation", call.calls.isEmpty())
    }

    @Test
    fun `picking a candidate by number still asks to confirm before dialing`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Abdul"}}"""),
        )
        val secretary = secretary(engine, listOf(ambiguousContactsTool(), call))

        secretary.handle("Abdul ko call karo")
        val outcome = secretary.handle("2")

        assertTrue("Expected Clarify (confirmation), got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue((outcome as ToolOrchestrator.Outcome.Clarify).question.contains("Abdul Rauf"))
        assertTrue(outcome.question.contains("+923002222222"))
        assertTrue(call.calls.isEmpty())
    }

    @Test
    fun `no matching contact reports the lookup failure rather than dialing anything`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Bilal"}}"""),
        )

        val outcome = secretary(engine, listOf(noContactTool(), call)).handle("Bilal ko call laga do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertTrue((outcome as ToolOrchestrator.Outcome.Handled).reply.contains("No contact named"))
        assertTrue(call.calls.isEmpty())
    }

    /**
     * A resolved contact with no phone number on file must never reach
     * confirmation or the dialer — and the tool itself reports why, exactly
     * like [PrepareWhatsAppMessageTool]'s equivalent case.
     */
    @Test
    fun `a contact with no phone number never reaches confirmation or the dialer`() = runTest {
        val call = callTool {
            ToolResult.Failure("${it.arguments["contact"]} has no phone number saved.", retryable = false)
        }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"call_contact","parameters":{"person":"Bilal"}}"""),
        )

        val outcome = secretary(engine, listOf(singleContactTool(phone = null), call)).handle("Bilal ko call laga do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertTrue((outcome as ToolOrchestrator.Outcome.Handled).reply.contains("no phone number"))
        // The tool itself is the one reporting the failure, so it is called
        // once with no phone argument — never skipped, never dialed blind.
        assertEquals(1, call.calls.size)
        assertNull(call.calls.single().arguments["phone"])
    }

    /**
     * The critical safety invariant for this milestone: a phone number the
     * model supplies directly alongside a resolved name must never survive
     * grounding. Bilal's real, contact-sourced number must reach the tool —
     * never the model's fabricated one, even though the model emitted a
     * syntactically-plausible-looking number of its own.
     */
    @Test
    fun `a model-supplied phone number never survives contact grounding`() = runTest {
        val call = callTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"intent":"call_contact","parameters":{"person":"Bilal","phone":"+92000FAKE0000"}}""",
            ),
        )
        val secretary = secretary(engine, listOf(singleContactTool(), call))

        secretary.handle("Bilal ko call laga do")
        val confirmed = secretary.handle("haan")

        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, call.calls.size)
        assertEquals(
            "The resolved contact's real number must win over the model's own value",
            "+923001234567",
            call.calls.single().arguments["phone"],
        )
    }

    // -----------------------------------------------------------------
    // Follow-up suggestions (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `a created calendar event offers a reminder suggestion in the same reply`() = runTest {
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendarTool()))

        val outcome = secretary.handle("standup tomorrow at 09:00") as ToolOrchestrator.Outcome.Handled

        assertTrue(outcome.reply.contains("reminder"))
        assertEquals(SecretaryState.WAITING_CONFIRMATION, secretary.state.value)
    }

    @Test
    fun `accepting a suggestion executes it without another inference pass`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        secretary.handle("standup tomorrow at 09:00")
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
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"pay rent","raw_when":"18:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        secretary.handle("standup tomorrow at 09:00")
        val continued = secretary.handle("remind me to pay rent at 18:00")

        assertTrue("Expected Handled, got $continued", continued is ToolOrchestrator.Outcome.Handled)
        assertEquals(2, engine.index)
        assertEquals("pay rent", reminder.calls.single().arguments["title"])
    }

    // -----------------------------------------------------------------
    // Type disambiguation (Day 09, Option 1)
    //
    // The Calendar Delete/Update Classification Reliability investigation
    // found the model unreliable specifically for non-CREATE requests
    // naming an existing item — these reproduce the four example flows from
    // the approved design against a real (scripted) classification, exactly
    // as the live-device investigation observed them.
    // -----------------------------------------------------------------

    @Test
    fun `delete standup meeting - a notification-cancel misclassification redirects to the remembered calendar event`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))
        secretary.handle("standup meeting tomorrow at 16:00")

        val asked = secretary.handle("delete the standup meeting")
        assertTrue("Expected a redirect question, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        assertTrue(
            "Question should name the remembered event, got: ${(asked as ToolOrchestrator.Outcome.Clarify).question}",
            asked.question.contains("standup meeting"),
        )
        assertEquals(SecretaryState.WAITING_TYPE_DISAMBIGUATION, secretary.state.value)

        val confirmedRedirect = secretary.handle("yes")
        assertTrue(
            "Redirect confirmed must still hit the delete-confirmation gate, got $confirmedRedirect",
            confirmedRedirect is ToolOrchestrator.Outcome.Clarify,
        )
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })

        val confirmedDelete = secretary.handle("yes")
        assertTrue("Expected Handled, got $confirmedDelete", confirmedDelete is ToolOrchestrator.Outcome.Handled)
        assertEquals("500", calendar.calls.single { it.operation == "delete_event" }.arguments["id"])
        // Only two real inference calls: the create and the one misclassification.
        // Both confirmations resolved deterministically, with no further model call.
        assertEquals(2, engine.index)
    }

    @Test
    fun `reschedule standup meeting to 11am - a reminder-update misclassification redirects to the remembered calendar event`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","action_type":"update","parameters":{"raw_when":"11:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar))
        secretary.handle("standup meeting tomorrow at 16:00")

        val asked = secretary.handle("reschedule the standup meeting to 11:00")
        assertTrue("Expected a redirect question, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        assertTrue((asked as ToolOrchestrator.Outcome.Clarify).question.contains("standup meeting"))

        val confirmed = secretary.handle("yes")
        assertTrue("Expected Handled, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals("500", calendar.calls.single { it.operation == "update_event" }.arguments["id"])
    }

    @Test
    fun `cancel that reminder - a notification-cancel misclassification redirects to the remembered reminder`() = runTest {
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(reminder))
        secretary.handle("remind me to call the bank at 16:00")

        val asked = secretary.handle("cancel that reminder")
        assertTrue("Expected a redirect question, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        assertTrue((asked as ToolOrchestrator.Outcome.Clarify).question.contains("call the bank"))

        secretary.handle("yes")
        val confirmedCancel = secretary.handle("yes")

        assertTrue("Expected Handled, got $confirmedCancel", confirmedCancel is ToolOrchestrator.Outcome.Handled)
        assertEquals("1001", reminder.calls.single { it.operation == "cancel_reminder" }.arguments["id"])
    }

    @Test
    fun `delete that meeting - no memory means no candidate, and the existing generic question is unchanged`() = runTest {
        val notification = RecordingTool(ToolId.NOTIFICATION, setOf("notify", "cancel")) {
            ToolResult.Success("Done.", mapOf("notification_id" to "1"))
        }
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""))
        val secretary = secretary(engine, listOf(notification))

        val outcome = secretary.handle("delete that meeting")

        // Nothing in memory to redirect toward — falls through to exactly
        // what ClarificationEngine already asked before this design existed.
        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertEquals("What should the notification say?", (outcome as ToolOrchestrator.Outcome.Clarify).question)
        assertEquals(SecretaryState.WAITING_MISSING_INFORMATION, secretary.state.value)
        assertEquals(0, notification.calls.size)
    }

    @Test
    fun `two plausible candidates are offered as a numbered choice, never guessed`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"17:00"}}"""),
            DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))
        secretary.handle("standup meeting tomorrow at 16:00")
        secretary.handle("remind me to call the bank at 17:00")

        val asked = secretary.handle("cancel it")
        assertTrue("Expected a numbered choice, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        val question = (asked as ToolOrchestrator.Outcome.Clarify).question
        assertTrue(question.contains("1)"))
        assertTrue(question.contains("2)"))
        assertTrue(question.contains("standup meeting"))
        assertTrue(question.contains("call the bank"))

        val picked = secretary.handle("2")
        assertTrue("Picking the reminder must still hit its own delete confirmation, got $picked", picked is ToolOrchestrator.Outcome.Clarify)
        secretary.handle("yes")

        assertEquals(1, reminder.calls.count { it.operation == "cancel_reminder" })
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })
    }

    @Test
    fun `an unparseable or declined redirect falls through to the message as a fresh request`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"notification","action_type":"cancel","parameters":{}}"""),
            // What the fallthrough message itself classifies as — proves it
            // actually reached the model as a fresh request, not a swallowed answer.
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"pay rent","raw_when":"18:00"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))
        secretary.handle("standup meeting tomorrow at 16:00")
        secretary.handle("delete the standup meeting")

        val fallthrough = secretary.handle("actually, remind me to pay rent at 18:00")

        assertTrue("Expected Handled, got $fallthrough", fallthrough is ToolOrchestrator.Outcome.Handled)
        assertEquals(3, engine.index)
        assertEquals("pay rent", reminder.calls.single().arguments["title"])
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })
    }

    @Test
    fun `a correctly resolved reminder cancel is never second-guessed even when a calendar event is also remembered`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"17:00"}}"""),
            // "that reminder" is an existing ReferenceResolver cue — targetId
            // resolves correctly on its own, with no ambiguity at all.
            DpsResult.Success("""{"intent":"reminder","action_type":"cancel"}"""),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))
        secretary.handle("standup meeting tomorrow at 16:00")
        secretary.handle("remind me to call the bank at 17:00")

        val asked = secretary.handle("cancel that reminder")

        assertTrue("Expected the reminder's own delete confirmation, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        assertTrue((asked as ToolOrchestrator.Outcome.Clarify).question.contains("call the bank"))
        assertEquals(SecretaryState.WAITING_CONFIRMATION, secretary.state.value)

        secretary.handle("yes")
        assertEquals(1, reminder.calls.count { it.operation == "cancel_reminder" })
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })
    }

    @Test
    fun `a fresh create is never redirected even with memory populated`() = runTest {
        val reminder = reminderTool()
        val calendar = calendarTool { call ->
            when (call.operation) {
                "create_event" -> ToolResult.Success(
                    "Added to your calendar.",
                    mapOf("event_id" to "501", "calendar" to "Personal", "start" to "2026-08-08T10:00", "end" to "2026-08-08T11:00"),
                )
                else -> ToolResult.Unsupported("not implemented")
            }
        }
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"17:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"create","parameters":{"title":"lunch","raw_when":"10:00"}}"""),
        )
        val secretary = secretary(engine, listOf(reminder, calendar))
        secretary.handle("remind me to call the bank at 17:00")

        val outcome = secretary.handle("schedule lunch at 10:00")

        assertTrue("A fresh CREATE must never be redirected, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.count { it.operation == "create_event" })
    }

    @Test
    fun `a list request is never redirected even with memory populated`() = runTest {
        val calendar = calendarTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}}"""),
            DpsResult.Success("""{"intent":"task","action_type":"list"}"""),
        )
        val secretary = secretary(engine, listOf(calendar, task))
        secretary.handle("standup meeting tomorrow at 16:00")

        val outcome = secretary.handle("show my pending tasks")

        assertTrue(
            "LIST must never be intercepted with a redirect question, got $outcome",
            outcome !is ToolOrchestrator.Outcome.Clarify,
        )
    }

    // -----------------------------------------------------------------
    // CAT5 fix (Day 09 follow-up) — bare "move"/"change" corrected via
    // ActionDetector's new anchored phrases, then redirected by the
    // already-committed Option 1 mechanism above. This reproduces the
    // real-device investigation's exact raw model output byte-for-byte.
    // -----------------------------------------------------------------

    @Test
    fun `move that meeting is corrected to update and redirected to the real calendar event`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"10:00"}}"""),
            // Byte-for-byte the real on-device evidence for "Move that
            // meeting to tomorrow evening." — reminder/create, the whole
            // sentence dumped into message, no title.
            DpsResult.Success(
                """{"intent":"reminder","action_type":"create","parameters":{"raw_when":"tomorrow evening","message":"Move that meeting to tomorrow evening."}}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar))
        secretary.handle("standup meeting tomorrow at 10:00")

        val redirected = secretary.handle("Move that meeting to tomorrow evening.")

        assertTrue("Expected a redirect to the real calendar event, got $redirected", redirected is ToolOrchestrator.Outcome.Clarify)
        assertTrue(
            "Question must name the real event, never ask about a nonexistent reminder, got: ${(redirected as ToolOrchestrator.Outcome.Clarify).question}",
            redirected.question.contains("standup meeting"),
        )
        assertEquals(SecretaryState.WAITING_TYPE_DISAMBIGUATION, secretary.state.value)

        val confirmed = secretary.handle("yes")

        assertTrue("Expected Handled after confirming the redirect, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals("500", calendar.calls.single { it.operation == "update_event" }.arguments["id"])
    }

    @Test
    fun `change that meeting is corrected to update the same way`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"10:00"}}"""),
            DpsResult.Success(
                """{"intent":"reminder","action_type":"create","parameters":{"raw_when":"tomorrow evening","message":"Change that meeting to tomorrow evening."}}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar))
        secretary.handle("standup meeting tomorrow at 10:00")

        val redirected = secretary.handle("Change that meeting to tomorrow evening.")

        assertTrue("Expected a redirect to the real calendar event, got $redirected", redirected is ToolOrchestrator.Outcome.Clarify)
        assertTrue((redirected as ToolOrchestrator.Outcome.Clarify).question.contains("standup meeting"))

        val confirmed = secretary.handle("yes")
        assertTrue("Expected Handled after confirming the redirect, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals("500", calendar.calls.single { it.operation == "update_event" }.arguments["id"])
    }

    @Test
    fun `move as ordinary create content is never redirected`() = runTest {
        val reminder = reminderTool()
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"10:00"}}"""),
            // "move" appears only as ordinary CREATE content — must stay CREATE.
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"move the couch","raw_when":"18:00"}}"""),
        )
        val secretary = secretary(engine, listOf(reminder, calendar))
        secretary.handle("standup meeting tomorrow at 10:00")

        val outcome = secretary.handle("remind me to move the couch at 18:00")

        assertTrue("A CREATE mentioning 'move' as content must never be redirected, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, reminder.calls.count { it.operation == "create_reminder" })
        assertEquals(0, calendar.calls.count { it.operation == "update_event" })
    }

    // -----------------------------------------------------------------
    // M2-B — Option 1 type disambiguation reachable from multi-step plans
    //
    // handlePlan's per-step loop now consults the exact same
    // disambiguationCandidates/askTypeDisambiguation Option 1 already uses
    // from handleSingleStep — a second call site for the identical,
    // unmodified mechanism, exercised here entirely within one compound
    // (multi-step) message.
    // -----------------------------------------------------------------

    @Test
    fun `an ambiguous notification-cancel step inside a plan redirects to a calendar event created earlier in the same plan`() = runTest {
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"notification","action_type":"cancel","parameters":{}}
                ]}""",
            ),
        )

        val secretary = secretary(engine, listOf(calendar))
        val outcome = secretary.handle("standup meeting tomorrow at 16:00 aur phir delete the standup meeting")

        assertTrue("Expected a redirect question, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        val question = (outcome as ToolOrchestrator.Outcome.Clarify).question
        assertTrue("Question must name the real event, got: $question", question.contains("standup meeting"))
        assertTrue(
            "Must never fall back to the old generic notification question inside a plan",
            !question.contains("What should the notification say"),
        )
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })

        // Functional proof that pendingTypeDisambiguation — not the calendar
        // create's own leftover follow-up-suggestion confirmation — is what
        // the next turn resolves: confirming must route into the delete
        // confirmation for the *redirected* event, not be misread as an
        // answer to the abandoned "add a reminder for this?" suggestion
        // (which would silently execute a different, unwanted action).
        val confirmedRedirect = secretary.handle("yes")
        assertTrue(
            "Confirming the redirect must hit the calendar event's own delete confirmation, got $confirmedRedirect",
            confirmedRedirect is ToolOrchestrator.Outcome.Clarify,
        )
        assertTrue((confirmedRedirect as ToolOrchestrator.Outcome.Clarify).question.contains("standup meeting"))
    }

    @Test
    fun `an ambiguous step inside a plan with no remembered candidate falls through to the existing generic question`() = runTest {
        val whatsapp = whatsAppTool()
        val notification = RecordingTool(ToolId.NOTIFICATION, setOf("notify", "cancel")) {
            ToolResult.Success("Done.", mapOf("notification_id" to "1"))
        }
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"whatsapp_message","parameters":{"person":"Ali","message":"on my way"}},
                    {"intent":"notification","action_type":"cancel","parameters":{}}
                ]}""",
            ),
        )

        val outcome = secretary(engine, listOf(whatsapp, notification))
            .handle("Ali ko WhatsApp kar do on my way aur phir notification cancel kar do")

        // whatsapp_message never touches lastCalendarEvent/lastReminder/lastTask,
        // so no candidate exists — Option 1 must not fabricate one.
        assertTrue("Expected Clarify, got $outcome", outcome is ToolOrchestrator.Outcome.Clarify)
        assertTrue(
            "No memory candidate exists, so the pre-M2-B generic question must be unchanged " +
                "(prefixed with step 1's own reply, exactly like every other handlePlan block already does)",
            (outcome as ToolOrchestrator.Outcome.Clarify).question.endsWith("What should the notification say?"),
        )
        assertEquals(0, notification.calls.size)
    }

    @Test
    fun `a title-addressed task step inside a plan is executed normally and never second-guessed`() = runTest {
        val calendar = calendarTool()
        // taskTool()'s shared helper only declares "create_task" — this
        // step needs "complete_task" recognised by the registry, so it is
        // built directly rather than reusing that CREATE-only default.
        val task = RecordingTool(ToolId.TASK, setOf("create_task", "complete_task")) {
            ToolResult.Success("Task \"${it.arguments["title"]}\" completed.", mapOf("task_id" to "1"))
        }
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"task","action_type":"complete","parameters":{"title":"DBPMS wala kaam"}}
                ]}""",
            ),
        )

        val outcome = secretary(engine, listOf(calendar, task))
            .handle("standup meeting tomorrow at 16:00 aur DBPMS wala kaam complete kar do")

        assertTrue(
            "A title-addressed, already-complete task step must run normally, got $outcome",
            outcome is ToolOrchestrator.Outcome.Handled,
        )
        assertEquals(
            "DBPMS wala kaam",
            task.calls.single { it.operation == "complete_task" }.arguments["title"],
        )
        assertEquals(0, calendar.calls.count { it.operation == "update_event" || it.operation == "delete_event" })
    }

    @Test
    fun `two plausible candidates inside a plan still use the existing numbered-choice mechanism`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"17:00"}}"""),
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"notification","action_type":"cancel","parameters":{}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))
        // A prior, separate turn remembers a reminder...
        secretary.handle("remind me to call the bank at 17:00")
        // ...then a plan created in this turn also remembers a calendar event.
        val asked = secretary.handle("standup meeting tomorrow at 16:00 aur phir cancel it")

        assertTrue("Expected a numbered choice, got $asked", asked is ToolOrchestrator.Outcome.Clarify)
        val question = (asked as ToolOrchestrator.Outcome.Clarify).question
        assertTrue(question.contains("1)"))
        assertTrue(question.contains("2)"))
        assertTrue(question.contains("standup meeting"))
        assertTrue(question.contains("call the bank"))

        // Reuses the existing, unmodified numbered-index parser (resolveTypeDisambiguation) — no new matching logic.
        val picked = secretary.handle("2")
        assertTrue("Picking the reminder must still hit its own delete confirmation, got $picked", picked is ToolOrchestrator.Outcome.Clarify)
        secretary.handle("yes")

        assertEquals(1, reminder.calls.count { it.operation == "cancel_reminder" })
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })
    }

    // -----------------------------------------------------------------
    // M2-C — PendingPlan and multi-step plan resumption
    //
    // Before M2-C, a block mid-plan resumed only the one blocked step;
    // everything after it was silently dropped. These prove the remainder
    // now survives a clarification, a confirmation, a contact selection,
    // and a Day 09 Option 1 redirect — including re-blocking a second time
    // — and that a fully completed plan leaves nothing behind to leak into
    // an unrelated later message.
    // -----------------------------------------------------------------

    @Test
    fun `clarification mid-plan resumes the preserved remainder`() = runTest {
        // A reminder step is deliberately NOT used to block here — placed
        // immediately after a calendar_event step, a bodiless reminder is
        // auto-completed by the pre-existing anchorToPriorEvent default
        // (M2-A), never actually reaching Missing. whatsapp_message with a
        // person but no message text blocks for a genuinely unrelated
        // reason, with no such interaction.
        val calendar = calendarTool()
        val whatsapp = whatsAppTool()
        val task = taskTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"whatsapp_message","parameters":{"person":"Ali"}},
                    {"intent":"task","parameters":{"title":"send agenda"}}
                ]}""",
            ),
            // The answer to step 2's "what would you like the message to say?" question.
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"message":"on my way"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, whatsapp, task))

        val blocked = secretary.handle("standup meeting tomorrow at 16:00, Ali ko WhatsApp kar do, aur agenda bhejne ka task bana do")

        assertTrue("Expected Clarify asking about step 2's message, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)
        assertEquals(1, calendar.calls.count { it.operation == "create_event" })
        assertEquals(0, whatsapp.calls.size)
        assertEquals(0, task.calls.size)

        val resumed = secretary.handle("on my way")

        assertTrue("Expected Handled once step 2 is answered, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, whatsapp.calls.size)
        assertEquals(
            "Step 3 must run after the resumed step 2, not be silently dropped",
            1,
            task.calls.count { it.operation == "create_task" },
        )
    }

    @Test
    fun `confirmation mid-plan resumes the preserved remainder`() = runTest {
        val calendar = calendarTool()
        // taskTool()'s shared helper only declares "create_task" (see the
        // M2-B lesson) — this step needs "cancel_task" recognised too.
        val task = RecordingTool(ToolId.TASK, setOf("create_task", "cancel_task")) {
            ToolResult.Success("Task \"${it.arguments["title"]}\" cancelled.", mapOf("task_id" to (it.arguments["id"] ?: "1")))
        }
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"task","action_type":"cancel","parameters":{"title":"old task"}},
                    {"intent":"reminder","parameters":{"title":"followup","raw_when":"18:00"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, task, reminder))

        val blocked = secretary.handle("standup meeting tomorrow at 16:00, cancel the old task, aur followup reminder 18:00 pe laga do")

        assertTrue("Expected the delete confirmation for step 2, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, task.calls.count { it.operation == "cancel_task" })
        assertEquals(0, reminder.calls.size)

        val resumed = secretary.handle("yes")

        assertTrue("Expected Handled once the confirmation is answered, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, task.calls.count { it.operation == "cancel_task" })
        assertEquals(
            "Step 3 must run after the confirmed step 2, not be silently dropped",
            1,
            reminder.calls.count { it.operation == "create_reminder" },
        )
    }

    @Test
    fun `declining a confirmation mid-plan stops the plan rather than continuing unattended`() = runTest {
        val calendar = calendarTool()
        val task = taskTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"task","action_type":"cancel","parameters":{"title":"old task"}},
                    {"intent":"reminder","parameters":{"title":"followup","raw_when":"18:00"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, task, reminder))
        secretary.handle("standup meeting tomorrow at 16:00, cancel the old task, aur followup reminder 18:00 pe laga do")

        val declined = secretary.handle("no")

        assertTrue("Expected Handled reporting the decline, got $declined", declined is ToolOrchestrator.Outcome.Handled)
        assertEquals(0, task.calls.count { it.operation == "cancel_task" })
        assertEquals(
            "A declined destructive step must not be followed by unattended further execution",
            0,
            reminder.calls.count { it.operation == "create_reminder" },
        )
    }

    @Test
    fun `contact selection mid-plan resumes the preserved remainder`() = runTest {
        val calendar = calendarTool()
        val whatsapp = whatsAppTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"whatsapp_message","parameters":{"person":"Abdul","message":"on my way"}},
                    {"intent":"reminder","parameters":{"title":"followup","raw_when":"18:00"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, ambiguousContactsTool(), whatsapp, reminder))

        val blocked = secretary.handle("standup meeting tomorrow at 16:00, Abdul ko WhatsApp kar do on my way, aur followup reminder 18:00 pe laga do")

        assertTrue("Expected the contact-selection question, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, whatsapp.calls.size)
        assertEquals(0, reminder.calls.size)

        val resumed = secretary.handle("1")

        assertTrue("Expected Handled once a contact is picked, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, whatsapp.calls.size)
        assertEquals(
            "Step 3 must run after the resolved step 2, not be silently dropped",
            1,
            reminder.calls.count { it.operation == "create_reminder" },
        )
    }

    @Test
    fun `type disambiguation mid-plan resumes the preserved remainder, through its own re-block into a delete confirmation`() = runTest {
        val calendar = calendarTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"notification","action_type":"cancel","parameters":{}},
                    {"intent":"reminder","parameters":{"title":"followup","raw_when":"18:00"}}
                ]}""",
            ),
        )
        val secretary = secretary(engine, listOf(calendar, reminder))

        val redirect = secretary.handle("standup meeting tomorrow at 16:00, delete the standup meeting, aur followup reminder 18:00 pe laga do")

        assertTrue("Expected the Option 1 redirect for step 2, got $redirect", redirect is ToolOrchestrator.Outcome.Clarify)
        assertTrue((redirect as ToolOrchestrator.Outcome.Clarify).question.contains("standup meeting"))

        // Confirming the redirect still hits the calendar event's own
        // delete-confirmation gate (M2-B's existing behaviour) — a
        // re-block, not a completion; step 3 must not run yet.
        val reBlocked = secretary.handle("yes")
        assertTrue("Expected the delete confirmation, got $reBlocked", reBlocked is ToolOrchestrator.Outcome.Clarify)
        assertEquals(0, reminder.calls.size)
        assertEquals(0, calendar.calls.count { it.operation == "delete_event" })

        val resumed = secretary.handle("yes")

        assertTrue("Expected Handled once the delete is confirmed, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, calendar.calls.count { it.operation == "delete_event" })
        assertEquals(
            "Step 3 must run after the redirected-and-confirmed step 2, not be silently dropped",
            1,
            reminder.calls.count { it.operation == "create_reminder" },
        )
    }

    @Test
    fun `re-blocking twice in the same plan resumes correctly both times with nothing skipped or duplicated`() = runTest {
        // Step 2 uses whatsapp_message rather than a bodiless reminder — see
        // the "clarification mid-plan" test's comment for why a reminder
        // directly after a calendar_event step would be auto-completed by
        // anchorToPriorEvent (M2-A) instead of genuinely blocking.
        val calendar = calendarTool()
        val whatsapp = whatsAppTool()
        val reminder = reminderTool()
        // taskTool()'s shared helper only declares "create_task" — this
        // step needs "cancel_task" recognised too (see the M2-B lesson).
        val task = RecordingTool(ToolId.TASK, setOf("create_task", "cancel_task")) {
            ToolResult.Success("Task \"${it.arguments["title"]}\" cancelled.", mapOf("task_id" to (it.arguments["id"] ?: "1")))
        }
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"standup meeting","raw_when":"16:00"}},
                    {"intent":"whatsapp_message","parameters":{"person":"Ali"}},
                    {"intent":"task","action_type":"cancel","parameters":{"title":"old task"}},
                    {"intent":"reminder","parameters":{"title":"followup2","raw_when":"19:00"}}
                ]}""",
            ),
            DpsResult.Success("""{"intent":"whatsapp_message","parameters":{"message":"on my way"}}"""),
        )
        val secretary = secretary(engine, listOf(calendar, whatsapp, reminder, task))

        val firstBlock = secretary.handle(
            "standup meeting tomorrow at 16:00, Ali ko WhatsApp kar do, cancel the old task, aur followup2 reminder 19:00 pe laga do",
        )
        assertTrue("Expected step 2's clarification, got $firstBlock", firstBlock is ToolOrchestrator.Outcome.Clarify)

        val secondBlock = secretary.handle("on my way")
        assertTrue("Expected step 3's delete confirmation, got $secondBlock", secondBlock is ToolOrchestrator.Outcome.Clarify)
        // Step 2 ran exactly once resolving the first block; step 3 has not run yet.
        assertEquals(1, whatsapp.calls.size)
        assertEquals(0, task.calls.size)

        val completed = secretary.handle("yes")

        assertTrue("Expected Handled once step 3 is confirmed, got $completed", completed is ToolOrchestrator.Outcome.Handled)
        assertEquals(1, task.calls.count { it.operation == "cancel_task" })
        assertEquals(
            "Step 4 must run after the second re-block resolves, not be silently dropped",
            1,
            reminder.calls.count { it.operation == "create_reminder" && it.arguments["title"] == "followup2" },
        )
        // Nothing ran twice across the two resumptions.
        assertEquals(1, calendar.calls.count { it.operation == "create_event" })
        assertEquals(1, whatsapp.calls.size)
        assertEquals(1, reminder.calls.count { it.operation == "create_reminder" })
        assertEquals(1, task.calls.count { it.operation == "cancel_task" })
    }

    @Test
    fun `a fully completed plan leaves no pending state for an unrelated later message to continue`() = runTest {
        // Deliberately neither CALENDAR_EVENT nor REMINDER creates here —
        // both trigger FollowUpSuggestionGenerator's own, pre-existing,
        // unrelated follow-up suggestion on success, which would leave its
        // own dangling pendingConfirmation and defeat this test's actual
        // point (verifying PendingPlan itself leaves nothing behind).
        val task = taskTool()
        val whatsapp = whatsAppTool()
        val reminder = reminderTool()
        val engine = ScriptedEngine(
            DpsResult.Success(
                """{"steps":[
                    {"intent":"task","parameters":{"title":"kickoff"}},
                    {"intent":"whatsapp_message","parameters":{"person":"Ali","message":"on my way"}}
                ]}""",
            ),
            // An entirely unrelated single-step request sent afterward.
            DpsResult.Success("""{"intent":"reminder","parameters":{"title":"buy milk","raw_when":"20:00"}}"""),
        )
        val secretary = secretary(engine, listOf(task, whatsapp, reminder))

        val completed = secretary.handle("kickoff ka task bana do aur Ali ko WhatsApp kar do on my way")
        assertTrue("Expected the plan to complete fully, got $completed", completed is ToolOrchestrator.Outcome.Handled)
        assertNull("Nothing should be pending once a plan fully completes", secretary.pendingQuestion())

        val unrelated = secretary.handle("remind me to buy milk at 20:00")

        assertTrue("Expected the unrelated request to run on its own, got $unrelated", unrelated is ToolOrchestrator.Outcome.Handled)
        // Exactly one reminder — the unrelated message's own — never a
        // phantom continuation of the already-finished plan (which had no
        // reminder step at all).
        assertEquals(1, reminder.calls.count { it.operation == "create_reminder" })
        assertEquals("buy milk", reminder.calls.single().arguments["title"])
    }

    // -----------------------------------------------------------------
    // M3-B: PersistentMemoryStore wiring
    //
    // These assert against the *store's* contents (store.load()), not just
    // orchestrator.memory.value — proving the write actually reached durable
    // storage, not merely the in-memory StateFlow every other test above
    // already exercises.
    // -----------------------------------------------------------------

    @Test
    fun `startup loads whatever memory was already persisted`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = PersistentMemoryStore(prefs, silentLogger)
        val existing = ConversationMemory(
            lastTask = TaskMemory(id = 7, title = "kickoff"),
            lastReferencedPerson = "Ali",
            updatedAtMillis = 500L,
        )
        store.save(existing)

        val orchestrator = secretary(
            ScriptedEngine(DpsResult.Success("""{"intent":"conversation"}""")),
            listOf(taskTool()),
            persistentMemoryStore = store,
        )

        assertEquals(existing, orchestrator.memory.value)
    }

    @Test
    fun `a successful action persists the updated memory to the store`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = PersistentMemoryStore(prefs, silentLogger)
        val task = taskTool()
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"task","parameters":{"title":"kickoff"}}"""))
        val orchestrator = secretary(engine, listOf(task), persistentMemoryStore = store)

        orchestrator.handle("kickoff ka task bana do")

        assertEquals(1, store.load().lastTask?.id)
        assertEquals("kickoff", store.load().lastTask?.title)
    }

    @Test
    fun `a failed action leaves the persisted memory untouched`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = PersistentMemoryStore(prefs, silentLogger)
        val known = ConversationMemory(lastTask = TaskMemory(id = 1, title = "kickoff"), updatedAtMillis = 1L)
        store.save(known)

        val failingReminder = reminderTool { ToolResult.Failure("boom", retryable = false) }
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"reminder","parameters":{"title":"x","raw_when":"16:00"}}"""))
        val orchestrator = secretary(engine, listOf(failingReminder), persistentMemoryStore = store)

        val outcome = orchestrator.handle("remind me at 16:00")

        assertTrue("Expected the tool failure to surface as Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
        assertEquals("A failed action must not change what's durably remembered", known, store.load())
    }

    @Test
    fun `a declined confirmation leaves the persisted memory untouched`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = PersistentMemoryStore(prefs, silentLogger)
        val calendar = calendarTool()
        val engine = ScriptedEngine(
            DpsResult.Success("""{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}"""),
            DpsResult.Success("""{"intent":"calendar_event","action_type":"cancel","parameters":{}}"""),
        )
        val orchestrator = secretary(engine, listOf(calendar), persistentMemoryStore = store)

        orchestrator.handle("standup tomorrow at 09:00")
        val afterCreate = store.load()
        orchestrator.handle("us meeting ko delete kar do")
        val declined = orchestrator.handle("nahi")

        assertTrue("Expected Handled reporting the decline, got $declined", declined is ToolOrchestrator.Outcome.Handled)
        assertTrue("Declining must not delete anything", calendar.calls.none { it.operation == "delete_event" })
        assertEquals(
            "A declined destructive action must leave durable memory exactly as it was before the decline",
            afterCreate,
            store.load(),
        )
    }

    @Test
    fun `reset clears the persisted memory, not merely the in-memory copy`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = PersistentMemoryStore(prefs, silentLogger)
        val task = taskTool()
        val engine = ScriptedEngine(DpsResult.Success("""{"intent":"task","parameters":{"title":"kickoff"}}"""))
        val orchestrator = secretary(engine, listOf(task), persistentMemoryStore = store)

        orchestrator.handle("kickoff ka task bana do")
        assertEquals(1, store.load().lastTask?.id)

        orchestrator.reset()

        assertEquals(ConversationMemory.EMPTY, orchestrator.memory.value)
        assertEquals(ConversationMemory.EMPTY, store.load())
    }
}
