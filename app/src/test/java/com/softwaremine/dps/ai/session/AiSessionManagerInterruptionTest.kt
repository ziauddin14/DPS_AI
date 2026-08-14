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
import com.softwaremine.dps.core.error.DpsError
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
import kotlinx.coroutines.CompletableDeferred
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
 * Verification of the Day 07 fix for Day 06's finding: a turn cut off by
 * [AiSessionManager.releaseMemory] (Android reclaiming memory mid-turn) must
 * leave a visible trace, never a silently dead exchange.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSessionManagerInterruptionTest {

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

    private val descriptor: ModelDescriptor = ModelCatalog.DEFAULT
    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /**
     * A classification pass that never completes until [release] is
     * signalled — simulating a turn genuinely in flight when memory pressure
     * hits, without relying on real timing.
     */
    private class HangingEngine(private val hangDuringStreaming: Boolean = false) : AiEngine {
        val release = CompletableDeferred<Unit>()

        override val state: StateFlow<AiState> =
            MutableStateFlow(AiState.Ready("test-model", RuntimeId.LLAMA_CPP))
        override val activeModel: ModelDescriptor = ModelCatalog.DEFAULT

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(d: ModelDescriptor, config: ModelConfig): DpsResult<Unit> =
            DpsResult.Success(Unit)
        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)

        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = flow {
            if (hangDuringStreaming) {
                emit(CompletionChunk.Token("Partial"))
                release.await() // never resolved in the streaming test — cancelled instead
            }
        }

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> {
            if (!hangDuringStreaming) release.await()
            return DpsResult.Success(
                AiCompletion(
                    text = """{"intent":"conversation"}""",
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 1, completionTokens = 1),
                    durationMillis = 1,
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

    private fun sessionManager(engine: AiEngine, scope: kotlinx.coroutines.CoroutineScope): AiSessionManager {
        val toolOrchestrator = ToolOrchestrator(
            engine = engine,
            executor = DefaultToolExecutor(
                registry = DefaultToolRegistry(silentLogger),
                permissionManager = FakePermissions(),
                dispatchers = immediateDispatchers,
                logger = silentLogger,
                apiLevel = 35,
            ),
            registry = DefaultToolRegistry(silentLogger),
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
    fun `releaseMemory while idle does not add any message`() = runTest {
        val session = sessionManager(HangingEngine(), this)

        session.releaseMemory()
        advanceUntilIdle()

        assertTrue(session.conversation.value.messages.isEmpty())
        assertTrue(session.sessionState.value is SessionState.Suspended)
    }

    @Test
    fun `a turn interrupted before any assistant bubble existed gets a visible note`() = runTest {
        val engine = HangingEngine()
        val session = sessionManager(engine, this)

        session.sendMessage("Assalam o Alaikum DPS")
        // Deliberately not resolving engine.release — the classification pass
        // is still in flight, exactly as it would be when onTrimMemory fires
        // mid-classification.
        session.releaseMemory()
        advanceUntilIdle()

        val messages = session.conversation.value.messages
        assertEquals(2, messages.size) // the user's turn, plus the new note
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals(MessageStatus.Complete, messages.last().status)
        assertTrue(messages.last().content.contains("free up memory", ignoreCase = true))
    }

    @Test
    fun `a turn interrupted mid-stream marks the streaming bubble failed rather than leaving it stuck`() = runTest {
        val engine = HangingEngine(hangDuringStreaming = true)
        val session = sessionManager(engine, this)

        session.sendMessage("Tell me something interesting")
        advanceUntilIdle()

        val streaming = session.conversation.value.messages.last()
        assertEquals(MessageRole.ASSISTANT, streaming.role)
        assertEquals(MessageStatus.Streaming, streaming.status)

        session.releaseMemory()
        advanceUntilIdle()

        val afterInterruption = session.conversation.value.messages.last()
        assertTrue(
            "Expected the streaming bubble to be marked Failed, got ${afterInterruption.status}",
            afterInterruption.status is MessageStatus.Failed,
        )
        assertEquals(
            DpsError.Session.Interrupted,
            (afterInterruption.status as MessageStatus.Failed).error,
        )
    }

    @Test
    fun `releaseMemory never throws even with nothing in flight`() = runTest {
        val session = sessionManager(HangingEngine(), this)

        // Calling it twice in a row must be safe — a second onTrimMemory
        // signal while already suspended is a real Android occurrence.
        session.releaseMemory()
        session.releaseMemory()
        advanceUntilIdle()

        assertTrue(session.sessionState.value is SessionState.Suspended)
    }
}
