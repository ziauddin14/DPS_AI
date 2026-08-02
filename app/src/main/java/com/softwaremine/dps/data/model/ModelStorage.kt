package com.softwaremine.dps.data.model

import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.core.result.dpsCatching
import com.softwaremine.dps.domain.model.ModelDescriptor
import java.io.File

/**
 * Filesystem layout for model artifacts.
 *
 * ## Purpose
 * The single component that knows where model files live and what they are
 * called. Isolating this means the storage location can change — to scoped
 * external storage, or to a user-selected directory — without touching download,
 * verification, or loading code.
 *
 * ## Location: app-private internal storage
 * The root is a subdirectory of `Context.filesDir`, chosen deliberately:
 *
 * - **No permission required.** Requesting storage permission to hold a file
 *   only this app uses would be an unjustifiable ask, and every unnecessary
 *   permission erodes the credibility of the ones that matter.
 * - **Not world-readable.** External storage is; a 1.5 GB artifact the user
 *   believes is private must not sit where any app can read it.
 * - **Encrypted at rest** under file-based encryption on modern Android.
 * - **Removed on uninstall.** Deleting DPS reclaims the full 1.5 GB, with no
 *   orphaned data left behind. That is the behaviour a privacy-first product
 *   owes its users.
 *
 * ## The `.part` convention
 * In-progress downloads are written to `<name>.gguf.part` and renamed to
 * `<name>.gguf` only after SHA-256 verification passes. The final name
 * therefore *only ever* exists for a verified file.
 *
 * This makes a half-downloaded model structurally impossible to mistake for a
 * complete one — a property worth having, because the consequence of loading a
 * truncated GGUF is a native crash rather than an exception.
 *
 * ## Dependencies
 * `java.io` only. Takes its root directory by injection, so it is testable
 * against a temporary folder with no Android framework present.
 */
class ModelStorage(private val rootDir: File) {

    /** Creates the model directory if absent. Safe to call repeatedly. */
    fun ensureRootExists(): DpsResult<Unit> = dpsCatching(
        onError = { DpsError.Model.StorageFailure("Cannot create model directory.", it) },
    ) {
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw java.io.IOException("mkdirs failed for ${rootDir.absolutePath}")
        }
    }

    /** The final path for a verified model. Existence implies verified. */
    fun modelFile(descriptor: ModelDescriptor): File = File(rootDir, descriptor.fileName)

    /** The in-progress download path for [descriptor]. */
    fun partialFile(descriptor: ModelDescriptor): File =
        File(rootDir, "${descriptor.fileName}$PARTIAL_SUFFIX")

    /** `true` when a completed (therefore verified) file exists. */
    fun hasModelFile(descriptor: ModelDescriptor): Boolean =
        modelFile(descriptor).let { it.exists() && it.length() > 0 }

    /** Bytes already fetched for a resumable download; zero when none. */
    fun partialBytes(descriptor: ModelDescriptor): Long =
        partialFile(descriptor).let { if (it.exists()) it.length() else 0L }

    /**
     * Atomically promotes a verified partial file to its final name.
     *
     * The caller must verify the checksum first. This is the step that makes
     * the final filename a guarantee rather than a hope.
     */
    fun promotePartial(descriptor: ModelDescriptor): DpsResult<File> = dpsCatching(
        onError = { DpsError.Model.StorageFailure("Cannot finalise model file.", it) },
    ) {
        val partial = partialFile(descriptor)
        val target = modelFile(descriptor)
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            throw java.io.IOException("Rename failed: ${partial.name} -> ${target.name}")
        }
        target
    }

    /** Deletes the completed file and any partial for [descriptor]. */
    fun delete(descriptor: ModelDescriptor): DpsResult<Unit> = dpsCatching(
        onError = { DpsError.Model.StorageFailure("Cannot delete model.", it) },
    ) {
        modelFile(descriptor).takeIf { it.exists() }?.delete()
        partialFile(descriptor).takeIf { it.exists() }?.delete()
        Unit
    }

    /** Deletes every orphaned `.part` file. Returns bytes reclaimed. */
    fun deletePartials(): DpsResult<Long> = dpsCatching(
        onError = { DpsError.Model.StorageFailure("Cannot clear partial downloads.", it) },
    ) {
        var reclaimed = 0L
        rootDir.listFiles()
            ?.filter { it.name.endsWith(PARTIAL_SUFFIX) }
            ?.forEach { file ->
                reclaimed += file.length()
                file.delete()
            }
        reclaimed
    }

    /**
     * Free space available to this app, in bytes.
     *
     * Uses [File.getUsableSpace] rather than `StatFs`: it reports space this
     * process may actually use after quotas and reserves, which is the number
     * that determines whether a write will succeed. `getFreeSpace` is
     * optimistic and will happily promise room a download then fails to use.
     */
    fun availableBytes(): Long = rootDir.usableSpace

    /** Total bytes occupied by completed model files. */
    fun totalModelBytes(): Long =
        rootDir.listFiles()
            ?.filter { !it.name.endsWith(PARTIAL_SUFFIX) }
            ?.sumOf { it.length() }
            ?: 0L

    /** Total bytes held by in-progress downloads. */
    fun totalPartialBytes(): Long =
        rootDir.listFiles()
            ?.filter { it.name.endsWith(PARTIAL_SUFFIX) }
            ?.sumOf { it.length() }
            ?: 0L

    /** Completed model files present on disk. */
    fun listModelFiles(): List<File> =
        rootDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PARTIAL_SUFFIX) }
            ?: emptyList()

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }
}
