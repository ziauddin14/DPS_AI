package com.softwaremine.dps.data.model

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.model.InstalledModel
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.model.ModelInstallState
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.model.ModelStorageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The production [ModelManager].
 *
 * ## Purpose
 * Composes [ModelStorage], [ModelDownloader] and [ChecksumVerifier] into the
 * install/verify/report lifecycle the rest of the app depends on.
 *
 * ## The invariant this class exists to hold
 * **An [InstalledModel] is only ever produced after a successful SHA-256 check.**
 *
 * Every path that constructs one — install, resolve, enumerate — runs
 * verification first. Because [com.softwaremine.dps.domain.runtime.RuntimeProvider.load]
 * accepts a file that only this class can vouch for, "never load an unverified
 * model" is enforced by the flow of types rather than by anyone remembering to
 * check.
 *
 * ## Cost of that invariant
 * Verification hashes the whole file, so [resolveInstalled] is not cheap — tens
 * of seconds for 1.5 GB. It is called at session start, not per message, which
 * makes it a once-per-session cost that buys immunity to an uncatchable class of
 * native crash. That is a trade worth making.
 *
 * A future optimisation is to cache "verified at mtime+size" and re-verify only
 * when either changes. Deliberately not done today: correctness first, and the
 * cache invalidation is exactly the kind of subtlety that quietly reintroduces
 * the bug this class prevents.
 *
 * ## Dependencies
 * [ModelStorage], [ModelDownloader], [ChecksumVerifier], [DispatcherProvider],
 * [DpsLogger].
 */
class DefaultModelManager(
    private val storage: ModelStorage,
    private val downloader: ModelDownloader,
    private val verifier: ChecksumVerifier,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    private val deviceTotalRamBytes: () -> Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ModelManager {

    private val _installState = MutableStateFlow<ModelInstallState>(ModelInstallState.NotInstalled)
    override val installState: StateFlow<ModelInstallState> = _installState.asStateFlow()

    override fun catalog(): List<ModelDescriptor> = ModelCatalog.ALL

    override fun defaultModel(): ModelDescriptor = ModelCatalog.DEFAULT

    override fun findDescriptor(modelId: String): DpsResult<ModelDescriptor> =
        ModelCatalog.findById(modelId)
            ?.let { DpsResult.Success(it) }
            ?: DpsResult.Failure(DpsError.Model.Unknown(modelId))

    override suspend fun installedModels(): DpsResult<List<InstalledModel>> =
        withContext(dispatchers.io) {
            val installed = catalog().mapNotNull { descriptor ->
                when (val resolved = resolveInstalled(descriptor)) {
                    is DpsResult.Success -> resolved.value
                    is DpsResult.Failure -> null
                }
            }
            DpsResult.Success(installed)
        }

    override suspend fun resolveInstalled(
        descriptor: ModelDescriptor,
    ): DpsResult<InstalledModel> = withContext(dispatchers.io) {
        if (!storage.hasModelFile(descriptor)) {
            return@withContext DpsResult.Failure(DpsError.Model.NotInstalled(descriptor.id))
        }

        val file = storage.modelFile(descriptor)

        when (val verified = verifier.verify(file, descriptor.sha256, descriptor.id)) {
            is DpsResult.Failure -> {
                logger.w(TAG, "Verification failed for '${descriptor.id}': ${verified.error.message}")
                verified
            }

            is DpsResult.Success -> DpsResult.Success(
                InstalledModel(
                    descriptor = descriptor,
                    file = file,
                    sizeOnDiskBytes = file.length(),
                    installedAtEpochMillis = file.lastModified(),
                    verifiedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    /**
     * Preflight: can this device take this model?
     *
     * Checks integrity metadata first, then RAM, then storage — cheapest and
     * most fundamental first, so a misconfigured catalog fails instantly rather
     * than after two filesystem round-trips.
     */
    override suspend fun canInstall(descriptor: ModelDescriptor): DpsResult<Unit> =
        withContext(dispatchers.io) {
            if (!descriptor.isVerifiable) {
                // Refusing here is the whole point of ADR-003. A model whose
                // integrity cannot be proven must never reach native memory.
                return@withContext DpsResult.Failure(
                    DpsError.Model.Corrupted(
                        modelId = descriptor.id,
                        reason = "No published SHA-256; this artifact has not been provisioned.",
                    ),
                )
            }
            if (descriptor.downloadUrl.isBlank()) {
                return@withContext DpsResult.Failure(
                    DpsError.Model.DownloadFailed(
                        modelId = descriptor.id,
                        reason = "No download URL configured for this artifact.",
                    ),
                )
            }

            val ram = deviceTotalRamBytes()
            if (ram < descriptor.minDeviceRamBytes) {
                return@withContext DpsResult.Failure(
                    DpsError.Model.InsufficientMemory(
                        requiredBytes = descriptor.minDeviceRamBytes,
                        availableBytes = ram,
                    ),
                )
            }

            // Headroom beyond the artifact size: the filesystem needs slack,
            // and a device driven to zero free bytes misbehaves in ways far
            // beyond this app.
            val alreadyFetched = storage.partialBytes(descriptor)
            val stillNeeded = descriptor.sizeBytes - alreadyFetched + STORAGE_HEADROOM_BYTES
            val available = storage.availableBytes()
            if (available < stillNeeded) {
                return@withContext DpsResult.Failure(
                    DpsError.Model.InsufficientStorage(
                        requiredBytes = stillNeeded,
                        availableBytes = available,
                    ),
                )
            }

            DpsResult.Ok
        }

    /**
     * Downloads and verifies [descriptor].
     *
     * Emits `Downloading*` → `Verifying*` → terminal `Installed` or `Failed`.
     * Already-installed models short-circuit to `Installed`.
     *
     * Started only after explicit user consent — never automatically.
     */
    override fun install(descriptor: ModelDescriptor): Flow<ModelInstallState> = flow {
        // Already present and intact: nothing to do.
        when (val existing = resolveInstalled(descriptor)) {
            is DpsResult.Success -> {
                emitState(ModelInstallState.Installed(existing.value))
                return@flow
            }

            is DpsResult.Failure -> {
                if (existing.error is DpsError.Model.ChecksumMismatch ||
                    existing.error is DpsError.Model.Corrupted
                ) {
                    // A corrupt completed file cannot be resumed from — its
                    // contents are wrong, not merely incomplete. Remove it so
                    // the download starts clean.
                    logger.w(TAG, "Discarding corrupt '${descriptor.id}' before reinstall.")
                    storage.delete(descriptor)
                }
            }
        }

        when (val preflight = canInstall(descriptor)) {
            is DpsResult.Failure -> {
                emitState(ModelInstallState.Failed(descriptor, preflight.error))
                return@flow
            }

            is DpsResult.Success -> Unit
        }

        storage.ensureRootExists()
        val partial = storage.partialFile(descriptor)

        emitState(
            ModelInstallState.Downloading(
                descriptor = descriptor,
                bytesDownloaded = storage.partialBytes(descriptor),
                totalBytes = descriptor.sizeBytes,
                bytesPerSecond = 0L,
            ),
        )

        // Progress is published to the StateFlow from the downloader callback
        // rather than emitted into this flow: the callback is invoked from the
        // IO thread inside a blocking read loop, which is not a valid place to
        // suspend. The StateFlow is the observable channel; this flow carries
        // only phase transitions.
        val downloaded = downloader.download(descriptor, partial) { bytes, total, rate ->
            _installState.value = ModelInstallState.Downloading(
                descriptor = descriptor,
                bytesDownloaded = bytes,
                totalBytes = total,
                bytesPerSecond = rate,
            )
        }

        if (downloaded is DpsResult.Failure) {
            emitState(ModelInstallState.Failed(descriptor, downloaded.error))
            return@flow
        }

        emitState(
            ModelInstallState.Verifying(
                descriptor = descriptor,
                bytesVerified = 0L,
                totalBytes = descriptor.sizeBytes,
            ),
        )

        val verified = verifier.verify(partial, descriptor.sha256, descriptor.id) { hashed ->
            _installState.value = ModelInstallState.Verifying(
                descriptor = descriptor,
                bytesVerified = hashed,
                totalBytes = descriptor.sizeBytes,
            )
        }

        if (verified is DpsResult.Failure) {
            // A file that failed verification is not merely unusable, it is
            // dangerous: leaving it on disk risks a later code path loading it.
            logger.e(TAG, "Verification failed; deleting artifact.")
            storage.delete(descriptor)
            emitState(ModelInstallState.Failed(descriptor, verified.error))
            return@flow
        }

        // Promotion happens only here — after verification. This is what makes
        // the final filename mean "verified".
        when (val promoted = storage.promotePartial(descriptor)) {
            is DpsResult.Failure -> emitState(ModelInstallState.Failed(descriptor, promoted.error))
            is DpsResult.Success -> {
                val installed = InstalledModel(
                    descriptor = descriptor,
                    file = promoted.value,
                    sizeOnDiskBytes = promoted.value.length(),
                    installedAtEpochMillis = nowMillis(),
                    verifiedAtEpochMillis = nowMillis(),
                )
                logger.i(TAG, "Installed and verified '${descriptor.id}'.")
                emitState(ModelInstallState.Installed(installed))
            }
        }
    }.flowOn(dispatchers.io)

    override suspend fun verify(descriptor: ModelDescriptor): DpsResult<Boolean> =
        withContext(dispatchers.io) {
            if (!storage.hasModelFile(descriptor)) {
                return@withContext DpsResult.Failure(DpsError.Model.NotInstalled(descriptor.id))
            }
            verifier.verify(storage.modelFile(descriptor), descriptor.sha256, descriptor.id)
        }

    override suspend fun delete(descriptor: ModelDescriptor): DpsResult<Unit> =
        withContext(dispatchers.io) {
            val result = storage.delete(descriptor)
            if (result is DpsResult.Success) {
                _installState.value = ModelInstallState.NotInstalled
                logger.i(TAG, "Deleted '${descriptor.id}'.")
            }
            result
        }

    override suspend fun storageStats(): DpsResult<ModelStorageStats> =
        withContext(dispatchers.io) {
            DpsResult.Success(
                ModelStorageStats(
                    installedModelCount = storage.listModelFiles().size,
                    totalModelBytes = storage.totalModelBytes(),
                    availableBytes = storage.availableBytes(),
                    partialDownloadBytes = storage.totalPartialBytes(),
                ),
            )
        }

    override suspend fun clearPartialDownloads(): DpsResult<Long> =
        withContext(dispatchers.io) { storage.deletePartials() }

    /** Publishes a phase transition to both the flow and the StateFlow. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelInstallState>.emitState(
        state: ModelInstallState,
    ) {
        _installState.value = state
        emit(state)
    }

    private companion object {
        const val TAG = "ModelManager"

        /** Filesystem slack kept free beyond the artifact itself. */
        const val STORAGE_HEADROOM_BYTES = 256L * 1024 * 1024
    }
}
