package com.softwaremine.dps.domain.model

import com.softwaremine.dps.core.error.DpsError
import java.io.File

/**
 * Progress of acquiring and validating a model artifact.
 *
 * ## Purpose
 * Installing a model means downloading ~1.5 GB and then hashing it. On a mobile
 * connection the download is minutes; the SHA-256 pass over the result is tens
 * of seconds on the target CPU. Both are far too long to represent as a single
 * indeterminate spinner, and users abandon what they cannot see progressing.
 *
 * ## Why [Verifying] is its own state
 * It is tempting to fold verification into the tail of downloading. It is
 * modelled separately because it is where a *silent* failure becomes a *visible*
 * one — a corrupted GGUF would otherwise crash the native runtime later, at a
 * moment with no connection to the download that caused it. Making verification
 * a first-class, visible step is what turns an inexplicable crash into an
 * actionable message.
 *
 * ## Dependencies
 * [DpsError], [ModelDescriptor]. Pure Kotlin.
 */
sealed interface ModelInstallState {

    /** Not present on this device. */
    data object NotInstalled : ModelInstallState

    /**
     * Transfer in progress.
     *
     * Resumable via HTTP `Range`, so [bytesDownloaded] may start above zero when
     * an interrupted download continues (ADR-003).
     */
    data class Downloading(
        val descriptor: ModelDescriptor,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : ModelInstallState {

        /** Completion in `0.0..1.0`. */
        val progress: Float
            get() = if (totalBytes <= 0) 0f
            else (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)

        /** Estimated seconds remaining, or `null` when not yet meaningful. */
        val estimatedSecondsRemaining: Long?
            get() = if (bytesPerSecond <= 0) null
            else (totalBytes - bytesDownloaded) / bytesPerSecond
    }

    /** Computing SHA-256 over the downloaded file. Tens of seconds. */
    data class Verifying(
        val descriptor: ModelDescriptor,
        val bytesVerified: Long,
        val totalBytes: Long,
    ) : ModelInstallState {
        val progress: Float
            get() = if (totalBytes <= 0) 0f
            else (bytesVerified.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** Present on disk and integrity-verified. Ready to load. */
    data class Installed(val model: InstalledModel) : ModelInstallState

    /** Installation failed. See [DpsError.Model] for the specific cause. */
    data class Failed(
        val descriptor: ModelDescriptor,
        val error: DpsError,
    ) : ModelInstallState
}

/**
 * A model present on disk and verified.
 *
 * Producing one of these is an assertion that the file's SHA-256 matched
 * [ModelDescriptor.sha256] at [verifiedAtEpochMillis]. Only an
 * [InstalledModel] may be handed to
 * [com.softwaremine.dps.domain.runtime.RuntimeProvider.load] — the type is the
 * mechanism that makes "never load an unverified file" (ADR-003) structural
 * rather than a convention someone has to remember.
 */
data class InstalledModel(
    val descriptor: ModelDescriptor,
    val file: File,
    val sizeOnDiskBytes: Long,
    val installedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long,
)

/**
 * Storage occupied by model artifacts, and headroom remaining.
 *
 * Surfaced in settings so the user can see and reclaim what DPS is using —
 * transparency about on-device footprint is part of the privacy promise, not
 * merely a diagnostic.
 */
data class ModelStorageStats(
    val installedModelCount: Int,
    val totalModelBytes: Long,
    val availableBytes: Long,
    val partialDownloadBytes: Long,
)
