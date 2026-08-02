package com.softwaremine.dps.domain.runtime

import com.softwaremine.dps.core.error.DpsError

/**
 * Lifecycle state of a single [RuntimeProvider].
 *
 * ## Purpose
 * Model loading takes 5–8 seconds on target hardware (`offliceLLM_guide.md`).
 * That is far too long to hide behind a spinner with no explanation, so the
 * state is modelled explicitly and surfaced to the UI.
 *
 * ## Distinction from [com.softwaremine.dps.domain.ai.AiState]
 * These look similar and are not the same thing, so the split is worth stating:
 *
 * - [RuntimeStatus] describes **one runtime's** view of the world. It is a
 *   low-level, mechanical fact: is this specific engine loaded?
 * - `AiState` describes **the whole AI subsystem** as the UI understands it,
 *   including model acquisition, which happens before any runtime is involved.
 *
 * Collapsing them would leak runtime-selection details into the UI and make it
 * impossible to represent "downloading the model" — a state no runtime has an
 * opinion about.
 *
 * ## Dependencies
 * [DpsError] only.
 */
sealed interface RuntimeStatus {

    /**
     * This runtime cannot be used in the current environment.
     *
     * A normal, expected state — not a failure. The llama.cpp provider reports
     * it when its native library is absent for the current ABI; the Ollama
     * provider reports it in release builds. [RuntimeProviderFactory] uses this
     * to select an alternative.
     */
    data class Unavailable(val reason: String) : RuntimeStatus

    /** Usable, but no model is currently loaded. */
    data object Available : RuntimeStatus

    /** A model is being loaded. Expect 5–8 seconds. */
    data class Loading(val modelId: String) : RuntimeStatus

    /** A model is resident and ready to generate. */
    data class Loaded(
        val modelId: String,
        val loadedAtEpochMillis: Long,
    ) : RuntimeStatus

    /** A model is being released. Native memory is being reclaimed. */
    data object Unloading : RuntimeStatus

    /** The runtime entered an error state and needs a reload to recover. */
    data class Failed(val error: DpsError) : RuntimeStatus
}

/** `true` when the runtime can accept a generation request right now. */
val RuntimeStatus.canGenerate: Boolean
    get() = this is RuntimeStatus.Loaded

/** `true` when the runtime is usable in this environment at all. */
val RuntimeStatus.isUsable: Boolean
    get() = this !is RuntimeStatus.Unavailable && this !is RuntimeStatus.Failed
