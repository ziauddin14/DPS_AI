package com.softwaremine.dps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.model.ModelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Thread-count benchmark for on-device inference (Day 04 optimisation phase).
 *
 * ## Purpose
 * Profiling established that ~99% of generation time is model computation —
 * prompt ingestion (76%) plus per-token forward passes (23%) — and that
 * synchronisation costs 0.06%. The open question is whether that computation is
 * **compute-bound**, in which case more threads help, or **memory-bandwidth
 * bound**, in which case they will not.
 *
 * This measures rather than assumes. `ModelConfig.DEFAULT_THREAD_COUNT` is not
 * changed until the data says which value to change it to.
 *
 * ## How compute-bound vs bandwidth-bound is distinguished
 * Process CPU time is sampled either side of each run and divided by wall time,
 * giving **effective cores used**:
 *
 *  - Scales with thread count and latency drops → compute-bound; more threads help.
 *  - Effective cores plateaus while latency stays flat → the cores are stalled
 *    waiting on memory, and thread count is the wrong lever entirely.
 *
 * A Q4_K_M 1.5B model reads roughly 1.1 GB of weights per token, so
 * bandwidth-bound is the more likely outcome on budget hardware. The
 * measurement decides it, not the expectation.
 *
 * ## Cost
 * Each configuration reloads the model (~5 s) and runs two generations. Roughly
 * two minutes total. Kept in a separate class from the validation suite so it
 * can be run deliberately rather than on every test pass.
 */
@RunWith(AndroidJUnit4::class)
class ThreadCountBenchmarkTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val descriptor = ModelCatalog.DEFAULT

    private fun bench(metric: String, value: String) {
        android.util.Log.i(BENCH_TAG, "$metric = $value")
    }

    /** Cumulative CPU time (user + system) for this process, in milliseconds. */
    private fun processCpuMillis(): Long {
        val stat = File("/proc/self/stat").readText()
        // Field 14 = utime, 15 = stime, in clock ticks. The comm field can
        // contain spaces, so parse from the closing parenthesis onward.
        val afterComm = stat.substringAfterLast(") ").split(" ")
        val utime = afterComm[11].toLong()
        val stime = afterComm[12].toLong()
        return (utime + stime) * MILLIS_PER_TICK
    }

    // Block body, not an expression body. An expression body would adopt the
    // type of the last statement — android.util.Log.i returns Int — and JUnit
    // rejects any test method that does not return void.
    @Test
    fun benchmarkThreadCounts() {
        runBlocking { runBenchmark() }
    }

    private suspend fun runBenchmark() {
        val modelFile = File(File(context.filesDir, "models"), descriptor.fileName)
        assumeTrue(
            "Model not present at ${modelFile.absolutePath}",
            modelFile.exists() && modelFile.length() == descriptor.sizeBytes,
        )

        val container = AiContainer(context)
        assertTrue(container.aiEngine.initialize() is DpsResult.Success)

        bench("device_cores", "${Runtime.getRuntime().availableProcessors()}")
        android.util.Log.i(BENCH_TAG, "===== thread-count benchmark starting =====")

        for (threads in THREAD_COUNTS) {
            container.aiEngine.unloadModel()

            val config = ModelConfig.SECRETARY.copy(
                threadCount = threads,
                maxOutputTokens = MAX_TOKENS,
            )

            val loadStart = System.currentTimeMillis()
            val loaded = container.aiEngine.loadModel(descriptor, config)
            val loadMs = System.currentTimeMillis() - loadStart
            assertTrue("Load failed at threads=$threads", loaded is DpsResult.Success)

            // Warm-up pass, discarded. The first generation after a load also
            // pays first-touch page-in of the mmap'd weights, which would
            // otherwise be attributed to whichever thread count ran first.
            runOnce(container, config, WARMUP_PROMPT)

            val cpuBefore = processCpuMillis()
            val wallStart = System.currentTimeMillis()
            val result = runOnce(container, config, BENCH_PROMPT)
            val wallMs = System.currentTimeMillis() - wallStart
            val cpuMs = processCpuMillis() - cpuBefore

            val effectiveCores = if (wallMs > 0) cpuMs.toDouble() / wallMs else 0.0

            bench("threads=$threads load_ms", "$loadMs")
            bench("threads=$threads ttft_ms", "${result.ttftMs}")
            bench("threads=$threads total_ms", "$wallMs")
            bench("threads=$threads completion_tokens", "${result.tokens}")
            bench("threads=$threads tokens_per_sec", "%.2f".format(result.tokensPerSecond))
            bench("threads=$threads cpu_ms", "$cpuMs")
            bench("threads=$threads effective_cores", "%.2f".format(effectiveCores))
        }

        container.aiEngine.shutdown()
        android.util.Log.i(BENCH_TAG, "===== thread-count benchmark complete =====")
    }

    private data class RunResult(
        val ttftMs: Long,
        val tokens: Int,
        val tokensPerSecond: Double,
    )

    private suspend fun runOnce(
        container: AiContainer,
        config: ModelConfig,
        prompt: String,
    ): RunResult {
        val request = CompletionRequest(
            prompt = prompt,
            config = config,
            stopSequences = descriptor.stopSequences,
        )

        val started = System.currentTimeMillis()
        var firstTokenAt = 0L
        var completed: CompletionChunk.Completed? = null

        container.aiEngine.generate(request).collect { chunk ->
            when (chunk) {
                is CompletionChunk.Token -> if (firstTokenAt == 0L) {
                    firstTokenAt = System.currentTimeMillis()
                }
                is CompletionChunk.Completed -> completed = chunk
                is CompletionChunk.Failed -> Unit
            }
        }

        val done = completed
        return RunResult(
            ttftMs = if (firstTokenAt > 0) firstTokenAt - started else -1,
            tokens = done?.completion?.usage?.completionTokens ?: 0,
            tokensPerSecond = done?.completion?.tokensPerSecond ?: 0.0,
        )
    }

    private companion object {
        const val BENCH_TAG = "DPS/Bench"

        /** Ticks per second is 100 on every Android ABI we ship. */
        const val MILLIS_PER_TICK = 10L

        val THREAD_COUNTS = listOf(4, 6, 8)
        const val MAX_TOKENS = 32

        const val WARMUP_PROMPT =
            "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n"

        /**
         * Fixed prompt across every configuration. Comparing thread counts
         * requires identical work; a varying prompt would change the ingestion
         * cost, which is the dominant term.
         */
        const val BENCH_PROMPT =
            "<|im_start|>system\nYou are DPS, a professional assistant. " +
                "Answer concisely.<|im_end|>\n" +
                "<|im_start|>user\nList three benefits of running AI on device." +
                "<|im_end|>\n<|im_start|>assistant\n"
    }
}
