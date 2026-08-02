package com.softwaremine.dps.di

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import com.softwaremine.dps.BuildConfig
import com.softwaremine.dps.ai.conversation.ConversationManager
import com.softwaremine.dps.ai.engine.DefaultAiEngine
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.ChatTemplateRegistry
import com.softwaremine.dps.ai.prompt.PromptManager
import com.softwaremine.dps.ai.session.AiSessionManager
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
 * assembled — which is a property worth more than it sounds, and one that
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
 * processing to every build — a real cost on a 4-core 1 GHz CPU (ADR-009).
 *
 * Adopting Hilt later is purely additive: annotate these factory methods,
 * delete the manual wiring. No class under `ai/`, `domain/` or `data/` changes.
 * Choosing manual DI now costs nothing and forecloses nothing.
 *
 * ## Where build-type divergence lives — and why it lives here
 * [runtimeProviders] is the *only* place in the codebase that branches on build
 * type. Debug builds get llama.cpp with an Ollama fallback; release builds get
 * llama.cpp alone, with no network runtime present at all.
 *
 * Confining that decision to the composition root means
 * [com.softwaremine.dps.ai.engine.DefaultAiEngine] contains no
 * `if (BuildConfig.DEBUG)` and behaves identically in both builds — it simply
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
     * [SupervisorJob] so a failed generation does not cancel unrelated work —
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
     * - **Debug** — llama.cpp first (it is what ships, so it must be exercised
     *   whenever present), Ollama as fallback for machines without the native
     *   build. This ordering means a developer with the library installed tests
     *   the real runtime by default rather than opting in.
     * - **Release** — llama.cpp only. A release build has no network runtime to
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

    val sessionManager: AiSessionManager by lazy {
        AiSessionManager(
            engine = aiEngine,
            modelManager = modelManager,
            conversationManager = conversationManager,
            promptManager = promptManager,
            responseParser = responseParser,
            dispatchers = dispatchers,
            logger = logger,
            scope = applicationScope,
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
