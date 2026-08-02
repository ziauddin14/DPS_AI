package com.softwaremine.dps.domain.model

import com.softwaremine.dps.core.result.DpsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the lifecycle of model artifacts on this device.
 *
 * ## Purpose
 * Everything to do with *possessing* a model: discovering what is available,
 * checking whether the device can accommodate it, downloading it, proving it is
 * intact, reporting what it costs in storage, and removing it.
 *
 * ## Boundary with [com.softwaremine.dps.domain.runtime.RuntimeProvider]
 * Worth stating precisely, because conflating the two is the most common way
 * this kind of layer decays:
 *
 * - `ModelManager` concerns **files**. It never loads anything into memory and
 *   holds no reference to a runtime.
 * - `RuntimeProvider` concerns **memory**. It never downloads, verifies, or
 *   deletes anything.
 *
 * The two meet only at [InstalledModel], which is a verified file handle.
 *
 * ## The integrity contract
 * This interface's single most important guarantee: **it never reports a model
 * as installed unless its SHA-256 has been verified.** Loading a corrupted GGUF
 * crashes the native runtime — a process death that no Kotlin `try/catch` can
 * intercept and that surfaces with no obvious link to the download that caused
 * it. Every path that produces an [InstalledModel] passes through verification.
 *
 * ## Responsibilities
 * - Catalog: what models does DPS know about?
 * - Preflight: can this device actually take this model?
 * - Acquire: download, resumably.
 * - Verify: SHA-256, after download and before every load.
 * - Report: storage statistics.
 * - Remove: delete artifacts and reclaim space.
 *
 * ## Future extensions
 * Model updates: comparing catalog [ModelDescriptor.version] against installed
 * versions and offering an upgrade. The version field exists for this; the
 * comparison logic arrives when there is a second version to compare against.
 *
 * ## Dependencies
 * `domain` + `core` types and coroutines. No Android framework in the contract.
 */
interface ModelManager {

    /** Observable state of the model currently being installed or in use. */
    val installState: StateFlow<ModelInstallState>

    /**
     * Every model DPS knows how to run.
     *
     * Bundled with the app rather than fetched: a remote catalog would mean the
     * app cannot determine what to install while offline, and would add a
     * network-controlled input to a security-sensitive decision.
     */
    fun catalog(): List<ModelDescriptor>

    /** The model DPS should use by default — Qwen2.5-1.5B-Instruct Q4_K_M. */
    fun defaultModel(): ModelDescriptor

    /** Looks up a descriptor by [ModelDescriptor.id]. */
    fun findDescriptor(modelId: String): DpsResult<ModelDescriptor>

    /**
     * Every verified model present on this device.
     *
     * Re-verifies checksums, so this is not free. Call it on a background
     * dispatcher and not on a hot path.
     */
    suspend fun installedModels(): DpsResult<List<InstalledModel>>

    /**
     * Returns [descriptor] as an [InstalledModel] if it is present and intact.
     *
     * Failures are informative rather than boolean:
     * [com.softwaremine.dps.core.error.DpsError.Model.NotInstalled] versus
     * [com.softwaremine.dps.core.error.DpsError.Model.ChecksumMismatch] lead to
     * completely different recoveries — offer a download, or delete and
     * re-acquire.
     */
    suspend fun resolveInstalled(descriptor: ModelDescriptor): DpsResult<InstalledModel>

    /**
     * Checks whether this device can accommodate [descriptor].
     *
     * Deliberately called **before** downloading rather than before loading.
     * Discovering that a phone cannot run DPS only after the user has spent
     * 1.5 GB of metered data would be indefensible.
     *
     * @return success if storage and RAM suffice, otherwise
     *   [com.softwaremine.dps.core.error.DpsError.Model.InsufficientStorage] or
     *   [com.softwaremine.dps.core.error.DpsError.Model.InsufficientMemory].
     */
    suspend fun canInstall(descriptor: ModelDescriptor): DpsResult<Unit>

    /**
     * Downloads and verifies [descriptor] if it is not already installed.
     *
     * Returns a cold [Flow] emitting [ModelInstallState.Downloading], then
     * [ModelInstallState.Verifying], then exactly one terminal
     * [ModelInstallState.Installed] or [ModelInstallState.Failed]. If the model
     * is already installed and intact, emits only [ModelInstallState.Installed].
     *
     * Downloads resume from a partial file via HTTP `Range` — mandatory, since
     * a 1.5 GB transfer over a mobile connection will be interrupted.
     *
     * **Must be started only after explicit user consent.** Never called
     * automatically; see [AiState.ModelRequired].
     */
    fun install(descriptor: ModelDescriptor): Flow<ModelInstallState>

    /**
     * Re-verifies an installed model's SHA-256 against its descriptor.
     *
     * Runs before every load, not only after download. Files rot: storage
     * corrupts, an interrupted write leaves a truncated file, and the cost of a
     * missed corruption is a native crash rather than an exception.
     */
    suspend fun verify(descriptor: ModelDescriptor): DpsResult<Boolean>

    /** Deletes an installed model and any partial download for it. */
    suspend fun delete(descriptor: ModelDescriptor): DpsResult<Unit>

    /** Storage consumed by models, and space remaining. */
    suspend fun storageStats(): DpsResult<ModelStorageStats>

    /** Removes orphaned `.part` files left by abandoned downloads. */
    suspend fun clearPartialDownloads(): DpsResult<Long>
}
