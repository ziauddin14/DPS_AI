package com.softwaremine.dps.ai.session

import com.softwaremine.dps.ai.conversation.ConversationManager
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
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.ChatTemplateRegistry
import com.softwaremine.dps.ai.prompt.PromptManager
import com.softwaremine.dps.ai.secretary.SecretaryOrchestrator
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
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
