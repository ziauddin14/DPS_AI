package com.softwaremine.dps.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.softwaremine.dps.BuildConfig
import com.softwaremine.dps.ai.conversation.ConversationManager
import com.softwaremine.dps.ai.engine.DefaultAiEngine
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.ChatTemplateRegistry
import com.softwaremine.dps.ai.prompt.PromptManager
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
import com.softwaremine.dps.ai.secretary.SecretaryOrchestrator
import com.softwaremine.dps.ai.session.AiSessionManager
import com.softwaremine.dps.ai.tool.DefaultToolExecutor
import com.softwaremine.dps.ai.tool.DefaultToolRegistry
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.contacts.AndroidContactRepository
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.data.android.productivity.AndroidActionItemStore
import com.softwaremine.dps.data.android.productivity.AndroidMeetingNoteStore
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.data.android.productivity.AndroidWorkLogStore
import com.softwaremine.dps.data.android.reminder.ReminderScheduler
import com.softwaremine.dps.data.android.reminder.ReminderStore
import com.softwaremine.dps.data.android.tool.AndroidActionItemTool
import com.softwaremine.dps.data.android.tool.AndroidCalendarTool
import com.softwaremine.dps.data.android.tool.AndroidContactsTool
import com.softwaremine.dps.data.android.tool.AndroidMeetingNoteTool
import com.softwaremine.dps.data.android.tool.AndroidNotificationTool
import com.softwaremine.dps.data.android.tool.AndroidReminderTool
import com.softwaremine.dps.data.android.tool.AndroidReportTool
import com.softwaremine.dps.data.android.tool.AndroidTaskTool
import com.softwaremine.dps.data.android.tool.AndroidToolCatalog
import com.softwaremine.dps.data.android.tool.AndroidWorkLogTool
import com.softwaremine.dps.data.android.tool.PrepareEmailTool
import com.softwaremine.dps.data.android.tool.PrepareWhatsAppMessageTool
import com.softwaremine.dps.data.android.voice.AndroidSpeechRecognizer
import com.softwaremine.dps.data.android.voice.AndroidTextToSpeech
import com.softwaremine.dps.ai.voice.VoiceModeController
import com.softwaremine.dps.domain.contact.ContactRepository
import com.softwaremine.dps.domain.contact.ContactResolver
import com.softwaremine.dps.domain.productivity.report.ReportGenerator
import com.softwaremine.dps.domain.tool.ToolExecutor
import com.softwaremine.dps.domain.tool.ToolRegistry
import com.softwaremine.dps.core.concurrency.DefaultDispatcherProvider
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.model.ChecksumVerifier
import com.softwaremine.dps.data.model.DefaultModelManager
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.data.model.ModelDownloader
import com.softwaremine.dps.data.model.ModelStorage
import com.softwaremine.dps.data.runtime.llamacpp.LlamaCppRuntimeProvider
import com.softwaremine.dps.data.runtime.ollama.OllamaRuntimeProvider
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.runtime.RuntimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The application's composition root.
 *
 * ## Purpose
 * Constructs the object graph in one explicit, readable place. Reading this
 * file top to bottom is the fastest way to understand how the AI subsystem is
 * assembled â€” which is a property worth more than it sounds, and one that
 * annotation-driven DI actively removes.
 *
 * ## Why no DI framework (ADR-006)
 * The brief calls for "Dependency Injection **Ready**", not "a DI framework
 * installed", and that distinction is worth honouring literally.
 *
 * Every class in this codebase takes its collaborators as constructor
 * parameters and constructs none of them itself. That is what makes a codebase
 * injectable; a framework merely automates the wiring. With a graph this size,
 * hand-wiring costs one readable file, while Hilt would add KSP annotation
 * processing to every build â€” a real cost on a 4-core 1 GHz CPU (ADR-009).
 *
 * Adopting Hilt later is purely additive: annotate these factory methods,
 * delete the manual wiring. No class under `ai/`, `domain/` or `data/` changes.
 * Choosing manual DI now costs nothing and forecloses nothing.
 *
 * ## Where build-type divergence lives â€” and why it lives here
 * [runtimeProviders] is the *only* place in the codebase that branches on build
 * type. Debug builds get llama.cpp with an Ollama fallback; release builds get
 * llama.cpp alone, with no network runtime present at all.
 *
 * Confining that decision to the composition root means
 * [com.softwaremine.dps.ai.engine.DefaultAiEngine] contains no
 * `if (BuildConfig.DEBUG)` and behaves identically in both builds â€” it simply
 * receives a different list. Testing it therefore tests the shipping code path.
 *
 * ## Lifetime
 * Held by [com.softwaremine.dps.DpsApplication] for the process lifetime.
 * Everything here is a singleton; the AI subsystem is inherently
 * single-instance because the model is.
 *
 * ## Dependencies
 * Android [Context] for storage paths and RAM interrogation. This is the only
 * class outside `ui/` that touches the Android framework at all.
 */
class AiContainer(private val applicationContext: Context) {

    // ---------------------------------------------------------------- core

    val dispatchers: DispatcherProvider by lazy { DefaultDispatcherProvider() }

    val logger: DpsLogger by lazy { AndroidDpsLogger() }

    /**
     * Application-scoped coroutine scope.
     *
     * [SupervisorJob] so a failed generation does not cancel unrelated work â€”
     * one model error must not take down the session manager with it.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    private val json: Json by lazy {
        Json {
            // Ollama adds response fields between versions. Ignoring unknowns
            // means a server upgrade does not break the development runtime.
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    // ---------------------------------------------------------------- data

    /**
     * Model storage under app-private internal storage.
     *
     * Not external storage: that is world-readable, and a 1.5 GB artifact the
     * user believes is private must not live where any app can read it
     * (ADR-003).
     */
    private val modelStorage: ModelStorage by lazy {
        ModelStorage(File(applicationContext.filesDir, MODELS_DIRECTORY))
    }

    private val checksumVerifier: ChecksumVerifier by lazy { ChecksumVerifier(dispatchers) }

    private val modelDownloader: ModelDownloader by lazy {
        ModelDownloader(
            httpClient = ModelDownloader.defaultClient(),
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    val modelManager: ModelManager by lazy {
        DefaultModelManager(
            storage = modelStorage,
            downloader = modelDownloader,
            verifier = checksumVerifier,
            dispatchers = dispatchers,
            logger = logger,
            deviceTotalRamBytes = ::queryTotalDeviceRam,
        )
    }

    // ------------------------------------------------------------- runtime

    /**
     * Candidate runtimes, in selection order.
     *
     * The engine takes the first that reports itself available, so order is the
     * policy:
     *
     * - **Debug** â€” llama.cpp first (it is what ships, so it must be exercised
     *   whenever present), Ollama as fallback for machines without the native
     *   build. This ordering means a developer with the library installed tests
     *   the real runtime by default rather than opting in.
     * - **Release** â€” llama.cpp only. A release build has no network runtime to
     *   fall back to, by construction.
     */
    private val runtimeProviders: List<RuntimeProvider> by lazy {
        buildList {
            add(LlamaCppRuntimeProvider(dispatchers = dispatchers, logger = logger))

            if (BuildConfig.DEBUG && BuildConfig.OLLAMA_BASE_URL.isNotBlank()) {
                add(
                    OllamaRuntimeProvider(
                        baseUrl = BuildConfig.OLLAMA_BASE_URL,
                        modelTag = DEVELOPMENT_OLLAMA_MODEL_TAG,
                        httpClient = OllamaRuntimeProvider.defaultClient(),
                        json = json,
                        dispatchers = dispatchers,
                        logger = logger,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ ai

    val aiEngine: AiEngine by lazy {
        DefaultAiEngine(
            modelManager = modelManager,
            runtimeCandidates = runtimeProviders,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    private val chatTemplates: ChatTemplateRegistry by lazy { ChatTemplateRegistry() }

    private val promptManager: PromptManager by lazy {
        PromptManager(templates = chatTemplates, logger = logger)
    }

    private val responseParser: ResponseParser by lazy { ResponseParser() }

    val conversationManager: ConversationManager by lazy { ConversationManager() }

    // ------------------------------------------------------- android tools
    //
    // Day 05 Phase 1: foundation only. Tools are declared and registered;
    // none implements Android behaviour yet.

    /**
     * The permission authority. Query-only until a UI attaches a
     * [com.softwaremine.dps.domain.permission.PermissionRequestHost].
     */
    val permissionManager: AndroidPermissionManager by lazy {
        AndroidPermissionManager(
            context = applicationContext,
            logger = logger,
        )
    }

    /** Shared notification presenter â€” used by the tool and by the alarm receiver. */
    val notificationPresenter: NotificationPresenter by lazy {
        NotificationPresenter(applicationContext, logger).also { it.ensureAllChannels() }
    }

    private val calendarWriter by lazy { CalendarWriter(applicationContext, logger) }
    private val reminderStore by lazy { ReminderStore(applicationContext, logger) }
    private val reminderScheduler by lazy {
        ReminderScheduler(applicationContext, logger, permissionManager)
    }

    // --- Phase C: communication layer ---

    private val contactRepository: ContactRepository by lazy {
        AndroidContactRepository(applicationContext, dispatchers, logger)
    }

    /**
     * Shared by the contacts, WhatsApp and email tools.
     *
     * One resolver, so all three agree on who "Abdul" is. Two tools disagreeing
     * about that would mean a private message reaching different people
     * depending on which path the assistant took.
     */
    private val contactResolver by lazy { ContactResolver() }

    private val intentLauncher by lazy { IntentLauncher(applicationContext, logger) }

    // --- Day 06: productivity secretary ---
    //
    // SharedPreferences-plus-JSON, mirroring ReminderStore (see AndroidTaskStore's
    // doc for why this is the smallest reliable solution for this data volume).

    private val taskStore by lazy { AndroidTaskStore(applicationContext, logger) }
    private val workLogStore by lazy { AndroidWorkLogStore(applicationContext, logger) }
    private val meetingNoteStore by lazy { AndroidMeetingNoteStore(applicationContext, logger) }
    private val actionItemStore by lazy { AndroidActionItemStore(applicationContext, logger) }

    /**
     * The tool catalogue.
     *
     * Phase B implementations are passed in and take precedence; everything
     * else remains a declaration returning `Unsupported`.
     */
    val toolRegistry: ToolRegistry by lazy {
        DefaultToolRegistry(logger).also { registry ->
            AndroidToolCatalog.registerAll(
                registry = registry,
                implemented = listOf(
                    // Phase B
                    AndroidNotificationTool(notificationPresenter),
                    AndroidCalendarTool(calendarWriter),
                    AndroidReminderTool(reminderScheduler, reminderStore),
                    // Phase C â€” all three share one resolver and one launcher
                    AndroidContactsTool(contactRepository, contactResolver),
                    PrepareWhatsAppMessageTool(contactRepository, contactResolver, intentLauncher),
                    PrepareEmailTool(contactRepository, contactResolver, intentLauncher),
                    // Day 06 â€” productivity secretary
                    AndroidTaskTool(taskStore),
                    AndroidWorkLogTool(workLogStore),
                    AndroidMeetingNoteTool(meetingNoteStore),
                    AndroidActionItemTool(actionItemStore),
                    AndroidReportTool(taskStore, workLogStore, meetingNoteStore, actionItemStore, ReportGenerator()),
                ),
            )
        }
    }

    /**
     * The single gate between the AI layer and Android.
     *
     * `Build.VERSION.SDK_INT` is read *here* rather than inside the executor.
     * The composition root is already the Android-aware layer; passing the API
     * level in keeps `ai/` free of platform imports and lets the executor be
     * tested on the JVM against any API level, including ones this device is
     * not.
     */
    val toolExecutor: ToolExecutor by lazy {
        DefaultToolExecutor(
            registry = toolRegistry,
            permissionManager = permissionManager,
            dispatchers = dispatchers,
            logger = logger,
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    // ------------------------------------------- Phase D: tool orchestration

    /**
     * Connects the model to the tool framework.
     *
     * Every component below it is pure Kotlin, so the routing, clarification and
     * response logic is JVM-testable; only this class needs a live engine.
     */
    val toolOrchestrator: ToolOrchestrator by lazy {
        ToolOrchestrator(
            engine = aiEngine,
            executor = toolExecutor,
            registry = toolRegistry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(),
            responses = ToolResponseGenerator(),
            logger = logger,
        )
    }

    // ------------------------------------------- Phase E: AI Secretary layer

    /**
     * The memory-aware, multi-turn layer above [toolOrchestrator].
     *
     * Every dependency here is pure Kotlin; only [toolOrchestrator] beneath it
     * touches the model.
     */
    val secretaryOrchestrator: SecretaryOrchestrator by lazy {
        SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            logger = logger,
        )
    }

    val sessionManager: AiSessionManager by lazy {
        AiSessionManager(
            engine = aiEngine,
            modelManager = modelManager,
            conversationManager = conversationManager,
            promptManager = promptManager,
            responseParser = responseParser,
            secretaryOrchestrator = secretaryOrchestrator,
            permissionManager = permissionManager,
            dispatchers = dispatchers,
            logger = logger,
            scope = applicationScope,
        )
    }

    // ------------------------------------------------- Day 07: voice

    /**
     * The dedicated voice-input owner (architecture rule 10). Constructed
     * once and reused — see [AndroidSpeechRecognizer]'s own doc for why a
     * fresh platform recognizer is still created per listening session
     * internally.
     */
    val voiceRecognizer: AndroidSpeechRecognizer by lazy {
        AndroidSpeechRecognizer(
            context = applicationContext,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    /**
     * The dedicated voice-output owner (architecture rule 11). Process-scoped
     * so its underlying `TextToSpeech` engine survives Activity recreation
     * without a re-initialisation cost.
     */
    val speechSynthesizer: AndroidTextToSpeech by lazy {
        AndroidTextToSpeech(context = applicationContext, logger = logger)
    }

    /**
     * Drives one tap-to-talk turn. Depends on [sessionManager] rather than
     * being depended upon by it — voice is a caller of the existing pipeline,
     * never a second one (architecture rules 14, 15).
     */
    val voiceModeController: VoiceModeController by lazy {
        VoiceModeController(
            recognizer = voiceRecognizer,
            synthesizer = speechSynthesizer,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            scope = applicationScope,
            logger = logger,
        )
    }

    /**
     * Total physical RAM in bytes.
     *
     * Used for the pre-download preflight in
     * [com.softwaremine.dps.domain.model.ModelManager.canInstall]. Total rather
     * than available memory is the right input: available fluctuates second to
     * second, and a decision about whether a device can *ever* run this model
     * must not depend on what happened to be open when the user tapped install.
     */
    private fun queryTotalDeviceRam(): Long {
        val activityManager = applicationContext.getSystemService<ActivityManager>()
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    private companion object {
        const val MODELS_DIRECTORY = "models"

        /**
         * The Ollama tag pulled on the developer's machine:
         * `ollama pull qwen2.5:1.5b-instruct-q4_K_M`
         *
         * Chosen to match [ModelCatalog.DEFAULT] as closely as Ollama's tagging
         * allows, so development and production exercise the same weights.
         */
        const val DEVELOPMENT_OLLAMA_MODEL_TAG = "qwen2.5:1.5b-instruct-q4_K_M"
    }
}
