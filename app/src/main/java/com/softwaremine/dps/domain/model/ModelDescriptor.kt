package com.softwaremine.dps.domain.model

import com.softwaremine.dps.domain.prompt.PromptFormat

/**
 * The complete, self-contained identity of a model artifact.
 *
 * ## Purpose
 * This is the data structure that makes ADR-004's "swap a model by editing data,
 * not code" real. Everything the system needs in order to *acquire*, *verify*,
 * *load* and *correctly prompt* a model lives here. No other type may hold
 * model-specific knowledge, and no code path may branch on model identity.
 *
 * If a future change requires an `if (model.id == …)` anywhere in the app, that
 * is the signal that a property is missing from this class — add it here rather
 * than branching at the call site.
 *
 * ## Responsibilities
 * - Identify the artifact ([id], [version], [family]).
 * - Describe how to fetch and verify it ([downloadUrl], [sizeBytes], [sha256]).
 * - Describe how to run it ([contextLength], [quantization], [minDeviceRamBytes]).
 * - Describe how to *talk* to it ([promptFormat], [stopSequences]).
 *
 * ## Future extensions
 * A `capabilities` field once tool calling arrives on Day 03 — not every model
 * emits reliable structured output, and the secretary must degrade gracefully
 * on those that do not.
 *
 * ## Dependencies
 * [PromptFormat] only. Pure Kotlin.
 */
data class ModelDescriptor(

    /** Stable unique identifier, e.g. `qwen2.5-1.5b-instruct-q4_k_m`. */
    val id: String,

    /** Human-readable name. Safe to show in settings UI. */
    val displayName: String,

    /**
     * Artifact version, independent of app version.
     *
     * Model updates ship without an app update (ADR-003), so this is the field
     * that drives "a newer model is available".
     */
    val version: String,

    /** Model family. Informational; behaviour is driven by [promptFormat]. */
    val family: ModelFamily,

    /** Filename on disk, e.g. `qwen2.5-1.5b-instruct-q4_k_m.gguf`. */
    val fileName: String,

    /** HTTPS source. Must support HTTP `Range` requests so downloads resume. */
    val downloadUrl: String,

    /** Exact expected size in bytes. A cheap first-pass integrity signal. */
    val sizeBytes: Long,

    /**
     * Lowercase hex SHA-256 of the complete file, or empty when the value has
     * not yet been provisioned.
     *
     * The authoritative integrity gate. Verified after download *and* before
     * every load, because a corrupted GGUF crashes the native runtime in a way
     * no Kotlin `try/catch` can intercept. See ADR-003.
     *
     * ## Why empty is permitted, and what it means
     * Empty means "the publisher has not yet recorded this artifact's hash".
     * It does **not** mean "skip verification". [ModelManager.canInstall]
     * refuses to install a descriptor whose hash is unset, so an unprovisioned
     * model is unusable rather than unverified.
     *
     * This is deliberate. The alternative — a plausible-looking placeholder
     * hash — is strictly worse: it either fails verification with a confusing
     * mismatch, or tempts someone to "fix" the failure by weakening the check.
     * An empty value is unmistakably unset and cannot be misread as verified.
     *
     * See [isVerifiable].
     */
    val sha256: String,

    /** Quantisation scheme, which determines the memory/quality trade. */
    val quantization: Quantization,

    /** The chat template this model was tuned on. See [PromptFormat]. */
    val promptFormat: PromptFormat,

    /** Maximum context window in tokens that this artifact supports. */
    val contextLength: Int,

    /**
     * Minimum device RAM required to run this model without OOM.
     *
     * Checked before download, not before load: telling a user their phone
     * cannot run DPS *after* they have spent 1.5 GB of mobile data would be
     * indefensible.
     */
    val minDeviceRamBytes: Long,

    /**
     * Sequences that terminate generation.
     *
     * Model-specific, and required: without the right stop sequence the model
     * will happily continue past its turn and begin hallucinating the user's
     * next message.
     */
    val stopSequences: List<String>,
) {
    init {
        require(id.isNotBlank()) { "ModelDescriptor.id must not be blank" }
        require(sizeBytes > 0) { "ModelDescriptor.sizeBytes must be positive" }
        require(sha256.isEmpty() || sha256.length == SHA256_HEX_LENGTH) {
            "ModelDescriptor.sha256 must be empty or $SHA256_HEX_LENGTH hex characters, " +
                "was ${sha256.length}"
        }
        require(sha256.all { it.isDigit() || it in 'a'..'f' }) {
            "ModelDescriptor.sha256 must be lowercase hexadecimal"
        }
        require(contextLength > 0) { "ModelDescriptor.contextLength must be positive" }
    }

    /**
     * `true` when this artifact's integrity can be proven.
     *
     * Installation is refused when this is `false`. A model that cannot be
     * verified cannot be loaded, because loading a corrupted GGUF terminates
     * the process rather than raising a catchable error.
     */
    val isVerifiable: Boolean get() = sha256.length == SHA256_HEX_LENGTH

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * Model families DPS knows about.
 *
 * Informational only — never branch on this. Behaviour is driven by
 * [ModelDescriptor.promptFormat], which is what actually varies.
 */
enum class ModelFamily {
    QWEN,
    LLAMA,
    PHI,
    GEMMA,
    MISTRAL,
    OTHER,
}

/**
 * GGUF quantisation scheme.
 *
 * Q4_K_M is the locked MVP choice (`offliceLLM_guide.md`): roughly 2 GB
 * resident for a 1.5B model with very little quality loss. Lower quantisations
 * degrade instruction-following sharply, which for a secretary shows up as
 * dropped constraints ("tomorrow at 5" becoming "tomorrow") rather than as
 * obviously broken output — a failure mode worth avoiding.
 */
enum class Quantization(val label: String) {
    Q4_0("Q4_0"),
    Q4_K_M("Q4_K_M"),
    Q5_K_M("Q5_K_M"),
    Q6_K("Q6_K"),
    Q8_0("Q8_0"),
    F16("F16"),
}
