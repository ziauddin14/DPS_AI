package com.softwaremine.dps.ai.session

import android.content.SharedPreferences
import com.softwaremine.dps.ai.conversation.ConversationManager
import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolResponseGenerator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.EpisodicMemoryRecorder
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.ChatTemplateRegistry
import com.softwaremine.dps.ai.prompt.PromptManager
import com.softwaremine.dps.ai.secretary.SecretaryOrchestrator
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.android.memory.LongTermMemoryStore
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryDao
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryEntity
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactDao
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.secretary.AutomationVerifier
import com.softwaremine.dps.data.android.secretary.ExecutionVerifier
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus
import com.softwaremine.dps.domain.model.InstalledModel
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelInstallState
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.model.ModelStorageStats
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.runtime.RuntimeId
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Verification of the Day 08-B reply shortcut at the routing layer.
 *
 * ## What this proves that [com.softwaremine.dps.ai.secretary.SecretaryOrchestratorTest]
 * cannot
 * That the *actual pass count* — not just the [ToolOrchestrator.Outcome] shape
 * — changes: [AiSessionManager.routeMessage] must skip the second, streaming
 * [AiEngine.generate] call entirely when the classification pass already
 * produced a reply, and must still call it, unchanged, when it did not. The
 * engine here tracks both call kinds separately so that distinction is
 * directly observable rather than inferred.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSessionManagerReplyShortcutTest {

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

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /**
     * Scripted [AiEngine] that answers [generateOnce] (classification) from a
     * canned queue and counts [generate] (streaming) calls separately, so a
     * test can assert directly on "was the second pass ever started" rather
     * than inferring it from timing.
     */
    private class RoutingProbeEngine(private val classificationReply: String) : AiEngine {
        var classifyCalls = 0
        var streamCalls = 0

        override val state: StateFlow<AiState> =
            MutableStateFlow(AiState.Ready("test-model", RuntimeId.LLAMA_CPP))
        override val activeModel: ModelDescriptor = ModelCatalog.DEFAULT

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(d: ModelDescriptor, config: ModelConfig): DpsResult<Unit> =
            DpsResult.Success(Unit)
        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)

        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = flow {
            streamCalls++
            emit(CompletionChunk.Token("Streamed reply."))
            emit(
                CompletionChunk.Completed(
                    AiCompletion(
                        text = "Streamed reply.",
                        finishReason = FinishReason.END_OF_TURN,
                        usage = TokenUsage(promptTokens = 10, completionTokens = 3),
                        durationMillis = 10,
                    ),
                ),
            )
        }

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> {
            classifyCalls++
            return DpsResult.Success(
                AiCompletion(
                    text = classificationReply,
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 50, completionTokens = 20),
                    durationMillis = 10,
                ),
            )
        }

        override suspend fun tokenCount(text: String): DpsResult<Int> = DpsResult.Success(1)
        override suspend fun shutdown() = Unit
    }

    private class StubModelManager : ModelManager {
        override val installState: StateFlow<ModelInstallState> = MutableStateFlow(ModelInstallState.NotInstalled)
        override fun catalog(): List<ModelDescriptor> = listOf(ModelCatalog.DEFAULT)
        override fun defaultModel(): ModelDescriptor = ModelCatalog.DEFAULT
        override fun findDescriptor(modelId: String): DpsResult<ModelDescriptor> = DpsResult.Success(ModelCatalog.DEFAULT)
        override suspend fun installedModels(): DpsResult<List<InstalledModel>> = error("unused")
        override suspend fun resolveInstalled(descriptor: ModelDescriptor): DpsResult<InstalledModel> = error("unused")
        override suspend fun canInstall(descriptor: ModelDescriptor): DpsResult<Unit> = error("unused")
        override fun install(descriptor: ModelDescriptor): Flow<ModelInstallState> = emptyFlow()
        override suspend fun verify(descriptor: ModelDescriptor): DpsResult<Boolean> = error("unused")
        override suspend fun delete(descriptor: ModelDescriptor): DpsResult<Unit> = error("unused")
        override suspend fun storageStats(): DpsResult<ModelStorageStats> = error("unused")
        override suspend fun clearPartialDownloads(): DpsResult<Long> = error("unused")
    }

    private class FakePermissions : PermissionManager {
        override fun state(permission: DpsPermission) = PermissionState.GRANTED
        override fun states(permissions: Set<DpsPermission>) = permissions.associateWith { PermissionState.GRANTED }
        override fun missing(permissions: Set<DpsPermission>) = emptySet<DpsPermission>()
        override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
    }

    /** A minimal, always-succeeding reminder tool — enough for a request to reach [ToolOrchestrator.Outcome.Handled]. */
    private fun reminderTool(): AndroidTool = object : AndroidTool {
        override val id: ToolId = ToolId.REMINDER
        override val operations: Set<String> = setOf("create_reminder", "update_reminder", "cancel_reminder")
        override val requiredPermissions: Set<DpsPermission> = emptySet()
        override suspend fun execute(call: ToolCall): ToolResult =
            ToolResult.Success("Reminder set.", mapOf("reminder_id" to "1001", "trigger_at" to "1000000", "exact" to "true"))
    }

    /**
     * M3-B: this file tests the same-pass reply shortcut, not persistence,
     * so [SecretaryOrchestrator]'s now-required [PersistentMemoryStore] is
     * backed by a no-op fake — reads always miss (memory starts EMPTY, as
     * before this milestone) and writes go nowhere.
     */
    private class NoOpSharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = NoOpEditor
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private object NoOpEditor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit() = true
            override fun apply() = Unit
        }
    }

    /** M6: in-memory DAO fakes — this file tests the reply shortcut, not persistence. */
    private class FakeSemanticFactDao : SemanticFactDao {
        private val facts = mutableListOf<SemanticFactEntity>()
        private var nextId = 1L
        override suspend fun insert(fact: SemanticFactEntity): Long {
            val assigned = fact.copy(id = nextId++)
            facts += assigned
            return assigned.id
        }
        override suspend fun findBySubject(subjectQuery: String): List<SemanticFactEntity> =
            facts.filter { it.subject.contains(subjectQuery, ignoreCase = true) }.sortedByDescending { it.createdAtMillis }
        override suspend fun findAll(): List<SemanticFactEntity> = facts.sortedByDescending { it.createdAtMillis }
        override suspend fun delete(fact: SemanticFactEntity): Int = if (facts.removeAll { it.id == fact.id }) 1 else 0
        override suspend fun deleteById(id: Long): Int = if (facts.removeAll { it.id == id }) 1 else 0
    }

    private class FakeEpisodicMemoryDao : EpisodicMemoryDao {
        private val entries = mutableListOf<EpisodicMemoryEntity>()
        private var nextId = 1L
        override suspend fun insert(entry: EpisodicMemoryEntity): Long {
            val assigned = entry.copy(id = nextId++)
            entries += assigned
            return assigned.id
        }
        override suspend fun findInRange(fromMillis: Long, toMillis: Long, limit: Int): List<EpisodicMemoryEntity> =
            entries.filter { it.timestampMillis in fromMillis..toMillis }.sortedByDescending { it.timestampMillis }.take(limit)
        override suspend fun findByKeyword(keyword: String, limit: Int): List<EpisodicMemoryEntity> =
            entries.filter { it.summary.contains(keyword, ignoreCase = true) }.sortedByDescending { it.timestampMillis }.take(limit)
        override suspend fun count(): Int = entries.size
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
            val before = entries.size
            entries.removeAll { it.timestampMillis < cutoffMillis }
            return before - entries.size
        }
        override suspend fun deleteOldestBeyond(keepNewest: Int): Int {
            if (entries.size <= keepNewest) return 0
            val toKeep = entries.sortedByDescending { it.id }.take(keepNewest).map { it.id }.toSet()
            val before = entries.size
            entries.removeAll { it.id !in toKeep }
            return before - entries.size
        }
    }

    private fun sessionManager(
        engine: AiEngine,
        scope: kotlinx.coroutines.CoroutineScope,
    ): AiSessionManager {
        val registry = DefaultToolRegistry(silentLogger).apply { register(reminderTool()) }
        val toolOrchestrator = ToolOrchestrator(
            engine = engine,
            executor = DefaultToolExecutor(
                registry = registry,
                permissionManager = FakePermissions(),
                dispatchers = immediateDispatchers,
                logger = silentLogger,
                apiLevel = 35,
            ),
            registry = registry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(zone = zone),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )
        val secretaryOrchestrator = SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(zone = zone),
            temporalPhraseResolver = TemporalPhraseResolver(zone = zone),
            temporalGroundingGuard = TemporalGroundingGuard(),
            temporalStepAttributor = TemporalStepAttributor(TemporalPhraseSpanFinder(), TemporalGroundingGuard()),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(zone = zone),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(zone = zone),
            persistentMemoryStore = PersistentMemoryStore(NoOpSharedPreferences(), silentLogger),
            persistentPreferenceStore = PersistentPreferenceStore(NoOpSharedPreferences(), silentLogger),
            persistentRecoveryStore = PersistentRecoveryStore(NoOpSharedPreferences(), silentLogger),
            episodicMemoryRecorder = EpisodicMemoryRecorder(
                LongTermMemoryStore(FakeSemanticFactDao(), FakeEpisodicMemoryDao(), silentLogger),
            ),
            // M7: this file tests the reply shortcut, not verification —
            // left unwired (null); see ExecutionVerifier's own doc.
            executionVerifier = ExecutionVerifier(
                taskRepository = null,
                calendarEventReader = null,
                persistentRecoveryStore = PersistentRecoveryStore(NoOpSharedPreferences(), silentLogger),
                dispatchers = immediateDispatchers,
                logger = silentLogger,
            ),
            // M9: same reasoning as executionVerifier's own null repository above.
            automationVerifier = AutomationVerifier(
                engine = null,
                persistentRecoveryStore = PersistentRecoveryStore(NoOpSharedPreferences(), silentLogger),
                dispatchers = immediateDispatchers,
                logger = silentLogger,
            ),
            permissionManager = FakePermissions(),
            toolRegistry = registry,
            responses = ToolResponseGenerator(),
            logger = silentLogger,
            zone = zone,
        )

        return AiSessionManager(
            engine = engine,
            modelManager = StubModelManager(),
            conversationManager = ConversationManager(),
            promptManager = PromptManager(ChatTemplateRegistry(), silentLogger),
            responseParser = ResponseParser(),
            secretaryOrchestrator = secretaryOrchestrator,
            permissionManager = FakePermissions(),
            dispatchers = immediateDispatchers,
            logger = silentLogger,
            scope = scope,
        )
    }

    @Test
    fun `ordinary conversation with a same-pass reply never starts the streaming pass`() = runTest {
        val engine = RoutingProbeEngine(
            """{"intent":"conversation","parameters":{"reply":"Wa alaikum assalam. Sab theek hai."}}""",
        )
        val session = sessionManager(engine, this)

        session.sendMessage("Salam DPS, kya haal hai?")
        advanceUntilIdle()

        assertEquals(1, engine.classifyCalls)
        assertEquals("The second, streaming pass must never start.", 0, engine.streamCalls)

        val reply = session.conversation.value.messages.last()
        assertEquals(MessageRole.ASSISTANT, reply.role)
        assertEquals(MessageStatus.Complete, reply.status)
        assertEquals("Wa alaikum assalam. Sab theek hai.", reply.content)
    }

    @Test
    fun `ordinary conversation with no same-pass reply falls back to exactly one streaming pass`() = runTest {
        val engine = RoutingProbeEngine("""{"intent":"conversation"}""")
        val session = sessionManager(engine, this)

        session.sendMessage("tell me something interesting")
        advanceUntilIdle()

        assertEquals(1, engine.classifyCalls)
        assertEquals("The existing fallback pass must still run exactly once.", 1, engine.streamCalls)

        val reply = session.conversation.value.messages.last()
        assertEquals(MessageRole.ASSISTANT, reply.role)
        assertEquals("Streamed reply.", reply.content)
    }

    @Test
    fun `a tool request never touches the streaming pass either way`() = runTest {
        val engine = RoutingProbeEngine(
            """{"intent":"reminder","parameters":{"title":"call the bank","time":"16:00"}}""",
        )
        val session = sessionManager(engine, this)

        session.sendMessage("remind me to call the bank at 4")
        advanceUntilIdle()

        assertEquals(1, engine.classifyCalls)
        assertEquals(0, engine.streamCalls)
    }

    @Test
    fun `two consecutive same-pass replies both skip the streaming pass`() = runTest {
        val engine = RoutingProbeEngine("""{"intent":"conversation","parameters":{"reply":"Sure."}}""")
        val session = sessionManager(engine, this)

        session.sendMessage("hi")
        advanceUntilIdle()
        session.sendMessage("thanks")
        advanceUntilIdle()

        assertEquals(2, engine.classifyCalls)
        assertEquals(0, engine.streamCalls)
        assertEquals(2, session.conversation.value.messages.count { it.role == MessageRole.ASSISTANT })
    }
}
