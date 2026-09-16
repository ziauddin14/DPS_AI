package com.softwaremine.dps.ai.secretary

import android.content.SharedPreferences
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
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
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
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * End-to-end verification of the Day 06 productivity flow through
 * [SecretaryOrchestrator] — same pattern as [SecretaryOrchestratorTest], now
 * exercising a real (in-memory-backed) task tool instead of a scripted one,
 * to prove the whole pipeline: reference resolution, title-addressing, and
 * the generalized delete-confirmation gate.
 */
class SecretaryOrchestratorProductivityTest {

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

    private class FakePermissions : PermissionManager {
        override fun state(permission: DpsPermission) = PermissionState.GRANTED
        override fun states(permissions: Set<DpsPermission>) = permissions.associateWith { PermissionState.GRANTED }
        override fun missing(permissions: Set<DpsPermission>) = emptySet<DpsPermission>()
        override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
    }

    /** A minimal in-memory task tool — real CRUD semantics, no Android SharedPreferences. */
    private class InMemoryTaskTool : AndroidTool {
        private val tasks = mutableMapOf<Int, Pair<String, Boolean>>() // id -> (title, deleted-tracked-separately)
        private var nextId = 1

        override val id: ToolId = ToolId.TASK
        override val operations: Set<String> = setOf("create_task", "complete_task", "cancel_task", "list_tasks")
        override val requiredPermissions: Set<DpsPermission> = emptySet()

        override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
            "create_task" -> {
                val id = nextId++
                val title = call.argument("title").orEmpty()
                tasks[id] = title to false
                ToolResult.Success("Task \"$title\" added.", mapOf("task_id" to id.toString()))
            }

            "complete_task", "cancel_task" -> {
                val byId = call.argument("id")?.toIntOrNull()
                val match = byId?.let { tasks[it]?.let { entry -> it to entry } }
                    ?: call.argument("title")?.let { q -> tasks.entries.find { it.value.first.contains(q, ignoreCase = true) } }
                        ?.let { it.key to it.value }

                if (match == null) {
                    ToolResult.Failure("I couldn't find that task.", retryable = false)
                } else {
                    val (foundId, entry) = match
                    if (call.operation == "cancel_task") tasks.remove(foundId)
                    ToolResult.Success("Done.", mapOf("task_id" to foundId.toString(), "title" to entry.first))
                }
            }

            "list_tasks" -> ToolResult.Success("You have ${tasks.size} pending tasks.")

            else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
        }
    }

    /**
     * M3-B: this file tests the productivity/task flow, not persistence, so
     * [SecretaryOrchestrator]'s now-required [PersistentMemoryStore] is
     * backed by a no-op fake — reads always miss (memory starts EMPTY, as
     * before this milestone) and writes go nowhere, with no Android runtime
     * needed.
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

    /** M6: in-memory DAO fakes — this file tests the productivity flow, not persistence. */
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

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    private fun secretary(vararg classifications: String): SecretaryOrchestrator {
        val registry = DefaultToolRegistry(silentLogger).apply { register(InMemoryTaskTool()) }
        val executor = DefaultToolExecutor(
            registry = registry,
            permissionManager = FakePermissions(),
            dispatchers = immediateDispatchers,
            logger = silentLogger,
            apiLevel = 35,
        )
        val toolOrchestrator = ToolOrchestrator(
            engine = ScriptedEngine(*classifications),
            executor = executor,
            registry = registry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(zone = zone),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )

        return SecretaryOrchestrator(
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
            // M7: this file tests the productivity flow, not verification —
            // InMemoryTaskTool's own Success.data includes a task_id with
            // no backing TaskRepository behind it, so verification is left
            // unwired (null) rather than hand-built to stay in sync with
            // that fake. See ExecutionVerifier's own doc.
            executionVerifier = ExecutionVerifier(
                taskRepository = null,
                calendarEventReader = null,
                persistentRecoveryStore = PersistentRecoveryStore(NoOpSharedPreferences(), silentLogger),
                dispatchers = immediateDispatchers,
                logger = silentLogger,
            ),
            // M9: same reasoning as executionVerifier's own null repositories above.
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
    }

    @Test
    fun `creating a task remembers it, and a follow-up pronoun resolves against it`() = runTest {
        val secretary = secretary(
            """{"intent":"task","parameters":{"title":"DBPMS documentation"}}""",
            """{"intent":"task","action_type":"complete"}""",
        )

        val created = secretary.handle("DBPMS documentation ka task add karo")
        assertTrue(created is ToolOrchestrator.Outcome.Handled)
        assertNotNull(secretary.memory.value.lastTask)

        val completed = secretary.handle("us task ko complete kar do")
        assertTrue("Expected the follow-up to resolve, got $completed", completed is ToolOrchestrator.Outcome.Handled)
    }

    @Test
    fun `deleting a task asks for confirmation before it happens`() = runTest {
        val secretary = secretary(
            """{"intent":"task","parameters":{"title":"DBPMS documentation"}}""",
            """{"intent":"task","action_type":"cancel","parameters":{"title":"DBPMS documentation"}}""",
        )

        secretary.handle("DBPMS documentation ka task add karo")
        val deleteRequest = secretary.handle("DBPMS documentation wala task delete kar do")

        assertTrue(
            "Delete must ask for confirmation before doing anything, got $deleteRequest",
            deleteRequest is ToolOrchestrator.Outcome.Clarify,
        )
    }

    @Test
    fun `confirming a task deletion actually deletes it`() = runTest {
        val secretary = secretary(
            """{"intent":"task","parameters":{"title":"DBPMS documentation"}}""",
            """{"intent":"task","action_type":"cancel","parameters":{"title":"DBPMS documentation"}}""",
        )

        secretary.handle("DBPMS documentation ka task add karo")
        secretary.handle("DBPMS documentation wala task delete kar do")
        val confirmed = secretary.handle("haan")

        assertTrue(confirmed is ToolOrchestrator.Outcome.Handled)
        assertEquals(
            "Done.",
            (confirmed as ToolOrchestrator.Outcome.Handled).result.let { (it as ToolResult.Success).summary },
        )
    }
}
