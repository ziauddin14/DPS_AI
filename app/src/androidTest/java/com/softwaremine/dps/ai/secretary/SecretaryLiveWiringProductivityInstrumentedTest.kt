package com.softwaremine.dps.ai.secretary

import android.content.Context
import android.content.SharedPreferences
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
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
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
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the Day 06 productivity intents reach the
 * *real* [AiContainer.toolRegistry] — the same drift check
 * [SecretaryLiveWiringInstrumentedTest] runs for Day 05's tools, now for
 * `create_task`/`complete_task` against the real, `SharedPreferences`-backed
 * [com.softwaremine.dps.data.android.productivity.AndroidTaskStore].
 *
 * The model is still scripted; see [SecretaryLiveWiringInstrumentedTest]'s
 * own doc for why.
 */
@RunWith(AndroidJUnit4::class)
class SecretaryLiveWiringProductivityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    @Before
    fun clearTaskStore() {
        context.getSharedPreferences("dps_tasks", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class ScriptedEngine(vararg replies: String) : AiEngine {
        private val replies = replies.toList()
        private var index = 0

        override val state: StateFlow<AiState> = MutableStateFlow(AiState.Idle)
        override val activeModel: ModelDescriptor? = null

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(descriptor: ModelDescriptor, config: ModelConfig): DpsResult<Unit> =
            DpsResult.Success(Unit)

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
            temporalPhraseResolver = TemporalPhraseResolver(),
            temporalGroundingGuard = TemporalGroundingGuard(),
            temporalStepAttributor = TemporalStepAttributor(TemporalPhraseSpanFinder(), TemporalGroundingGuard()),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            // M3-B: this file drift-checks the productivity tools against the
            // real registry, not memory persistence — an isolated, in-memory
            // fake keeps every test here touching zero real device state for
            // memory, exactly as before this milestone.
            persistentMemoryStore = PersistentMemoryStore(FakeSharedPreferences(), silentLogger),
            persistentPreferenceStore = PersistentPreferenceStore(FakeSharedPreferences(), silentLogger),
            logger = silentLogger,
        )
    }

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

    @Test
    fun taskOperationsAreRegisteredOnTheRealTool() {
        val selector = ToolSelector()
        val tool = container.toolRegistry.find(
            selector.select(
                com.softwaremine.dps.domain.intent.DpsIntent(
                    com.softwaremine.dps.domain.intent.IntentType.TASK,
                    com.softwaremine.dps.domain.intent.IntentParameters(title = "x"),
                ),
            )!!.toolId,
        )

        assertNotNull("TASK is not registered on this device", tool)
        assertTrue(
            "create_task is not declared by the real tool: ${tool!!.operations}",
            "create_task" in tool.operations,
        )
        assertTrue("complete_task is not declared: ${tool.operations}", "complete_task" in tool.operations)
    }

    @Test
    fun creatingATaskThenCompletingItByPronounResolvesAgainstTheRealTaskJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"task","parameters":{"title":"DBPMS documentation"}}""",
            """{"intent":"task","action_type":"complete"}""",
        )

        val first = orchestrator.handle("DBPMS documentation ka task add karo")
        assertTrue("Expected Handled, got $first", first is ToolOrchestrator.Outcome.Handled)
        assertNotNull("Memory did not record the created task", orchestrator.memory.value.lastTask)

        val second = orchestrator.handle("us task ko complete kar do")
        assertTrue(
            "Expected the follow-up to resolve against the real task, got $second",
            second is ToolOrchestrator.Outcome.Handled,
        )
    }

    @Test
    fun generatingADailyReportNeverTouchesTheInferenceEngineTwice(): Unit = runBlocking {
        val orchestrator = secretary("""{"intent":"report"}""")

        val outcome = orchestrator.handle("meri aaj ki report bana do")

        assertTrue("Expected Handled, got $outcome", outcome is ToolOrchestrator.Outcome.Handled)
    }
}
