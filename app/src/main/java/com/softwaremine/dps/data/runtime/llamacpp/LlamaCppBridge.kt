package com.softwaremine.dps.data.runtime.llamacpp

/**
 * The JNI boundary to llama.cpp.
 *
 * ## Purpose
 * The single point at which Kotlin calls native inference code. Every `external`
 * declaration here has a matching C++ symbol in the NDK build.
 *
 * ## Current state — read this before assuming something is missing
 * The native library **is not built yet**. The NDK and CMake are deliberately
 * not installed (Day 03 owns llama.cpp integration), so [isAvailable] evaluates
 * to `false` on this build and [LlamaCppRuntimeProvider] reports itself
 * unavailable.
 *
 * This is not a stub and not a placeholder. It is real, correct behaviour:
 * capability detection at runtime, followed by graceful degradation. The same
 * code path handles a genuine production case — a device whose ABI has no
 * bundled `.so` — and will continue to be exercised after Day 03 ships the
 * library. Nothing here is written to be deleted later.
 *
 * ## Why loading is guarded rather than allowed to throw
 * [System.loadLibrary] raises [UnsatisfiedLinkError], an `Error` rather than an
 * `Exception`. Left unguarded it terminates the process at class initialisation
 * — before any UI exists to explain what happened. Catching it converts an
 * unexplained crash on launch into a runtime that simply declines to be
 * selected, and lets the Ollama provider take over during development.
 *
 * ## Handle discipline
 * [loadModel] returns an opaque `Long` — a pointer to native state. Two rules,
 * both of which produce process death rather than exceptions when broken:
 *
 * 1. Every non-zero handle must eventually reach [freeModel]. Native allocations
 *    are invisible to the JVM garbage collector, so a leaked handle is 2 GB the
 *    system cannot reclaim.
 * 2. A handle must never be used after being freed. The wrapper enforces both
 *    by never exposing handles outside [LlamaCppRuntimeProvider].
 *
 * ## Threading
 * All calls arrive on
 * [com.softwaremine.dps.core.concurrency.DispatcherProvider.inference], which is
 * serialised to one operation. [cancel] is the sole exception — it is called
 * from another thread by design, so the native implementation must make its
 * cancellation flag atomic.
 *
 * ## Future extensions
 * Day 03 adds grammar-constrained sampling (GBNF) for reliable tool calls, and
 * KV-cache reuse across turns so multi-turn conversations do not re-process the
 * full prompt each time.
 *
 * ## Dependencies
 * The `dps_llama` shared library, built by the NDK from llama.cpp sources.
 */
internal object LlamaCppBridge {

    private const val LIBRARY_NAME = "dps_llama"

    /**
     * `true` when the native library loaded successfully for this device's ABI.
     *
     * Evaluated once, lazily. Calling any `external` member while this is
     * `false` throws [UnsatisfiedLinkError], so every call site must check first.
     */
    val isAvailable: Boolean by lazy { tryLoadLibrary() }

    /** The reason loading failed, for diagnostics. `null` when it succeeded. */
    @Volatile
    var unavailableReason: String? = null
        private set

    private fun tryLoadLibrary(): Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        unavailableReason = null
        true
    } catch (error: UnsatisfiedLinkError) {
        unavailableReason =
            "Native library '$LIBRARY_NAME' is not available for this ABI: ${error.message}"
        false
    } catch (error: SecurityException) {
        unavailableReason = "Loading '$LIBRARY_NAME' was denied: ${error.message}"
        false
    }

    /**
     * Loads a GGUF model and returns an opaque handle, or `0` on failure.
     *
     * @param modelPath absolute path to a **checksum-verified** GGUF file.
     *   Passing an unverified file risks terminating the process — the native
     *   loader memory-maps and dereferences the file directly.
     */
    external fun loadModel(
        modelPath: String,
        contextLength: Int,
        threadCount: Int,
        gpuLayers: Int,
    ): Long

    /** Frees native state. Safe to call with `0`. Idempotent per handle. */
    external fun freeModel(handle: Long)

    /**
     * Generates tokens, invoking [callback] for each.
     *
     * Blocks until generation ends. Runs on the inference dispatcher, so
     * blocking is expected rather than a defect.
     *
     * @return a [FinishCode].
     */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
        stopSequences: Array<String>,
        callback: TokenCallback,
    ): Int

    /** Counts tokens using the loaded model's tokenizer. Negative on error. */
    external fun tokenCount(handle: Long, text: String): Int

    /**
     * Requests cancellation of an in-flight [generate].
     *
     * Called from a **different thread** than the one blocked in [generate].
     * The native flag must therefore be atomic, and the generation loop must
     * check it between tokens.
     */
    external fun cancel(handle: Long)

    /**
     * Receives tokens from native code.
     *
     * A `fun interface` so the C++ side can invoke a single well-known method
     * signature. Implementations must be fast and must not block: this is
     * called from inside the native generation loop, and time spent here is
     * time not spent generating.
     */
    fun interface TokenCallback {
        /**
         * Called once per token.
         *
         * @return `false` to request that generation stop.
         */
        fun onToken(text: String): Boolean
    }

    /** Return codes from [generate]. Mirrors the C++ enum exactly. */
    object FinishCode {
        const val END_OF_TURN = 0
        const val STOP_SEQUENCE = 1
        const val MAX_TOKENS = 2
        const val CANCELLED = 3
        const val ERROR = -1
    }
}
