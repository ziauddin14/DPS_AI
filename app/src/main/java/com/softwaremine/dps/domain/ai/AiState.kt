package com.softwaremine.dps.domain.ai

import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelInstallState
import com.softwaremine.dps.domain.runtime.RuntimeId

/**
 * The state of the AI subsystem as a whole.
 *
 * ## Purpose
 * A single value the UI can render without knowing anything about runtimes,
 * model files, checksums, or JNI. It is the public face of the entire AI layer.
 *
 * ## Why this is separate from [com.softwaremine.dps.domain.runtime.RuntimeStatus]
 * `RuntimeStatus` is one engine's mechanical view: loaded or not. [AiState] spans
 * the whole journey the user actually experiences — *is a model even present on
 * this device?*, *is it downloading?*, *is it waking up?*, *can I talk now?*
 *
 * Several of these states exist before any runtime is involved at all. Merging
 * the two types would make model acquisition inexpressible and would leak
 * runtime selection into the UI.
 *
 * ## Design note on ordering
 * These states form the honest startup sequence:
 * `Idle → CheckingModel → (ModelRequired → PreparingModel) → LoadingModel → Ready`
 *
 * The parenthesised pair is skipped once the model is installed, which is the
 * normal case after first launch and the reason cold start can meet the < 7 s
 * KPI at all.
 *
 * ## What is deliberately not here
 * There is no `Generating` state. Generation is a property of a conversation,
 * not of the AI subsystem — the engine remains `Ready` throughout, and
 * [com.softwaremine.dps.domain.conversation.ConversationState.isGenerating]
 * carries that fact. Modelling it here would make the two sources of truth
 * capable of disagreeing.
 *
 * ## Dependencies
 * `domain` types only. Pure Kotlin.
 */
sealed interface AiState {

    /** Nothing has been initialised. The state at process start. */
    data object Idle : AiState

    /** Determining whether a usable model is installed. Fast, local. */
    data object CheckingModel : AiState

    /**
     * No usable model is installed; the user must consent to a download.
     *
     * Downloading ~1.5 GB is never begun automatically. It is the user's
     * bandwidth — very often metered — and Human Confirmation is a core
     * principle of this product, not a courtesy reserved for outgoing messages.
     */
    data class ModelRequired(val descriptor: ModelDescriptor) : AiState

    /** A model is being downloaded and verified. Carries granular progress. */
    data class PreparingModel(val installState: ModelInstallState) : AiState

    /** The model is being loaded into the runtime. Expect 5–8 seconds. */
    data class LoadingModel(val modelId: String) : AiState

    /** The AI is loaded and able to respond. */
    data class Ready(
        val modelId: String,
        val runtimeId: RuntimeId,
    ) : AiState

    /**
     * The AI cannot be used, with the reason attached.
     *
     * Reached when no runtime is available on this device, the model is
     * irrecoverably corrupt, or loading failed. Distinct from
     * [ModelRequired], which is a *recoverable* absence the user can resolve.
     */
    data class Unavailable(val error: DpsError) : AiState
}

/** `true` when the AI can accept a message right now. */
val AiState.isReady: Boolean get() = this is AiState.Ready

/** `true` when the AI is mid-transition and the UI should show progress. */
val AiState.isBusy: Boolean
    get() = this is AiState.CheckingModel ||
        this is AiState.PreparingModel ||
        this is AiState.LoadingModel

/** `true` when user action is required before the AI can be used. */
val AiState.needsUserAction: Boolean
    get() = this is AiState.ModelRequired || this is AiState.Unavailable
