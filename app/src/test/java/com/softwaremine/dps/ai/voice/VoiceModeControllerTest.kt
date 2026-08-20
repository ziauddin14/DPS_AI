package com.softwaremine.dps.ai.voice

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
import com.softwaremine.dps.ai.session.AiSessionManager
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
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
import com.softwaremine.dps.domain.voice.SpeechOutcome
import com.softwaremine.dps.domain.voice.SpeechSynthesizer
import com.softwaremine.dps.domain.voice.VoiceCaptureOutcome
import com.softwaremine.dps.domain.voice.VoiceMode
import com.softwaremine.dps.domain.voice.VoiceRecognizer
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
 * Verification of [VoiceModeController] — Day 07's voice conversation state
 * machine and its promise that a recognized transcript reaches the *real*
 * secretary pipeline rather than a parallel one.
 *
 * ## Why a real [AiSessionManager], not a fake
 * [VoiceModeController] depends on the concrete class directly (mirroring
 * [com.softwaremine.dps.ui.chat.ChatViewModel]'s own existing dependency —
 * there has never been an interface seam here). Verifying "voice text goes
 * through the same pipeline as typed text" therefore means constructing the
 * real thing, with only its leaf runtime ([AiEngine], [ModelManager])
 * scripted — the same trade [ai.secretary.SecretaryOrchestratorTest] already
 * makes for the layer below this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceModeControllerTest {

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

    /** Always reports the model loaded and ready; classification always resolves to conversation. */
    private inner class ConversationalEngine(private val replyText: String = "Hello there.") : AiEngine {
        override val state: StateFlow<AiState> =
            MutableStateFlow(AiState.Ready(descriptor.id, RuntimeId.LLAMA_CPP))
        override val activeModel: ModelDescriptor = descriptor

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(d: ModelDescriptor, config: ModelConfig): DpsResult<Unit> =
            DpsResult.Success(Unit)
        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)

        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = flow {
            emit(CompletionChunk.Token(replyText))
            emit(
                CompletionChunk.Completed(
                    AiCompletion(
                        text = replyText,
                        finishReason = FinishReason.END_OF_TURN,
                        usage = TokenUsage(promptTokens = 10, completionTokens = 4),
                        durationMillis = 5,
                    ),
                ),
            )
        }

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> =
            // The classification pass: always "conversation", so
            // SecretaryOrchestrator.handle() falls through to the ordinary
            // streaming pipeline that generate() above drives.
            DpsResult.Success(
                AiCompletion(
                    text = """{"intent":"conversation"}""",
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 20, completionTokens = 5),
                    durationMillis = 5,
                ),
            )

        override suspend fun tokenCount(text: String): DpsResult<Int> = DpsResult.Success(text.length / 4)
        override suspend fun shutdown() = Unit
    }

    private class StubModelManager : ModelManager {
        override val installState: StateFlow<ModelInstallState> =
            MutableStateFlow(ModelInstallState.NotInstalled)
        override fun catalog(): List<ModelDescriptor> = listOf(ModelCatalog.DEFAULT)
        override fun defaultModel(): ModelDescriptor = ModelCatalog.DEFAULT
        override fun findDescriptor(modelId: String): DpsResult<ModelDescriptor> =
            DpsResult.Success(ModelCatalog.DEFAULT)
        override suspend fun installedModels(): DpsResult<List<InstalledModel>> =
            error("not used in this test")
        override suspend fun resolveInstalled(descriptor: ModelDescriptor): DpsResult<InstalledModel> =
            error("not used in this test")
        override suspend fun canInstall(descriptor: ModelDescriptor): DpsResult<Unit> =
            error("not used in this test")
        override fun install(descriptor: ModelDescriptor): Flow<ModelInstallState> = emptyFlow()
        override suspend fun verify(descriptor: ModelDescriptor): DpsResult<Boolean> =
            error("not used in this test")
        override suspend fun delete(descriptor: ModelDescriptor): DpsResult<Unit> =
            error("not used in this test")
        override suspend fun storageStats(): DpsResult<ModelStorageStats> =
            error("not used in this test")
        override suspend fun clearPartialDownloads(): DpsResult<Long> =
            error("not used in this test")
    }

    private class FakePermissions(
        private var recordAudioState: PermissionState = PermissionState.GRANTED,
    ) : PermissionManager {
        override fun state(permission: DpsPermission): PermissionState =
            if (permission == DpsPermission.RECORD_AUDIO) recordAudioState else PermissionState.GRANTED
        override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
        override fun missing(permissions: Set<DpsPermission>) =
            permissions.filterNot { state(it).isUsable }.toSet()

        /** Simulates the user granting on the live dialog exactly once. */
        var grantOnRequest: Boolean = false

        override suspend fun request(permissions: Set<DpsPermission>): Map<DpsPermission, PermissionState> {
            if (DpsPermission.RECORD_AUDIO in permissions && grantOnRequest) {
                recordAudioState = PermissionState.GRANTED
            }
            return states(permissions)
        }
    }

    private class FakeVoiceRecognizer(private val script: List<VoiceCaptureOutcome>) : VoiceRecognizer {
        var listenCallCount = 0
            private set

        override fun listen(): Flow<VoiceCaptureOutcome> {
            listenCallCount++
            return flow { script.forEach { emit(it) } }
        }

        override fun isAvailable(): Boolean = true
    }

    private class FakeSynthesizer(private val outcome: SpeechOutcome = SpeechOutcome.Completed) : SpeechSynthesizer {
        val spoken = mutableListOf<String>()
        var stopCalled = false

        override suspend fun speak(text: String): SpeechOutcome {
            spoken += text
            return outcome
        }

        override fun stop() {
            stopCalled = true
        }

        override fun isAvailable(): Boolean = true
        override fun shutdown() = Unit
    }

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /**
     * M3-B: this file tests voice mode, not persistence, so
     * [SecretaryOrchestrator]'s now-required [PersistentMemoryStore] is
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
            persistentMemoryStore = PersistentMemoryStore(NoOpSharedPreferences(), silentLogger),
            persistentPreferenceStore = PersistentPreferenceStore(NoOpSharedPreferences(), silentLogger),
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

    // -----------------------------------------------------------------
    // Permission
    // -----------------------------------------------------------------

    @Test
    fun `permission denied and never granted ends the turn in Error, never touches the recognizer`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(listOf(VoiceCaptureOutcome.Listening))
        val permissions = FakePermissions(recordAudioState = PermissionState.DENIED)
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, permissions, this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        assertTrue(controller.mode.value is VoiceMode.Error)
        assertEquals(0, recognizer.listenCallCount)
    }

    @Test
    fun `permission granted on request proceeds to listen`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(
            listOf(VoiceCaptureOutcome.Listening, VoiceCaptureOutcome.Cancelled),
        )
        val permissions = FakePermissions(recordAudioState = PermissionState.DENIED).apply { grantOnRequest = true }
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, permissions, this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        assertEquals(1, recognizer.listenCallCount)
    }

    // -----------------------------------------------------------------
    // Terminal outcomes each map to a distinct, honest VoiceMode
    // -----------------------------------------------------------------

    @Test
    fun `no speech produces an Error mode, never a silent Idle`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(listOf(VoiceCaptureOutcome.Listening, VoiceCaptureOutcome.NoSpeech))
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        assertTrue(controller.mode.value is VoiceMode.Error)
    }

    @Test
    fun `recognizer unavailable is reported, not silently ignored`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(
            listOf(VoiceCaptureOutcome.Unavailable("No recognition service present.")),
        )
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        val mode = controller.mode.value
        assertTrue(mode is VoiceMode.Error)
        assertEquals("No recognition service present.", (mode as VoiceMode.Error).message)
    }

    @Test
    fun `a recognition error is surfaced as an Error mode`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(
            listOf(VoiceCaptureOutcome.Error("Voice recognition failed on this attempt.", retryable = true)),
        )
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        assertTrue(controller.mode.value is VoiceMode.Error)
    }

    @Test
    fun `cancellation from the recognizer returns to Idle`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(listOf(VoiceCaptureOutcome.Listening, VoiceCaptureOutcome.Cancelled))
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        assertEquals(VoiceMode.Idle, controller.mode.value)
    }

    // -----------------------------------------------------------------
    // The full turn: voice becomes text becomes the real pipeline's reply
    // -----------------------------------------------------------------

    @Test
    fun `a final result is sent through the real session manager and the reply is spoken`() = runTest {
        val engine = ConversationalEngine(replyText = "Wa Alaikum Assalam.")
        val session = sessionManager(engine, this)
        val recognizer = FakeVoiceRecognizer(
            listOf(VoiceCaptureOutcome.Listening, VoiceCaptureOutcome.FinalResult("Assalam o Alaikum DPS", offlineRecognition = true)),
        )
        val synthesizer = FakeSynthesizer()
        val controller = VoiceModeController(recognizer, synthesizer, session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()

        // The user's turn reached the same conversation the text composer
        // would have populated — no second pipeline, no separate transcript.
        assertTrue(session.conversation.value.messages.any { it.content == "Assalam o Alaikum DPS" })
        assertEquals(listOf("Wa Alaikum Assalam."), synthesizer.spoken)
        assertEquals(VoiceMode.Idle, controller.mode.value)
    }

    @Test
    fun `cancel() stops synthesis and returns to Idle immediately`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        val recognizer = FakeVoiceRecognizer(listOf(VoiceCaptureOutcome.Listening))
        val synthesizer = FakeSynthesizer()
        val controller = VoiceModeController(recognizer, synthesizer, session, FakePermissions(), this, silentLogger)

        controller.startListening()
        advanceUntilIdle()
        controller.cancel()

        assertEquals(VoiceMode.Idle, controller.mode.value)
        assertTrue(synthesizer.stopCalled)
    }

    @Test
    fun `starting a turn while one is already active is a no-op`() = runTest {
        val session = sessionManager(ConversationalEngine(), this)
        // A recognizer that never terminates on its own within this test's
        // timeframe — startListening() should refuse a second concurrent turn.
        val recognizer = FakeVoiceRecognizer(listOf(VoiceCaptureOutcome.Listening))
        val controller = VoiceModeController(recognizer, FakeSynthesizer(), session, FakePermissions(), this, silentLogger)

        controller.startListening()
        controller.startListening()
        advanceUntilIdle()

        // Only one listening session should ever have been started for the
        // first call; the flow used here completes immediately after
        // Listening, so a second, distinct listen() call would indicate the
        // guard failed to prevent an overlapping turn.
        assertEquals(1, recognizer.listenCallCount)
    }
}
