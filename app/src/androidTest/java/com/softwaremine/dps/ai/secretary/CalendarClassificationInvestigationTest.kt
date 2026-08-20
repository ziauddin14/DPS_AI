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
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THROWAWAY investigation harness, not permanent coverage.
 *
 * Runs the exact 4 Stage-2 calendar-deletion phrases plus an 8-category
 * matrix through the real on-device Qwen2.5-1.5B model, capturing raw model
 * JSON, classified intent/action, clarification decision and final
 * execution outcome for every run — evidence for the Calendar Delete/Update
 * Classification Reliability investigation.
 */
@RunWith(AndroidJUnit4::class)
class CalendarClassificationInvestigationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val descriptor: ModelDescriptor = ModelCatalog.DEFAULT
    private val container by lazy { AiContainer(context) }

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Wraps the real engine, printing the raw completion before it is parsed. */
    private class LoggingAiEngine(private val delegate: AiEngine) : AiEngine {
        override val state get() = delegate.state
        override val activeModel get() = delegate.activeModel

        override suspend fun initialize() = delegate.initialize()
        override suspend fun loadModel(descriptor: ModelDescriptor, config: ModelConfig) =
            delegate.loadModel(descriptor, config)

        override suspend fun unloadModel() = delegate.unloadModel()
        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = delegate.generate(request)

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> {
            val result = delegate.generateOnce(request)
            if (result is DpsResult.Success) {
                println("CLASSIFY_RAW >>> ${result.value.text.replace("\n", "\\n")}")
            } else if (result is DpsResult.Failure) {
                println("CLASSIFY_RAW >>> [FAILED: ${result.error.message}]")
            }
            return result
        }

        override suspend fun tokenCount(text: String) = delegate.tokenCount(text)
        override suspend fun shutdown() = delegate.shutdown()
    }

    private fun secretary(): SecretaryOrchestrator {
        val toolOrchestrator = ToolOrchestrator(
            engine = LoggingAiEngine(container.aiEngine),
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
            logger = silentLogger,
        )
    }

    private fun run(label: String, orchestrator: SecretaryOrchestrator, message: String) = runBlocking {
        println("CASE_START >>> $label | \"$message\"")
        val outcome = orchestrator.handle(message)
        val memory = orchestrator.memory.value
        println(
            "CASE_OUTCOME >>> $label | outcome=${outcome::class.simpleName} | detail=${describeOutcome(outcome)} " +
                "| lastCalendarEvent=${memory.lastCalendarEvent} | lastReminder=${memory.lastReminder}",
        )
    }

    private fun describeOutcome(outcome: ToolOrchestrator.Outcome): String = when (outcome) {
        is ToolOrchestrator.Outcome.Handled ->
            "Handled(intent=${outcome.intent.type}/${outcome.intent.action}, result=${outcome.result::class.simpleName}, " +
                "summary=${(outcome.result as? com.softwaremine.dps.domain.tool.ToolResult.Success)?.summary})"

        is ToolOrchestrator.Outcome.Clarify ->
            "Clarify(intent=${outcome.resolution.intent.type}/${outcome.resolution.intent.action}, question=\"${outcome.question}\")"

        is ToolOrchestrator.Outcome.Conversational -> "Conversational(reason=${outcome.reason})"
        is ToolOrchestrator.Outcome.NeedsPermission -> "NeedsPermission(reply=${outcome.reply})"
    }

    @Test
    fun runInvestigation(): Unit = runBlocking {
        // ---- One-time model load, shared for the whole investigation ----
        assertTrue(
            "Engine failed to initialize",
            container.aiEngine.initialize() is DpsResult.Success,
        )
        val loaded = container.aiEngine.loadModel(descriptor, ModelConfig.SECRETARY)
        assertTrue(
            "Model failed to load: ${(loaded as? DpsResult.Failure)?.error?.message}",
            loaded is DpsResult.Success,
        )
        println("MODEL_READY >>> ${descriptor.id}")

        // ---- Section 1: exact Stage-2 replication, standalone each ----
        listOf(
            "Delete the calendar event.",
            "Remove the meeting from my calendar.",
            "Cancel the meeting.",
            "Us meeting ko calendar se delete kar do.",
        ).forEachIndexed { i, phrase ->
            run("STAGE2_REPLICATION_${i + 1}", secretary(), phrase)
        }

        // ---- Section 2: 8-category matrix, 3 repeats each ----
        val repeats = 3

        repeat(repeats) { n ->
            // 1. Delete/cancel a specific calendar event (with a real prior event)
            val s1 = secretary()
            run("CAT1_DELETE_SPECIFIC_r$n setup", s1, "Schedule a standup meeting tomorrow at 10am.")
            run("CAT1_DELETE_SPECIFIC_r$n", s1, "Delete the standup meeting.")

            // 2. Update/reschedule a specific calendar event
            val s2 = secretary()
            run("CAT2_UPDATE_SPECIFIC_r$n setup", s2, "Schedule a standup meeting tomorrow at 10am.")
            run("CAT2_UPDATE_SPECIFIC_r$n", s2, "Reschedule the standup meeting to 11am.")

            // 3. Change title/details of a calendar event
            val s3 = secretary()
            run("CAT3_CHANGE_DETAILS_r$n setup", s3, "Schedule a standup meeting tomorrow at 10am.")
            run("CAT3_CHANGE_DETAILS_r$n", s3, "Change the standup meeting title to Daily Sync.")

            // 4. Delete by reference
            val s4 = secretary()
            run("CAT4_DELETE_BY_REFERENCE_r$n setup", s4, "Schedule a standup meeting tomorrow at 10am.")
            run("CAT4_DELETE_BY_REFERENCE_r$n", s4, "Delete that meeting.")

            // 5. Update by reference
            val s5 = secretary()
            run("CAT5_UPDATE_BY_REFERENCE_r$n setup", s5, "Schedule a standup meeting tomorrow at 10am.")
            run("CAT5_UPDATE_BY_REFERENCE_r$n", s5, "Move that meeting to tomorrow evening.")

            // 6. Reminder cancel/update control
            val s6 = secretary()
            run("CAT6_REMINDER_CONTROL_r$n setup", s6, "Remind me to call the bank at 4pm.")
            run("CAT6_REMINDER_CONTROL_r$n", s6, "Cancel that reminder.")

            // 7. Calendar creation control
            run("CAT7_CALENDAR_CREATE_CONTROL_r$n", secretary(), "Schedule a team lunch on Friday at 1pm.")

            // 8. Genuine notification request (negative control)
            run("CAT8_NOTIFICATION_CONTROL_r$n", secretary(), "Show me a notification that says the build finished.")
        }

        println("INVESTIGATION_COMPLETE")
    }
}
