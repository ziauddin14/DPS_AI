package com.softwaremine.dps.ai.engine

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.core.result.flatMap
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.model.InstalledModel
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.runtime.RuntimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The production [AiEngine].
 *
 * ## Purpose
 * Binds the three things the AI subsystem needs — a way to obtain models
 * ([ModelManager]), a way to run them ([RuntimeProvider]), and a coherent state
 * machine ([AiState]) — into the single facade the rest of the app depends on.
 *
 * ## Runtime selection
 * Constructed with an *ordered* list of candidate runtimes and selects the
 * first that reports itself available. That ordering is the entire mechanism by
 * which development and production differ:
 *
 * - Debug builds supply `[llama.cpp, Ollama]`. If the native library is present
 *   it is used; otherwise development falls back to Ollama.
 * - Release builds supply `[llama.cpp]` alone. There is no network fallback,
 *   because a release build that could silently reach a remote runtime would
 *   breach the product's central privacy guarantee.
 *
 * The engine itself contains no build-type branching. Which runtimes exist is a
 * wiring decision made in [com.softwaremine.dps.di.AiContainer], which keeps
 * this class free of `if (BuildConfig.DEBUG)`.
 *
 * ## Concurrency
 * Lifecycle transitions are guarded by [lifecycleMutex] so that concurrent
 * `load`/`unload`/`shutdown` calls cannot interleave — an interleaved unload
 * during a load would free native memory the loader is still writing into,
 * which is a process death rather than an exception.
 *
 * Generation is *not* guarded here; it is serialised by
 * [DispatcherProvider.inference], which limits parallelism to one.
 *
 * ## Responsibilities
 * - Select an available runtime.
 * - Verify and load models; keep at most one resident (ADR-002).
 * - Publish [AiState].
 * - Delegate generation and token counting.
 *
 * ## Dependencies
 * `domain` + `core` only. No Android framework — this class is JVM-unit-testable
 * with fake runtimes and a fake model manager.
 */
class DefaultAiEngine(
    private val modelManager: ModelManager,
    private val runtimeCandidates: List<RuntimeProvider>,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) : AiEngine {

    private val _state = MutableStateFlow<AiState>(AiState.Idle)
    override val state: StateFlow<AiState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()

    @Volatile
    private var selectedRuntime: RuntimeProvider? = null

    @Volatile
    private var loadedModel: ModelDescriptor? = null

    override val activeModel: ModelDescriptor? get() = loadedModel

    /**
     * Selects a runtime and reports whether a usable model is present.
     *
     * Deliberately does **not** load the model — see [AiEngine.initialize] for
     * why that separation protects cold-start time.
     */
    override suspend fun initialize(): DpsResult<Unit> = lifecycleMutex.withLock {
        _state.value = AiState.CheckingModel

        val runtime = selectRuntime()
        if (runtime == null) {
            val error = DpsError.Runtime.Unavailable(
                runtimeId = "any",
                reason = "No inference runtime is available on this device.",
            )
            logger.e(TAG, error.message)
            _state.value = AiState.Unavailable(error)
            return@withLock DpsResult.Failure(error)
        }
        selectedRuntime = runtime
        logger.i(TAG, "Selected runtime: ${runtime.id.label}")

        val descriptor = modelManager.defaultModel()
        return@withLock when (val installed = modelManager.resolveInstalled(descriptor)) {
            is DpsResult.Success -> {
                logger.i(TAG, "Model '${descriptor.id}' present and verified.")
                // Present and intact, but not yet resident. Lazy load (ADR-002)
                // defers the 5-8 second cost until the AI is actually used.
                _state.value = AiState.Idle
                DpsResult.Ok
            }

            is DpsResult.Failure -> when (installed.error) {
                is DpsError.Model.NotInstalled,
                is DpsError.Model.ChecksumMismatch,
                is DpsError.Model.Corrupted,
                -> {
                    // All three are recoverable by acquiring the model, so all
                    // three lead the user to the same consented download.
                    logger.i(TAG, "Model requires installation: ${installed.error.message}")
                    _state.value = AiState.ModelRequired(descriptor)
                    DpsResult.Ok
                }

                else -> {
                    logger.e(TAG, "Initialisation failed: ${installed.error.message}")
                    _state.value = AiState.Unavailable(installed.error)
                    DpsResult.Failure(installed.error)
                }
            }
        }
    }

    override suspend fun loadModel(
        descriptor: ModelDescriptor,
        config: ModelConfig,
    ): DpsResult<Unit> = lifecycleMutex.withLock {
        val runtime = selectedRuntime
            ?: return@withLock DpsResult.Failure(
                DpsError.Runtime.Unavailable("any", "Engine not initialised."),
            )

        _state.value = AiState.LoadingModel(descriptor.id)

        // Verification before every load, not only after download. A corrupted
        // GGUF crashes the native runtime in a way no try/catch can intercept,
        // so this check is the last line of defence (ADR-003).
        return@withLock modelManager.resolveInstalled(descriptor)
            .flatMap { installed: InstalledModel ->
                // Single-slot residency: release the incumbent before admitting
                // a new model, or the device holds two models' worth of native
                // memory simultaneously and is killed (ADR-002).
                if (loadedModel != null) {
                    logger.i(TAG, "Unloading '${loadedModel?.id}' before load.")
                    runtime.unload()
                }

                val startedAt = System.currentTimeMillis()
                when (val result = runtime.load(installed.file, config)) {
                    is DpsResult.Success -> {
                        loadedModel = descriptor
                        val elapsed = System.currentTimeMillis() - startedAt
                        logger.i(TAG, "Loaded '${descriptor.id}' in ${elapsed}ms")
                        _state.value = AiState.Ready(descriptor.id, runtime.id)
                        DpsResult.Ok
                    }

                    is DpsResult.Failure -> {
                        loadedModel = null
                        logger.e(TAG, "Load failed: ${result.error.message}")
                        _state.value = AiState.Unavailable(result.error)
                        result
                    }
                }
            }
    }

    override suspend fun unloadModel(): DpsResult<Unit> = lifecycleMutex.withLock {
        val runtime = selectedRuntime ?: return@withLock DpsResult.Ok
        val result = runtime.unload()
        loadedModel = null
        _state.value = AiState.Idle
        logger.i(TAG, "Model unloaded.")
        return@withLock result
    }

    /**
     * Delegates to the selected runtime.
     *
     * The readiness check happens inside the [flow] builder rather than before
     * it, so that a call made while unloaded produces a
     * [CompletionChunk.Failed] element instead of throwing. Callers then have
     * exactly one failure channel to handle rather than two.
     */
    override fun generate(request: CompletionRequest): Flow<CompletionChunk> = flow {
        val runtime = selectedRuntime
        if (runtime == null || loadedModel == null) {
            emit(CompletionChunk.Failed(DpsError.Runtime.NotLoaded))
            return@flow
        }
        runtime.generate(request).collect { chunk -> emit(chunk) }
    }.flowOn(dispatchers.inference)

    override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> =
        withContext(dispatchers.inference) {
            var failure: DpsError? = null
            var completion: AiCompletion? = null

            generate(request).collect { chunk ->
                when (chunk) {
                    is CompletionChunk.Token -> Unit // Accumulated by the runtime.
                    is CompletionChunk.Completed -> completion = chunk.completion
                    is CompletionChunk.Failed -> failure = chunk.error
                }
            }

            val error = failure
            val result = completion
            when {
                error != null -> DpsResult.Failure(error)
                result != null -> DpsResult.Success(result)
                else -> DpsResult.Failure(
                    DpsError.Runtime.GenerationFailed("Stream ended without a terminal chunk."),
                )
            }
        }

    override suspend fun tokenCount(text: String): DpsResult<Int> {
        val runtime = selectedRuntime ?: return DpsResult.Failure(DpsError.Runtime.NotLoaded)
        if (loadedModel == null) return DpsResult.Failure(DpsError.Runtime.NotLoaded)
        return runtime.tokenCount(text)
    }

    /**
     * Releases everything. Must never throw — it is called from
     * `onTrimMemory`, where failing converts a recoverable memory trim into a
     * crash.
     */
    override suspend fun shutdown() {
        lifecycleMutex.withLock {
            try {
                selectedRuntime?.unload()
            } catch (throwable: Throwable) {
                logger.w(TAG, "Ignoring error during shutdown.", throwable)
            }
            loadedModel = null
            selectedRuntime = null
            _state.value = AiState.Idle
            logger.i(TAG, "Engine shut down.")
        }
    }

    /** Returns the first candidate that reports itself usable, or `null`. */
    private suspend fun selectRuntime(): RuntimeProvider? =
        withContext(dispatchers.io) {
            runtimeCandidates.firstOrNull { candidate ->
                val available = candidate.isAvailable()
                logger.d(TAG, "Runtime ${candidate.id.label} available=$available")
                available
            }
        }

    private companion object {
        const val TAG = "AiEngine"
    }
}
