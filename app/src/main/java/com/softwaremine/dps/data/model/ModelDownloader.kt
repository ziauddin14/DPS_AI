package com.softwaremine.dps.data.model

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.model.ModelDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Downloads model artifacts, resumably.
 *
 * ## Purpose
 * Transfers a ~1.5 GB file to app-private storage, surviving the interruptions
 * that transfer will certainly encounter.
 *
 * ## Why resume is mandatory rather than a refinement
 * The target user is on a Pakistani mobile network. A 1.5 GB download will be
 * interrupted — by a tunnel, a handover, a screen lock, or the OS reclaiming a
 * background socket. A non-resumable downloader restarts from zero each time,
 * which on a metered connection is not merely slow but expensive, and in
 * practice means the product never installs at all.
 *
 * Resume is implemented with HTTP `Range: bytes=<offset>-` against the existing
 * `.part` file. The server must support range requests; [ModelDescriptor]
 * documents this as a hosting requirement.
 *
 * ## Server ignoring Range
 * A server that ignores `Range` replies `200` with the whole body instead of
 * `206` with the remainder. Appending that to an existing partial would produce
 * a corrupt file that is the right *size* but wrong *content* — which the
 * checksum would catch, but only after another full download. This class
 * detects the `200` and truncates the partial first, so the bytes on disk always
 * match what was requested.
 *
 * ## Progress and throughput
 * Emits bytes transferred and a smoothed rate. The rate feeds the user-visible
 * time estimate; without it a multi-minute transfer is indistinguishable from a
 * stall, and users cancel.
 *
 * ## What this class does not do
 * It does not verify checksums ([ChecksumVerifier]), does not decide where files
 * live ([ModelStorage]), and does not decide *whether* to download
 * ([DefaultModelManager]). It moves bytes.
 *
 * ## Dependencies
 * OkHttp, coroutines, `java.io`.
 */
class ModelDownloader(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) {

    /**
     * Downloads [descriptor] into [target], resuming if [target] already holds
     * a partial transfer.
     *
     * Suspends until complete, cancelled, or failed. Cancellation leaves the
     * partial file intact so a later call resumes rather than restarts.
     *
     * @param onProgress invoked with cumulative bytes and current rate.
     */
    suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit,
    ): DpsResult<Unit> = withContext(dispatchers.io) {
        val existingBytes = if (target.exists()) target.length() else 0L

        if (existingBytes > descriptor.sizeBytes) {
            // Larger than the artifact should be: a previous run appended to a
            // file it should have replaced. Unrecoverable by resuming.
            logger.w(TAG, "Partial larger than expected; discarding and restarting.")
            target.delete()
        }

        val resumeFrom = if (target.exists()) target.length() else 0L
        if (resumeFrom == descriptor.sizeBytes) {
            logger.i(TAG, "Partial already complete; skipping transfer.")
            return@withContext DpsResult.Ok
        }

        val request = Request.Builder()
            .url(descriptor.downloadUrl)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DpsResult.Failure(
                        DpsError.Model.DownloadFailed(
                            modelId = descriptor.id,
                            reason = "HTTP ${response.code}",
                        ),
                    )
                }

                // A 200 in response to a Range request means the server sent
                // the whole body. Appending it would corrupt the file, so start
                // over from zero.
                val serverHonouredRange = response.code == HTTP_PARTIAL_CONTENT
                val startOffset = if (resumeFrom > 0 && !serverHonouredRange) {
                    logger.w(TAG, "Server ignored Range; restarting from zero.")
                    0L
                } else {
                    resumeFrom
                }

                val body = response.body
                    ?: return@withContext DpsResult.Failure(
                        DpsError.Model.DownloadFailed(descriptor.id, "Empty response body."),
                    )

                writeBody(
                    source = body.byteStream(),
                    target = target,
                    startOffset = startOffset,
                    totalBytes = descriptor.sizeBytes,
                    onProgress = onProgress,
                )
            }

            val finalSize = target.length()
            if (finalSize != descriptor.sizeBytes) {
                return@withContext DpsResult.Failure(
                    DpsError.Model.DownloadFailed(
                        modelId = descriptor.id,
                        reason = "Size mismatch: expected ${descriptor.sizeBytes}, got $finalSize",
                    ),
                )
            }

            logger.i(TAG, "Downloaded '${descriptor.id}' ($finalSize bytes).")
            DpsResult.Ok
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            // Deliberately not converted to a failure. The partial file stays on
            // disk and the next attempt resumes from where this one stopped.
            logger.i(TAG, "Download cancelled; partial retained for resume.")
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e(TAG, "Download failed for '${descriptor.id}'.", throwable)
            DpsResult.Failure(
                DpsError.Model.DownloadFailed(
                    modelId = descriptor.id,
                    reason = throwable.message ?: throwable::class.simpleName ?: "unknown",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * Streams [source] into [target] starting at [startOffset].
     *
     * Uses [RandomAccessFile] rather than an append stream so the write offset
     * is explicit. Append mode would silently write to the end of whatever is
     * on disk, which is the wrong place whenever the resume offset had to be
     * reset.
     */
    private suspend fun writeBody(
        source: java.io.InputStream,
        target: File,
        startOffset: Long,
        totalBytes: Long,
        onProgress: (Long, Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = startOffset

        var windowStartMillis = System.currentTimeMillis()
        var windowStartBytes = written
        var bytesPerSecond = 0L
        var lastReportMillis = 0L

        RandomAccessFile(target, "rw").use { file ->
            file.seek(startOffset)
            // Truncate anything beyond the resume point. Stale trailing bytes
            // from an aborted write would otherwise survive past the new data.
            file.setLength(startOffset)

            source.buffered(BUFFER_BYTES).use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()

                    val read = stream.read(buffer)
                    if (read <= 0) break

                    file.write(buffer, 0, read)
                    written += read

                    val now = System.currentTimeMillis()
                    val windowMillis = now - windowStartMillis
                    if (windowMillis >= RATE_WINDOW_MILLIS) {
                        bytesPerSecond =
                            (written - windowStartBytes) * MILLIS_PER_SECOND / windowMillis
                        windowStartMillis = now
                        windowStartBytes = written
                    }

                    // Throttled so a fast connection does not flood the UI with
                    // recompositions it cannot usefully render.
                    if (now - lastReportMillis >= PROGRESS_INTERVAL_MILLIS) {
                        lastReportMillis = now
                        onProgress(written, totalBytes, bytesPerSecond)
                    }
                }
            }
        }

        onProgress(written, totalBytes, bytesPerSecond)
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val BUFFER_BYTES = 128 * 1024
        private const val MILLIS_PER_SECOND = 1000L
        private const val RATE_WINDOW_MILLIS = 1000L
        private const val PROGRESS_INTERVAL_MILLIS = 250L

        /**
         * An [OkHttpClient] tuned for very large transfers.
         *
         * The read timeout is generous and the *call* timeout is disabled
         * outright: OkHttp's call timeout bounds the entire request including
         * body transfer, so any finite value would abort a legitimate multi-
         * minute download. Stalls are caught by the read timeout instead, which
         * is the correct instrument — it measures inactivity rather than
         * duration.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
