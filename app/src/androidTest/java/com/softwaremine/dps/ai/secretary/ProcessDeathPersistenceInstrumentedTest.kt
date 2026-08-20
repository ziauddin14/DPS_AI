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
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.preferences.UserPreferences
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3-D: real Android process-death validation for M3-A/M3-B/M3-C's
 * persistence wiring.
 *
 * ## Why two `@Test` methods, not one
 * A single instrumented test method cannot outlive its own process, so the
 * only honest way to prove "written in process A, read in process B" is to
 * split writing and reading into two separate JUnit test methods and kill
 * the app process between them from *outside* the JVM entirely, via ADB —
 * not a trick performed by Kotlin code running inside the process being
 * killed:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest#phase1WriteRealStateBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest#phase2VerifyRestoredStateAfterProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `am force-stop` genuinely terminates the OS process — unlike a fake
 * in-process `reset()` call, recreating an `Activity`, or constructing a
 * second `AiContainer` inside the same still-running process (all explicitly
 * insufficient per the M3-D brief) — while leaving on-disk
 * `SharedPreferences` files untouched (`pm clear` would wipe them too; this
 * deliberately never runs that). Running this whole class in one
 * `am instrument` invocation (e.g. Android Studio's "Run Tests") still
 * executes both methods as a compile/logic smoke check, but *without* a real
 * process death between them — phase2's own assertions would then only be
 * proving same-process disk round-tripping, already covered by M3-B/M3-C's
 * own tests. The DAY-10-M3-D-COMPLETION doc records the result of the actual
 * two-invocation, real-kill run this class exists for.
 *
 * ## What phase1 leaves behind, deliberately
 * Phase1 does not clean up its own task/preference — that state is exactly
 * what phase2 needs to still find after the kill. Phase2 does the cleanup
 * for both, once it has finished asserting against what survived.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathPersistenceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays a fixed script of classifications, one per [handle] call — mirrors every other file in this suite. */
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

    /**
     * A [SecretaryOrchestrator] against the real tool layer ([container]'s
     * real `toolExecutor`/`toolRegistry`) and whichever [PersistentMemoryStore]/
     * [PersistentPreferenceStore] the caller passes — the model alone is
     * scripted, for the same determinism reason every other instrumented
     * test in this suite scripts it.
     */
    private fun scriptedSecretary(
        vararg classifications: String,
        persistentMemoryStore: PersistentMemoryStore,
        persistentPreferenceStore: PersistentPreferenceStore,
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
            persistentMemoryStore = persistentMemoryStore,
            persistentPreferenceStore = persistentPreferenceStore,
            logger = silentLogger,
        )
    }

    // -----------------------------------------------------------------
    // Phase 1 — run first, against a fresh process
    // -----------------------------------------------------------------

    /**
     * Writes real, durable state a fresh process (phase2) must find: a real
     * task via [com.softwaremine.dps.data.android.tool.AndroidTaskTool] (the
     * one permission-free tool in this suite), and a real stored preference.
     * Also proves — before any process boundary is even involved — that a
     * genuinely failed action never touches memory at all, matching M3's
     * standing gating rule this test does not modify.
     */
    @Test
    fun phase1WriteRealStateBeforeProcessDeath(): Unit = runBlocking {
        val memoryStore = PersistentMemoryStore.create(context, silentLogger)
        val prefStore = PersistentPreferenceStore.create(context, silentLogger)
        memoryStore.clear()
        prefStore.clear()

        val secretary = scriptedSecretary(
            """{"intent":"task","parameters":{"title":"M3-D process-death check"}}""",
            """{"intent":"task","action_type":"complete","parameters":{"title":"M3-D nonexistent task xyz"}}""",
            persistentMemoryStore = memoryStore,
            persistentPreferenceStore = prefStore,
        )

        val created = secretary.handle("M3-D process-death check ka task bana do")
        assertTrue("Expected Handled, got $created", created is ToolOrchestrator.Outcome.Handled)
        assertEquals("M3-D process-death check", secretary.memory.value.lastTask?.title)

        // Failure/refusal pollution guard (section 5): completing a task
        // that does not exist must fail without touching memory at all —
        // ConversationMemoryUpdater's existing, unmodified gating logic.
        val failed = secretary.handle("M3-D nonexistent task xyz ko complete kar do")
        assertTrue("Expected Handled reporting the failure, got $failed", failed is ToolOrchestrator.Outcome.Handled)
        assertEquals(
            "A failed action must never overwrite what memory already held",
            "M3-D process-death check",
            secretary.memory.value.lastTask?.title,
        )
        assertEquals(
            "A failed action must never overwrite the persisted copy either",
            "M3-D process-death check",
            memoryStore.load().lastTask?.title,
        )

        prefStore.save(UserPreferences(defaultReminderLeadMinutes = 15))

        // Confirm both are genuinely on disk before this process ends.
        // Deliberately not cleaned up here — phase2 is what must find this.
        assertEquals("M3-D process-death check", memoryStore.load().lastTask?.title)
        assertEquals(15, prefStore.load().defaultReminderLeadMinutes)

        // M2-D's own finding, reused here: SharedPreferences.Editor.apply()
        // is asynchronous, and a process that exits immediately after the
        // last apply() call can lose the write before it ever reaches disk
        // — confirmed empirically for this exact test (see the M3-D
        // completion doc). This delay is what "give the async write enough
        // time to flush" (the M3-D brief's own step 3) means in practice.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // Phase 2 — run second, after `adb shell am force-stop` between the two
    // -----------------------------------------------------------------

    /**
     * Verifies everything phase1 wrote survived a real kill, then exercises
     * the full M3-C three-way precedence against the *restored* real
     * preference store, then cleans up everything both phases created.
     */
    @Test
    fun phase2VerifyRestoredStateAfterProcessDeath(): Unit = runBlocking {
        // The real, production-wired SecretaryOrchestrator — merely
        // accessing this lazy property constructs it, which is exactly
        // where PersistentMemoryStore.load() seeds `_memory` in production
        // (SecretaryOrchestrator.kt). No .handle() call, no scripted
        // engine, no model load — a pure disk read.
        val restoredTask = container.secretaryOrchestrator.memory.value.lastTask
        assertNotNull("ConversationMemory did not survive process death", restoredTask)
        assertEquals("M3-D process-death check", restoredTask?.title)

        // The real, production-wired preference store, same check.
        assertEquals(15, container.persistentPreferenceStore.load().defaultReminderLeadMinutes)

        val memoryStore = PersistentMemoryStore.create(context, silentLogger)
        val prefStore = container.persistentPreferenceStore
        val createdEventIds = mutableListOf<String>()
        val createdReminderIds = mutableListOf<String>()
        var taskId: String? = null

        try {
            // ReferenceResolver resolving purely from memory a real process
            // death restored: a scripted-engine SecretaryOrchestrator built
            // from the SAME real, on-disk stores container.secretaryOrchestrator
            // itself reads from — only the model is swapped for the
            // deterministic stand-in this whole suite already uses.
            val secretary = scriptedSecretary(
                """{"intent":"task","action_type":"complete","parameters":{}}""",
                persistentMemoryStore = memoryStore,
                persistentPreferenceStore = prefStore,
            )
            assertEquals(
                "A freshly constructed orchestrator must load the same restored memory",
                "M3-D process-death check",
                secretary.memory.value.lastTask?.title,
            )

            val resolved = secretary.handle("complete that task")
            assertTrue("Expected Handled, got $resolved", resolved is ToolOrchestrator.Outcome.Handled)
            taskId = secretary.memory.value.lastTask?.id?.toString()
            assertNotNull("The reference-resolved completion did not record a task id", taskId)
            assertEquals(
                "\"complete that task\" must resolve to the exact task restored from disk, not a fresh one",
                "M3-D process-death check",
                secretary.memory.value.lastTask?.title,
            )

            // --- Precedence Case B: no explicit offset — the restored 15-minute preference wins over the 30-minute default ---
            val caseBSecretary = scriptedSecretary(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"M3-D precedence case B","raw_when":"11:00"}},
                    {"intent":"reminder","parameters":{"title":"M3-D precedence case B"}}
                ]}""",
                persistentMemoryStore = memoryStore,
                persistentPreferenceStore = prefStore,
            )
            val outcomeB = caseBSecretary.handle("M3-D precedence case B at 11:00 aur usse pehle mujhe yaad dila dena")
            assertTrue(
                "Expected Handled or NeedsPermission, got $outcomeB",
                outcomeB is ToolOrchestrator.Outcome.Handled || outcomeB is ToolOrchestrator.Outcome.NeedsPermission,
            )
            if (outcomeB is ToolOrchestrator.Outcome.Handled) {
                val eventIdB = caseBSecretary.memory.value.lastCalendarEvent?.id
                val eventStartB = caseBSecretary.memory.value.lastCalendarEvent?.startMillis
                val reminderIdB = caseBSecretary.memory.value.lastReminder?.id
                val reminderTriggerB = caseBSecretary.memory.value.lastReminder?.triggerAtMillis
                assertNotNull("Memory did not record the created event (case B)", eventIdB)
                assertNotNull("Memory did not record the created reminder (case B)", reminderIdB)
                createdEventIds += eventIdB.toString()
                createdReminderIds += reminderIdB.toString()

                assertEquals(
                    "With no explicit offset, the restored 15-minute preference must be used instead of the 30-minute default",
                    eventStartB!! - 15 * 60 * 1000L,
                    reminderTriggerB,
                )
            }

            // --- Precedence Case A/D: an explicit 5-minute request offset still wins over the restored 15-minute preference ---
            val caseASecretary = scriptedSecretary(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"M3-D precedence case A","raw_when":"12:00"}},
                    {"intent":"reminder","parameters":{"title":"M3-D precedence case A","raw_when":"5 minutes before"}}
                ]}""",
                persistentMemoryStore = memoryStore,
                persistentPreferenceStore = prefStore,
            )
            val outcomeA = caseASecretary.handle("M3-D precedence case A at 12:00, remind me 5 minutes before it.")
            assertTrue(
                "Expected Handled or NeedsPermission, got $outcomeA",
                outcomeA is ToolOrchestrator.Outcome.Handled || outcomeA is ToolOrchestrator.Outcome.NeedsPermission,
            )
            if (outcomeA is ToolOrchestrator.Outcome.Handled) {
                val eventIdA = caseASecretary.memory.value.lastCalendarEvent?.id
                val eventStartA = caseASecretary.memory.value.lastCalendarEvent?.startMillis
                val reminderIdA = caseASecretary.memory.value.lastReminder?.id
                val reminderTriggerA = caseASecretary.memory.value.lastReminder?.triggerAtMillis
                assertNotNull("Memory did not record the created event (case A)", eventIdA)
                assertNotNull("Memory did not record the created reminder (case A)", reminderIdA)
                createdEventIds += eventIdA.toString()
                createdReminderIds += reminderIdA.toString()

                assertEquals(
                    "An explicit 5-minute request offset must still win over the restored 15-minute preference",
                    eventStartA!! - 5 * 60 * 1000L,
                    reminderTriggerA,
                )
            }

            // --- Precedence Case C: clearing the restored preference restores the original 30-minute default ---
            prefStore.clear()
            val caseCSecretary = scriptedSecretary(
                """{"steps":[
                    {"intent":"calendar_event","parameters":{"title":"M3-D precedence case C","raw_when":"13:00"}},
                    {"intent":"reminder","parameters":{"title":"M3-D precedence case C"}}
                ]}""",
                persistentMemoryStore = memoryStore,
                persistentPreferenceStore = prefStore,
            )
            val outcomeC = caseCSecretary.handle("M3-D precedence case C at 13:00 aur usse pehle mujhe yaad dila dena")
            assertTrue(
                "Expected Handled or NeedsPermission, got $outcomeC",
                outcomeC is ToolOrchestrator.Outcome.Handled || outcomeC is ToolOrchestrator.Outcome.NeedsPermission,
            )
            if (outcomeC is ToolOrchestrator.Outcome.Handled) {
                val eventIdC = caseCSecretary.memory.value.lastCalendarEvent?.id
                val eventStartC = caseCSecretary.memory.value.lastCalendarEvent?.startMillis
                val reminderIdC = caseCSecretary.memory.value.lastReminder?.id
                val reminderTriggerC = caseCSecretary.memory.value.lastReminder?.triggerAtMillis
                assertNotNull("Memory did not record the created event (case C)", eventIdC)
                assertNotNull("Memory did not record the created reminder (case C)", reminderIdC)
                createdEventIds += eventIdC.toString()
                createdReminderIds += reminderIdC.toString()

                assertEquals(
                    "Clearing the restored preference must restore the original 30-minute default",
                    eventStartC!! - 30 * 60 * 1000L,
                    reminderTriggerC,
                )
            }

            // reset() semantics (section 7): unchanged from M3-B — clears
            // both the in-memory copy and the persisted copy. Documented,
            // not re-decided, here; asserted once as a lock-down, using the
            // real production orchestrator itself (no scripted secretary
            // needed for this).
            container.secretaryOrchestrator.reset()
            assertEquals(
                "reset() must clear the persisted copy too, per M3-B's own established contract",
                ConversationMemory.EMPTY,
                memoryStore.load(),
            )
        } finally {
            taskId?.let {
                container.toolExecutor.execute(ToolCall(ToolId.TASK, "cancel_task", mapOf("id" to it)))
            }
            createdReminderIds.forEach { id ->
                container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to id)))
            }
            createdEventIds.forEach { id ->
                container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to id)))
            }
            memoryStore.clear()
            prefStore.clear()
        }
    }
}
