package com.softwaremine.dps.data.runtime.llamacpp

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.runtime.RuntimeCapabilities
import com.softwaremine.dps.domain.runtime.RuntimeId
import com.softwaremine.dps.domain.runtime.RuntimeProvider
import com.softwaremine.dps.domain.runtime.RuntimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The production runtime: in-process llama.cpp via JNI.
 *
 * ## Purpose
 * The runtime DPS ships. Everything happens inside the app process with no
 * network involved, which is what makes the product's offline and privacy
 * guarantees structural rather than promised.
 *
 * ## Availability
 * Reports unavailable when the native library is absent — which is the case on
 * this build, since NDK/CMake are deferred to Day 03. That is deliberate
 * capability detection, not a gap: the same path serves a real production case
 * (a device ABI with no bundled `.so`) and remains exercised afterwards.
 *
 * ## Streaming via [callbackFlow]
 * Native generation is a blocking loop that pushes tokens through a callback.
 * [callbackFlow] is the correct bridge from a push-based callback to a
 * cold [Flow], because it provides a channel that is safe to send into from the
 * native thread and an [awaitClose] hook for cancellation.
 *
 * Cancellation matters here beyond responsiveness. Without wiring
 * [LlamaCppBridge.cancel] into [awaitClose], a user who navigates away leaves
 * native inference running to completion — burning battery and holding the
 * inference dispatcher against the next request.
 *
 * ## Memory
 * Holds at most one handle ([modelHandle]). [load] frees any incumbent first,
 * enforcing ADR-002's single-resident-model rule at the lowest level, where it
 * cannot be bypassed by a caller.
 *
 * ## Dependencies
 * [LlamaCppBridge], `domain` types, coroutines.
 */
class LlamaCppRuntimeProvider(
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) : RuntimeProvider {

    override val id: RuntimeId = RuntimeId.LLAMA_CPP

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsTokenCount = true,
        // GPU offload is off for the MVP: Android Vulkan support is
        // device-fragmented, and getting it wrong is a crash rather than a
        // slowdown. Revisit with measurement (ADR-002).
        supportsGpuOffload = false,
        isFullyOffline = true,
        // Available in llama.cpp, not yet wired through the bridge. Day 03.
        supportsGrammarConstraints = false,
    )

    private val _status = MutableStateFlow<RuntimeStatus>(
        RuntimeStatus.Unavailable("Not yet probed."),
    )
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    /** Native handle, or `0` when nothing is loaded. Never exposed. */
    @Volatile
    private var modelHandle: Long = 0L

    override suspend fun isAvailable(): Boolean {
        val available = LlamaCppBridge.isAvailable
        _status.value = if (available) {
            RuntimeStatus.Available
        } else {
            val reason = LlamaCppBridge.unavailableReason ?: "Native library unavailable."
            logger.i(TAG, "llama.cpp unavailable: $reason")
            RuntimeStatus.Unavailable(reason)
        }
        return available
    }

    override suspend fun load(modelFile: File, config: ModelConfig): DpsResult<Unit> =
        withContext(dispatchers.inference) {
            if (!LlamaCppBridge.isAvailable) {
                return@withContext DpsResult.Failure(
                    DpsError.Runtime.Unavailable(
                        runtimeId = id.label,
                        reason = LlamaCppBridge.unavailableReason ?: "Native library unavailable.",
                    ),
                )
            }
            if (!modelFile.exists()) {
                return@withContext DpsResult.Failure(
                    DpsError.Runtime.LoadFailed(
                        modelId = modelFile.name,
                        reason = "Model file does not exist: ${modelFile.absolutePath}",
                    ),
                )
            }

            // Single-slot residency, enforced where it cannot be bypassed.
            if (modelHandle != 0L) {
                LlamaCppBridge.freeModel(modelHandle)
                modelHandle = 0L
            }

            _status.value = RuntimeStatus.Loading(modelFile.name)

            val handle = try {
                LlamaCppBridge.loadModel(
                    modelPath = modelFile.absolutePath,
                    contextLength = config.contextLength,
                    threadCount = config.threadCount,
                    gpuLayers = config.gpuLayers,
                )
            } catch (error: UnsatisfiedLinkError) {
                val failure = DpsError.Runtime.LoadFailed(
                    modelId = modelFile.name,
                    reason = "Native symbol missing: ${error.message}",
                    cause = error,
                )
                _status.value = RuntimeStatus.Failed(failure)
                return@withContext DpsResult.Failure(failure)
            }

            if (handle == 0L) {
                val failure = DpsError.Runtime.LoadFailed(
                    modelId = modelFile.name,
                    reason = "Native loader returned a null handle.",
                )
                _status.value = RuntimeStatus.Failed(failure)
                return@withContext DpsResult.Failure(failure)
            }

            modelHandle = handle
            _status.value = RuntimeStatus.Loaded(modelFile.name, System.currentTimeMillis())
            logger.i(TAG, "Loaded ${modelFile.name} (ctx=${config.contextLength})")
            DpsResult.Ok
        }

    override suspend fun unload(): DpsResult<Unit> = withContext(dispatchers.inference) {
        if (modelHandle == 0L) return@withContext DpsResult.Ok

        _status.value = RuntimeStatus.Unloading
        try {
            LlamaCppBridge.freeModel(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            // Nothing useful remains to do; the process is losing native state
            // regardless. Logged rather than propagated because unload is
            // called from memory-pressure handlers where throwing turns a
            // recoverable trim into a crash.
            logger.w(TAG, "freeModel failed during unload.", error)
        }
        modelHandle = 0L
        _status.value = RuntimeStatus.Available
        logger.i(TAG, "Model unloaded.")
        DpsResult.Ok
    }

    /**
     * ## Structure note
     * The native [LlamaCppBridge.generate] call **blocks** until generation
     * finishes. It is therefore dispatched onto a child coroutine rather than
     * run directly in the [callbackFlow] builder.
     *
     * This ordering is load-bearing. Running the blocking call inline would
     * mean [awaitClose] is not reached — and so cancellation is not registered
     * — until after generation has already completed, making it useless
     * precisely when it is needed. Launching first and awaiting immediately
     * ensures a collector that cancels mid-generation reaches
     * [LlamaCppBridge.cancel] while native work is still running.
     */
    override fun generate(request: CompletionRequest): Flow<CompletionChunk> = callbackFlow {
        val handle = modelHandle
        if (handle == 0L) {
            trySend(CompletionChunk.Failed(DpsError.Runtime.NotLoaded))
            close()
            return@callbackFlow
        }

        val worker = launch(dispatchers.inference) {
            val startedAt = System.currentTimeMillis()
            val accumulated = StringBuilder()
            var tokenCount = 0

            val callback = LlamaCppBridge.TokenCallback { text ->
                accumulated.append(text)
                tokenCount++
                // trySendBlocking, not trySend: this is invoked from inside the
                // native generation loop, and silently dropping a token under
                // backpressure would corrupt the response. Blocking briefly is
                // the correct trade.
                trySendBlocking(CompletionChunk.Token(text)).isSuccess
            }

            val finishCode = try {
                LlamaCppBridge.generate(
                    handle = handle,
                    prompt = request.prompt,
                    maxTokens = request.config.maxOutputTokens,
                    temperature = request.config.temperature,
                    topP = request.config.topP,
                    topK = request.config.topK,
                    repeatPenalty = request.config.repeatPenalty,
                    seed = request.config.seed ?: RANDOM_SEED,
                    stopSequences = request.stopSequences.toTypedArray(),
                    callback = callback,
                )
            } catch (error: Throwable) {
                trySend(
                    CompletionChunk.Failed(
                        DpsError.Runtime.GenerationFailed(
                            reason = error.message ?: "Native generation failed.",
                            cause = error,
                        ),
                    ),
                )
                close()
                return@launch
            }

            if (finishCode == LlamaCppBridge.FinishCode.ERROR) {
                trySend(
                    CompletionChunk.Failed(
                        DpsError.Runtime.GenerationFailed("Native generation reported an error."),
                    ),
                )
            } else {
                val promptTokens = LlamaCppBridge.tokenCount(handle, request.prompt)
                    .coerceAtLeast(0)
                trySend(
                    CompletionChunk.Completed(
                        AiCompletion(
                            text = accumulated.toString(),
                            finishReason = finishCode.toFinishReason(),
                            usage = TokenUsage(
                                promptTokens = promptTokens,
                                completionTokens = tokenCount,
                            ),
                            durationMillis = System.currentTimeMillis() - startedAt,
                        ),
                    ),
                )
            }
            close()
        }

        awaitClose {
            // Signals the native loop to stop between tokens. Without this, a
            // collector that walks away leaves inference running to completion,
            // draining battery on output nobody will read.
            runCatching { LlamaCppBridge.cancel(handle) }
            worker.cancel()
        }
    }

    override suspend fun tokenCount(text: String): DpsResult<Int> =
        withContext(dispatchers.inference) {
            val handle = modelHandle
            if (handle == 0L) return@withContext DpsResult.Failure(DpsError.Runtime.NotLoaded)

            val count = try {
                LlamaCppBridge.tokenCount(handle, text)
            } catch (error: UnsatisfiedLinkError) {
                return@withContext DpsResult.Failure(
                    DpsError.Runtime.GenerationFailed("tokenCount unavailable.", error),
                )
            }

            if (count < 0) {
                DpsResult.Failure(DpsError.Runtime.GenerationFailed("Tokenizer returned $count."))
            } else {
                DpsResult.Success(count)
            }
        }

    private fun Int.toFinishReason(): FinishReason = when (this) {
        LlamaCppBridge.FinishCode.STOP_SEQUENCE -> FinishReason.STOP_SEQUENCE
        LlamaCppBridge.FinishCode.MAX_TOKENS -> FinishReason.MAX_TOKENS
        LlamaCppBridge.FinishCode.CANCELLED -> FinishReason.CANCELLED
        else -> FinishReason.END_OF_TURN
    }

    private companion object {
        const val TAG = "LlamaCppRuntime"

        /** llama.cpp convention: -1 selects a random seed. */
        const val RANDOM_SEED = -1
    }
}
