package com.softwaremine.dps.domain.runtime

import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.model.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * An inference engine capable of running a GGUF model.
 *
 * ## Purpose — the primary seam of the whole architecture
 * This interface is the boundary that keeps DPS from being welded to any one
 * inference technology. Everything above it — the engine, the session manager,
 * the conversation pipeline, every ViewModel — is written against this contract
 * and has no knowledge of whether tokens arrive over JNI, over HTTP, or from
 * something that does not exist yet.
 *
 * DPS ships two implementations from day one (ADR-001):
 *
 * | Implementation | Used in | Mechanism |
 * |---|---|---|
 * | `OllamaRuntimeProvider` | development | HTTP to a developer-machine server |
 * | `LlamaCppRuntimeProvider` | production | JNI into a bundled native library |
 *
 * Having two implementations from the first commit is a deliberate structural
 * choice, not an accident of tooling. A codebase with a single implementation
 * behind an interface accretes assumptions about that implementation until the
 * abstraction is decorative. Two implementations, both continuously exercised,
 * keep the seam honest.
 *
 * ## Responsibilities
 * - Report whether it can operate at all in this environment ([status]).
 * - Load and unload a model deterministically.
 * - Produce tokens as a stream.
 * - Count tokens, so callers can manage the context window before overflowing it.
 *
 * ## Explicit non-responsibilities
 * A [RuntimeProvider] must **not**:
 * - apply a chat template — prompts arrive fully rendered (see [CompletionRequest]);
 * - know about conversations, roles, memory, or the secretary domain;
 * - decide *which* model to load — that is [com.softwaremine.dps.domain.model.ModelManager];
 * - retain state between calls beyond the loaded model itself.
 *
 * ## Threading contract
 * [load], [unload] and [generate] are called on
 * [com.softwaremine.dps.core.concurrency.DispatcherProvider.inference], which is
 * serialised to one operation. Implementations therefore need not be internally
 * concurrent, but **must** be cancellation-aware: a cancelled generation has to
 * stop native work promptly rather than run to completion in the background,
 * burning battery on tokens nobody will read.
 *
 * ## Future extensions
 * MLC LLM or MediaPipe would be added purely as new implementations of this
 * interface, with no changes above the seam.
 *
 * ## Dependencies
 * `domain` types and coroutines. No Android framework.
 */
interface RuntimeProvider {

    /** Stable identifier for logging and provider selection. */
    val id: RuntimeId

    /** What this runtime can do. Consulted before use, not assumed. */
    val capabilities: RuntimeCapabilities

    /** Observable lifecycle state. See [RuntimeStatus]. */
    val status: StateFlow<RuntimeStatus>

    /**
     * Checks whether this runtime can operate in the current environment.
     *
     * Called before selection. Implementations must be honest and cheap here:
     * llama.cpp probes for its native library, Ollama pings its server. A
     * runtime that reports availability and then fails to load is far worse
     * than one that declines up front, because the failure surfaces after the
     * user has already waited.
     *
     * @return `true` if [load] has a realistic chance of succeeding.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Loads a model into memory. Expect 5–8 seconds on target hardware.
     *
     * Per ADR-002 at most one model is resident at a time; loading while
     * another model is loaded must unload the previous one first.
     *
     * @param modelFile a verified GGUF file. The caller guarantees its checksum
     *   has already passed — loading an unverified file risks a native crash.
     * @param config sampling and resource parameters.
     */
    suspend fun load(modelFile: File, config: ModelConfig): DpsResult<Unit>

    /**
     * Releases the loaded model and its native memory.
     *
     * Must be idempotent — unloading when nothing is loaded succeeds silently.
     * Callers include memory-pressure handlers, where throwing would be
     * actively harmful.
     */
    suspend fun unload(): DpsResult<Unit>

    /**
     * Generates a completion as a stream.
     *
     * The returned [Flow] is cold: nothing happens until it is collected. It
     * emits zero or more [CompletionChunk.Token] values followed by exactly one
     * terminal [CompletionChunk.Completed] or [CompletionChunk.Failed].
     *
     * The flow does not throw for expected failures; it emits
     * [CompletionChunk.Failed] so that tokens already produced survive.
     */
    fun generate(request: CompletionRequest): Flow<CompletionChunk>

    /**
     * Counts tokens in [text] using the loaded model's tokenizer.
     *
     * Required for honest context management. Character-count heuristics are
     * not a substitute: tokenisation varies by several-fold across scripts, and
     * Roman-Urdu input — the primary interaction language in `user_journey.md` —
     * tokenises far less efficiently than English. A heuristic tuned on English
     * will silently overflow the context window on exactly the input this
     * product is built for.
     */
    suspend fun tokenCount(text: String): DpsResult<Int>
}

/** Stable identifier for a [RuntimeProvider] implementation. */
enum class RuntimeId(val label: String) {
    /** HTTP client for a developer-machine Ollama server. Development only. */
    OLLAMA("ollama"),

    /** In-process llama.cpp via JNI. The production runtime. */
    LLAMA_CPP("llama.cpp"),
}

/**
 * Declared capabilities of a runtime.
 *
 * Callers consult this rather than branching on [RuntimeId]. Type-switching on
 * a provider's concrete identity is precisely the coupling this whole seam
 * exists to prevent.
 */
data class RuntimeCapabilities(

    /** Emits tokens incrementally rather than only a final result. */
    val supportsStreaming: Boolean,

    /** Exposes the model's real tokenizer for [RuntimeProvider.tokenCount]. */
    val supportsTokenCount: Boolean,

    /** Can offload layers to GPU. Zero for the MVP (see [ModelConfig.gpuLayers]). */
    val supportsGpuOffload: Boolean,

    /**
     * Runs entirely on-device with no network.
     *
     * `false` for Ollama, which is why it is a development runtime only:
     * shipping it would breach the product's Offline First and Privacy First
     * guarantees. Release builds enforce this at the configuration level, but
     * this flag makes the property inspectable at runtime too.
     */
    val isFullyOffline: Boolean,

    /**
     * Can constrain sampling to a formal grammar (GBNF).
     *
     * Not used today. On Day 03 this becomes the difference between reliable
     * tool calling and hopeful JSON parsing — a 1.5B model asked politely for
     * valid JSON will not always comply, whereas one constrained at the sampler
     * cannot do otherwise.
     */
    val supportsGrammarConstraints: Boolean,
)
