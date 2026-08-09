package com.softwaremine.dps.ai.secretary

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
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
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
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(zone = zone),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(zone = zone),
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
