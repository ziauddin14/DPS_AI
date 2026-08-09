package com.softwaremine.dps.core.error

/**
 * Every way the DPS AI foundation can fail, as a closed hierarchy.
 *
 * ## Purpose
 * Running an LLM on a phone introduces failure modes that simply do not exist
 * when calling a cloud API: the weights may not be downloaded, the download may
 * have been corrupted, the device may lack storage or RAM, the native runtime
 * may be missing for this ABI, the conversation may outgrow the context window.
 *
 * These are *expected states of a healthy app*, not bugs. Enumerating them in a
 * sealed hierarchy lets `when` be exhaustive, so adding a failure mode later
 * produces compile errors at every site that must now handle it. That is the
 * property worth having: the compiler tells us what we forgot.
 *
 * ## Responsibilities
 * - Describe *what* went wrong precisely enough for the UI to choose a recovery.
 * - Carry the originating [cause] for diagnostics without leaking it to the UI.
 *
 * ## Note on user-facing text
 * [message] is a *developer-facing* diagnostic. It must never be shown verbatim
 * to the user — AI Rule 1 and 2 (`ai_architecture.md`) forbid exposing internal
 * architecture. The UI layer maps error types to human wording.
 *
 * ## Future extensions
 * Day 03 adds `Tool` (execution failed, unknown tool, invalid arguments).
 * Day 04+ adds `Permission` (denied, permanently denied).
 *
 * ## Dependencies
 * None.
 */
sealed class DpsError {

    /** Developer-facing diagnostic. Never rendered directly to the user. */
    abstract val message: String

    /** The originating throwable, when this error wraps one. */
    open val cause: Throwable? = null

    // ---------------------------------------------------------------------
    // Model lifecycle — acquisition, integrity and storage of GGUF weights
    // ---------------------------------------------------------------------

    /** Failures relating to the model artifact on disk. See ADR-003. */
    sealed class Model : DpsError() {

        /** The requested model has not been downloaded to this device yet. */
        data class NotInstalled(val modelId: String) : Model() {
            override val message: String = "Model '$modelId' is not installed."
        }

        /** The model id is not present in the catalog. Usually a coding error. */
        data class Unknown(val modelId: String) : Model() {
            override val message: String = "Model '$modelId' is not in the catalog."
        }

        /**
         * The file exists but failed integrity verification.
         *
         * This is the single most important error in the system: loading a
         * corrupted GGUF crashes the *native* runtime, which is not catchable
         * as a Kotlin exception. Verification exists so this is reported here
         * instead of terminating the process. See ADR-003.
         */
        data class ChecksumMismatch(
            val modelId: String,
            val expectedSha256: String,
            val actualSha256: String,
        ) : Model() {
            override val message: String =
                "Checksum mismatch for '$modelId': expected $expectedSha256, got $actualSha256."
        }

        /** The file is structurally invalid — truncated, wrong magic bytes, unreadable. */
        data class Corrupted(val modelId: String, val reason: String) : Model() {
            override val message: String = "Model '$modelId' is corrupted: $reason."
        }

        /** Not enough free space to install this model. */
        data class InsufficientStorage(
            val requiredBytes: Long,
            val availableBytes: Long,
        ) : Model() {
            override val message: String =
                "Insufficient storage: need $requiredBytes bytes, have $availableBytes."
        }

        /** The device does not have enough RAM to run this model. */
        data class InsufficientMemory(
            val requiredBytes: Long,
            val availableBytes: Long,
        ) : Model() {
            override val message: String =
                "Insufficient memory: need $requiredBytes bytes, have $availableBytes."
        }

        /** The download did not complete. Resumable — see ADR-003. */
        data class DownloadFailed(
            val modelId: String,
            val reason: String,
            override val cause: Throwable? = null,
        ) : Model() {
            override val message: String = "Download of '$modelId' failed: $reason."
        }

        /** A filesystem operation against the model store failed. */
        data class StorageFailure(
            val reason: String,
            override val cause: Throwable? = null,
        ) : Model() {
            override val message: String = "Model storage failure: $reason."
        }
    }

    // ---------------------------------------------------------------------
    // Runtime — the inference engine behind RuntimeProvider
    // ---------------------------------------------------------------------

    /** Failures relating to the inference runtime. See ADR-001. */
    sealed class Runtime : DpsError() {

        /**
         * This runtime cannot operate in the current environment.
         *
         * Expected and non-exceptional: the llama.cpp provider reports this
         * when its native library is absent for the current ABI, and the Ollama
         * provider reports it in release builds where no base URL is compiled in.
         */
        data class Unavailable(
            val runtimeId: String,
            val reason: String,
        ) : Runtime() {
            override val message: String = "Runtime '$runtimeId' unavailable: $reason."
        }

        /** Generation was requested before a model was loaded. */
        data object NotLoaded : Runtime() {
            override val message: String = "No model is currently loaded."
        }

        /** The model failed to load into the runtime. */
        data class LoadFailed(
            val modelId: String,
            val reason: String,
            override val cause: Throwable? = null,
        ) : Runtime() {
            override val message: String = "Failed to load '$modelId': $reason."
        }

        /** Token generation failed partway through. */
        data class GenerationFailed(
            val reason: String,
            override val cause: Throwable? = null,
        ) : Runtime() {
            override val message: String = "Generation failed: $reason."
        }

        /**
         * The prompt does not fit the context window even after trimming.
         *
         * Reported rather than silently truncated: silent truncation drops the
         * system prompt or the user's actual question, producing confidently
         * wrong answers — the worst possible failure for a secretary.
         */
        data class ContextOverflow(
            val requiredTokens: Int,
            val maxTokens: Int,
        ) : Runtime() {
            override val message: String =
                "Context overflow: prompt needs $requiredTokens tokens, limit is $maxTokens."
        }

        /**
         * Generation stalled — no token arrived within the allowed interval.
         *
         * Distinct from a cancellation and from a general failure because the
         * recovery differs: a stall means the runtime is wedged, so the model
         * should be reloaded rather than the request simply retried.
         *
         * Note this measures *inactivity between tokens*, not total duration. A
         * cap on total time would abort long-but-healthy generations, which on
         * a phone are entirely normal; silence is the real symptom of a wedged
         * runtime.
         */
        data class Timeout(
            val idleMillis: Long,
            val limitMillis: Long,
        ) : Runtime() {
            override val message: String =
                "Generation stalled: no token for ${idleMillis}ms (limit ${limitMillis}ms)."
        }
    }

    // ---------------------------------------------------------------------
    // Session — AiSessionManager lifecycle
    // ---------------------------------------------------------------------

    /** Failures relating to AI session lifecycle. */
    sealed class Session : DpsError() {

        /** An operation requiring an active session was attempted without one. */
        data object NotStarted : Session() {
            override val message: String = "No active AI session."
        }

        /** A session was started while another was already running. */
        data object AlreadyActive : Session() {
            override val message: String = "An AI session is already active."
        }

        /** A generation was requested while one was already in flight. */
        data object GenerationInProgress : Session() {
            override val message: String = "A response is already being generated."
        }

        /**
         * A turn was cut off by [com.softwaremine.dps.ai.session.AiSessionManager.releaseMemory]
         * — Android reclaiming memory mid-turn (Day 07). Distinct from
         * [com.softwaremine.dps.core.error.DpsError.Runtime.Timeout]: nothing
         * was wedged, the system asked for memory back and DPS gave it back,
         * exactly as designed. Named so the user is told the truth rather
         * than being shown a generic stall message for a deliberate,
         * recoverable pause.
         */
        data object Interrupted : Session() {
            override val message: String = "Turn interrupted: memory was reclaimed by the system."
        }
    }

    // ---------------------------------------------------------------------
    // Catch-all
    // ---------------------------------------------------------------------

    /**
     * An unanticipated failure.
     *
     * A rising count of these in production is a signal that the taxonomy above
     * is missing a case, not that the catch-all is working well.
     */
    data class Unexpected(
        override val message: String,
        override val cause: Throwable? = null,
    ) : DpsError()
}
