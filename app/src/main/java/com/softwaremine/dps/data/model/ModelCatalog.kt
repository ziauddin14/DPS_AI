package com.softwaremine.dps.data.model

import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelFamily
import com.softwaremine.dps.domain.model.Quantization
import com.softwaremine.dps.domain.prompt.PromptFormat

/**
 * The models DPS knows how to run.
 *
 * ## Purpose
 * The concrete expression of ADR-004: adding support for a new model is an edit
 * to this file plus, if its family is new, one [ChatTemplate]. No orchestration,
 * engine, session, or UI code changes.
 *
 * ## Why the catalog is bundled rather than fetched
 * A remote catalog would mean the app cannot determine what to install while
 * offline — unacceptable for an offline-first product — and would place a
 * network-controlled value into a security decision: whatever the server said
 * the hash was would become the hash we trust, defeating the point of having one.
 *
 * Model *artifacts* are downloaded. The description of what is legitimate ships
 * with the app and is signed by the APK signature.
 *
 * ## ⚠ Provisioning required before any model can be installed
 * [ModelDescriptor.sha256] below is empty, and installation is refused while it
 * stays that way ([ModelDescriptor.isVerifiable]).
 *
 * This is intentional. The authoritative hash of the artifact DPS will ship has
 * not yet been published by the Product Owner, and inventing a plausible-looking
 * one would be worse than leaving it unset: it would either fail with a
 * confusing mismatch or invite someone to "fix" the failure by weakening
 * verification.
 *
 * To provision, download the artifact once and record its real hash and size:
 *
 * ```bash
 * curl -L -o qwen2.5-1.5b-instruct-q4_k_m.gguf "<hosting-url>"
 * sha256sum qwen2.5-1.5b-instruct-q4_k_m.gguf   # -> sha256
 * stat -c %s qwen2.5-1.5b-instruct-q4_k_m.gguf  # -> sizeBytes
 * ```
 *
 * Then set both fields together. They describe one artifact and must never be
 * updated independently.
 *
 * ## Dependencies
 * `domain` model types only. Pure Kotlin.
 */
object ModelCatalog {

    /**
     * Qwen2.5-1.5B-Instruct, Q4_K_M — the locked MVP model.
     *
     * Chosen in `offliceLLM_guide.md` for the combination that matters on a
     * phone: strong instruction-following at ~2 GB resident, which is what a
     * secretary needs. Larger models reason better but do not fit the RAM
     * budget; smaller ones drop constraints from requests, which for this
     * product means losing "at 5 PM" from a reminder — a silent failure far
     * worse than an obviously wrong answer.
     */
    val QWEN_2_5_1_5B_INSTRUCT_Q4_K_M: ModelDescriptor = ModelDescriptor(
        id = "qwen2.5-1.5b-instruct-q4_k_m",
        displayName = "Qwen2.5 1.5B Instruct",
        version = "1.0.0",
        family = ModelFamily.QWEN,
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",

        // Hosting is an open decision (ADR open question 3). A controlled
        // mirror is the safer answer for a product that claims supply-chain
        // integrity: an upstream repository can retag or remove a file, and the
        // resulting checksum failure would strand every user at once.
        downloadUrl = "",

        // Approximate published size, inert until provisioned: canInstall
        // refuses while sha256 is empty, so this value is never acted upon.
        // Replace with the exact byte count alongside the real hash.
        sizeBytes = 1_117_320_704L,

        // NOT YET PROVISIONED — see the class documentation above.
        sha256 = "",

        quantization = Quantization.Q4_K_M,
        promptFormat = PromptFormat.QWEN_CHAT_ML,
        contextLength = 32_768,

        // 3 GB. Below this the model loads but the system reclaims it under any
        // other memory pressure, producing an assistant that dies mid-sentence.
        minDeviceRamBytes = 3L * 1024 * 1024 * 1024,

        stopSequences = listOf("<|im_end|>", "<|endoftext|>"),
    )

    /** Every known model, in preference order. */
    val ALL: List<ModelDescriptor> = listOf(
        QWEN_2_5_1_5B_INSTRUCT_Q4_K_M,
    )

    /** The model DPS uses unless told otherwise. */
    val DEFAULT: ModelDescriptor = QWEN_2_5_1_5B_INSTRUCT_Q4_K_M

    /** Looks up a descriptor by id, or `null`. */
    fun findById(modelId: String): ModelDescriptor? = ALL.firstOrNull { it.id == modelId }
}
