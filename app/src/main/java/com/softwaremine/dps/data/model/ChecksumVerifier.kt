package com.softwaremine.dps.data.model

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.core.result.dpsCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Computes and verifies SHA-256 hashes of model files.
 *
 * ## Purpose
 * The integrity gate described in ADR-003, and the reason a corrupted download
 * surfaces as a message rather than as a process death.
 *
 * ## Why this matters more than it appears to
 * llama.cpp loads a GGUF by memory-mapping it and reading structure directly.
 * Given a truncated or bit-rotted file it does not raise an error — it
 * dereferences garbage and the process is killed by a signal. That is not a
 * Kotlin exception; no `try/catch` anywhere in this codebase can intercept it,
 * and the crash report points at the load site with no indication that a
 * download weeks earlier was the cause.
 *
 * Hashing before every load converts that entire class of failure into a
 * [DpsError.Model.ChecksumMismatch] the UI can act on.
 *
 * ## Performance and why progress is reported
 * SHA-256 over 1.5 GB takes tens of seconds on the target CPU — it is disk-bound
 * rather than CPU-bound at these sizes, but it is not instantaneous, and a
 * silent multi-second freeze after a long download reads as a hang. Hence the
 * progress callback and its own visible install state.
 *
 * ## Cancellation
 * The hashing loop checks for cancellation each chunk. Without that, cancelling
 * an install would leave a coroutine grinding through a gigabyte of unwanted IO.
 *
 * ## Dependencies
 * `java.security`, `java.io`, coroutines.
 */
class ChecksumVerifier(private val dispatchers: DispatcherProvider) {

    /**
     * Computes the lowercase hex SHA-256 of [file].
     *
     * @param onProgress invoked with cumulative bytes hashed.
     */
    suspend fun sha256(
        file: File,
        onProgress: (bytesHashed: Long) -> Unit = {},
    ): DpsResult<String> = withContext(dispatchers.io) {
        dpsCatching(
            onError = { DpsError.Model.StorageFailure("Cannot hash ${file.name}.", it) },
        ) {
            val digest = MessageDigest.getInstance(ALGORITHM)
            val buffer = ByteArray(BUFFER_BYTES)
            var hashed = 0L

            file.inputStream().buffered(BUFFER_BYTES).use { stream ->
                while (true) {
                    // Cooperative cancellation: without this, cancelling an
                    // install still reads the whole file.
                    currentCoroutineContext().ensureActive()

                    val read = stream.read(buffer)
                    if (read <= 0) break

                    digest.update(buffer, 0, read)
                    hashed += read
                    onProgress(hashed)
                }
            }

            digest.digest().toHexString()
        }
    }

    /**
     * Verifies [file] against [expectedSha256].
     *
     * @return [DpsResult.Success] with `true` on match; a
     *   [DpsError.Model.ChecksumMismatch] failure on mismatch. Mismatch is a
     *   *failure* rather than `Success(false)` because it carries both hashes,
     *   which is what makes the problem diagnosable.
     */
    suspend fun verify(
        file: File,
        expectedSha256: String,
        modelId: String,
        onProgress: (bytesHashed: Long) -> Unit = {},
    ): DpsResult<Boolean> {
        if (!file.exists()) {
            return DpsResult.Failure(DpsError.Model.NotInstalled(modelId))
        }
        if (expectedSha256.isEmpty()) {
            // Refuse rather than pass. An unprovisioned hash means integrity is
            // unknown, and unknown must never be treated as acceptable for a
            // file that will be mapped into native memory.
            return DpsResult.Failure(
                DpsError.Model.Corrupted(
                    modelId = modelId,
                    reason = "No published SHA-256 for this artifact; integrity cannot be proven.",
                ),
            )
        }

        return when (val computed = sha256(file, onProgress)) {
            is DpsResult.Failure -> computed
            is DpsResult.Success ->
                if (computed.value.equals(expectedSha256, ignoreCase = true)) {
                    DpsResult.Success(true)
                } else {
                    DpsResult.Failure(
                        DpsError.Model.ChecksumMismatch(
                            modelId = modelId,
                            expectedSha256 = expectedSha256,
                            actualSha256 = computed.value,
                        ),
                    )
                }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ALGORITHM = "SHA-256"

        /**
         * 1 MiB. Large enough that syscall overhead is negligible, small enough
         * that cancellation is checked often and the progress callback stays
         * responsive.
         */
        const val BUFFER_BYTES = 1024 * 1024
    }
}
