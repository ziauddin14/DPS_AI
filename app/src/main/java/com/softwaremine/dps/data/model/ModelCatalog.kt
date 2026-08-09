package com.softwaremine.dps.data.model

import com.softwaremine.dps.domain.model.ModelCatalogSource
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelFamily
import com.softwaremine.dps.domain.model.Quantization
import com.softwaremine.dps.domain.prompt.PromptFormat

/**
 * The models DPS knows how to run.
 *
 * ## Purpose
 * The concrete expression of ADR-004: adding a model is an edit to this file
 * plus, if its family is new, one [com.softwaremine.dps.ai.prompt.ChatTemplate].
 * No orchestration, engine, session, or UI code changes.
 *
 * ## Why the catalog is bundled rather than fetched
 * A remote catalog would mean the app cannot determine what to install while
 * offline — unacceptable for an offline-first product — and would place a
 * network-controlled value into a security decision: whatever the server said
 * the hash was would become the hash we trust, defeating the point of having
 * one.
 *
 * Model *artifacts* are downloaded. The description of what is legitimate ships
 * inside the APK and is covered by its signature.
 *
 * ## Provenance of the values below
 * Every checksum and size here was **retrieved from the publisher**, never
 * estimated. Each was cross-verified against two independent endpoints that
 * agreed exactly:
 *
 *  1. `GET /api/models/{repo}/tree/main` → `lfs.oid` (the SHA-256) and `lfs.size`
 *  2. `HEAD` on the resolve URL → `X-Linked-ETag` and `X-Linked-Size`
 *
 * Retrieved 2026-08-04. If a future entry cannot be sourced this way, it does
 * not belong in this file — a plausible-looking hash is worse than none,
 * because it either fails confusingly or tempts someone to weaken verification.
 *
 * ## Hosting
 * URLs are resolved through [ModelCatalogSource], not hardcoded. Migrating to a
 * controlled mirror (TD-001) changes that constant and nothing else; the
 * checksums stay identical and are what proves a mirror serves the same bytes.
 *
 * ## Dependencies
 * `domain` model types only. Pure Kotlin.
 */
object ModelCatalog {

    /**
     * Qwen2.5-1.5B-Instruct, Q4_K_M — the locked MVP model, provisioned.
     *
     * Chosen in `offliceLLM_guide.md` for the combination that matters on a
     * phone: strong instruction-following at ~1 GB on disk. Larger models
     * reason better but do not fit the RAM budget; smaller ones drop
     * constraints from requests, which for this product means losing "at 5 PM"
     * from a reminder — a silent failure worse than an obviously wrong answer.
     *
     * Licence: Apache-2.0. Repository is public and ungated.
     */
    val QWEN_2_5_1_5B_INSTRUCT_Q4_K_M: ModelDescriptor = ModelDescriptor(
        id = "qwen2.5-1.5b-instruct-q4_k_m",
        displayName = "Qwen2.5 1.5B Instruct",
        version = "1.0.0",
        family = ModelFamily.QWEN,
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = ModelCatalogSource.ACTIVE.resolve(
            "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        ),

        // Verified: lfs.size and Content-Length both report 1117320736.
        // Note this is 32 bytes larger than the figure carried as a placeholder
        // before provisioning — exactly the kind of near-miss that makes
        // size-only validation insufficient and the checksum mandatory.
        sizeBytes = 1_117_320_736L,

        // Verified: lfs.oid == X-Linked-ETag.
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",

        quantization = Quantization.Q4_K_M,
        promptFormat = PromptFormat.QWEN_CHAT_ML,

        // The artifact supports 32k, but DPS runs it at ModelConfig's 8k.
        // KV-cache memory scales with context, and 32k would exceed the device
        // budget on its own (ADR-002).
        contextLength = 32_768,

        // 3 GB. Below this the model loads but the system reclaims it under any
        // other memory pressure, producing an assistant that dies mid-sentence.
        minDeviceRamBytes = 3L * 1024 * 1024 * 1024,

        stopSequences = listOf("<|im_end|>", "<|endoftext|>"),
    )

    /**
     * Phi-3 Mini 4K Instruct, Q4 — provisioned, but **not runnable on typical
     * target hardware**.
     *
     * Included deliberately rather than omitted. It demonstrates that the
     * catalog is genuinely multi-family rather than shaped around a single
     * model, and its honest 6 GB [ModelDescriptor.minDeviceRamBytes] means
     * [com.softwaremine.dps.domain.model.ModelManager.canInstall] refuses it on
     * a 4 GB phone *before* the user spends 2.4 GB of data discovering that
     * themselves. The preflight guard existing is worth nothing until something
     * actually exercises it.
     *
     * Licence: MIT. Repository is public and ungated.
     */
    val PHI_3_MINI_4K_INSTRUCT_Q4: ModelDescriptor = ModelDescriptor(
        id = "phi-3-mini-4k-instruct-q4",
        displayName = "Phi-3 Mini 4K Instruct",
        version = "1.0.0",
        family = ModelFamily.PHI,
        fileName = "Phi-3-mini-4k-instruct-q4.gguf",
        downloadUrl = ModelCatalogSource.ACTIVE.resolve(
            "microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        ),
        sizeBytes = 2_393_231_072L,
        sha256 = "8a83c7fb9049a9b2e92266fa7ad04933bb53aa1e85136b7b30f1b8000ff2edef",
        quantization = Quantization.Q4_K_M,
        promptFormat = PromptFormat.PHI_3,
        contextLength = 4_096,
        minDeviceRamBytes = 6L * 1024 * 1024 * 1024,
        stopSequences = listOf("<|end|>", "<|endoftext|>"),
    )

    /**
     * Every provisioned model, in preference order.
     *
     * ## Families not present, and precisely why
     *
     * `ChatTemplate` implementations for Gemma, Llama 3 and Mistral shipped on
     * Day 02, so the *runtime* supports all five families named in the brief.
     * What is missing is provisioning data, and in two cases it cannot be
     * obtained without action from the Product Owner:
     *
     * | Family  | Repository                              | Status |
     * |---------|-----------------------------------------|--------|
     * | Gemma   | `google/gemma-2-2b-it-GGUF`             | **`gated=manual`** — requires a HuggingFace account and manual acceptance of the Gemma licence |
     * | Llama   | `meta-llama/Llama-3.2-1B-Instruct`      | **`gated=manual`** — requires acceptance of the Llama 3.2 Community Licence |
     * | Mistral | `mistralai/Mistral-7B-Instruct-v0.3`    | Ungated (Apache-2.0), but 7B exceeds the device budget by a wide margin; no official small GGUF exists |
     *
     * Adding a family is one entry here once its checksum can be retrieved from
     * the publisher. Entries are never added on estimated values.
     */
    val ALL: List<ModelDescriptor> = listOf(
        QWEN_2_5_1_5B_INSTRUCT_Q4_K_M,
        PHI_3_MINI_4K_INSTRUCT_Q4,
    )

    /** The model DPS uses unless told otherwise. */
    val DEFAULT: ModelDescriptor = QWEN_2_5_1_5B_INSTRUCT_Q4_K_M

    /** Looks up a descriptor by id, or `null`. */
    fun findById(modelId: String): ModelDescriptor? = ALL.firstOrNull { it.id == modelId }
}
