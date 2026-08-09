package com.softwaremine.dps

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.conversation.MessageStatus
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.core.result.DpsResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * First real offline GGUF inference on device (Day 04, Phases C–G).
 *
 * ## Purpose
 * Proves the complete offline pipeline end to end with a real model:
 * checksum → mmap load → context init → prompt build → token generation →
 * streaming → cancellation → unload, and measures the KPIs while doing it.
 *
 * ## Preconditions
 * Requires the verified GGUF present in the app's private model directory. All
 * tests self-skip via [assumeTrue] when it is absent, so the suite still passes
 * on a machine without the artifact rather than reporting spurious failures.
 *
 * ## Method ordering
 * Fixed and numerically prefixed. These tests share one process-global engine
 * and a resident model; cold-load timing is only meaningful before anything
 * else has loaded, and unload must run last.
 *
 * ## Measurement discipline
 * Every figure is logged under `DPS/Perf` with its unit. Nothing here is
 * estimated — the numbers in the Day 04 report are read back from logcat.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GgufInferenceInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val descriptor = ModelCatalog.DEFAULT

    /**
     * Process-scoped, deliberately.
     *
     * JUnit constructs a fresh test instance for every method, so a per-instance
     * container would hand each test its own [com.softwaremine.dps.domain.ai.AiEngine]
     * with nothing loaded — the model loaded by the cold-load test would be
     * invisible to every test after it.
     *
     * A single shared container also matches production: `DpsApplication` holds
     * exactly one for the process lifetime, because the model it owns is a
     * process-global resource.
     */
    private val container: AiContainer get() = sharedContainer

    private fun modelFile(): File =
        File(File(context.filesDir, "models"), descriptor.fileName)

    private fun requireModel() {
        val f = modelFile()
        assumeTrue(
            "Model not present at ${f.absolutePath} — push it before running this suite",
            f.exists() && f.length() == descriptor.sizeBytes,
        )
    }

    private fun perf(metric: String, value: String) {
        android.util.Log.i(PERF_TAG, "$metric = $value")
    }

    // -----------------------------------------------------------------
    // Phase C — provisioning and load
    // -----------------------------------------------------------------

    /** The artifact on device must match the catalog byte for byte. */
    @Test
    fun t01_modelPresentAndChecksumVerifies() = runBlocking {
        requireModel()
        val started = System.currentTimeMillis()
        val result = container.modelManager.verify(descriptor)
        val elapsed = System.currentTimeMillis() - started

        perf("checksum_verify_ms", "$elapsed")
        perf("model_size_bytes", "${modelFile().length()}")

        assertTrue(
            "Checksum verification failed: ${(result as? DpsResult.Failure)?.error?.message}",
            result is DpsResult.Success,
        )
    }

    /**
     * Cold load — first load in this process. Includes mmap and context init.
     *
     * Measured against the < 7 s target from `offliceLLM_guide.md`, though note
     * that target covers app cold start rather than model load alone.
     */
    @Test
    fun t02_coldModelLoad() = runBlocking {
        requireModel()
        assertTrue(container.aiEngine.initialize() is DpsResult.Success)

        val started = System.currentTimeMillis()
        val result = container.aiEngine.loadModel(descriptor, ModelConfig.SECRETARY)
        val elapsed = System.currentTimeMillis() - started

        perf("cold_load_ms", "$elapsed")
        perf("native_heap_after_load_bytes", "${Debug.getNativeHeapAllocatedSize()}")

        assertTrue(
            "Cold load failed: ${(result as? DpsResult.Failure)?.error?.message}",
            result is DpsResult.Success,
        )
        assertTrue(container.aiEngine.state.value is AiState.Ready)
        assertEquals(descriptor.id, container.aiEngine.activeModel?.id)
    }

    /** The real tokenizer must work once a model is resident. */
    @Test
    fun t03_tokenCountUsesRealTokenizer() = runBlocking {
        requireModel()
        val result = container.aiEngine.tokenCount("Hello, how are you today?")
        assertTrue("tokenCount failed", result is DpsResult.Success)
        val n = (result as DpsResult.Success).value
        perf("token_count_sample", "$n tokens for 25 chars")
        assertTrue("Expected a positive token count, got $n", n > 0)
    }

    // -----------------------------------------------------------------
    // Phase D — first real inference
    // -----------------------------------------------------------------

    /**
     * **The Day 04 objective.** First real offline response from a GGUF model.
     *
     * Measures time-to-first-token separately from throughput because they are
     * different user experiences: TTFT is what the user perceives as
     * responsiveness, throughput is how fast the answer fills in.
     */
    @Test
    fun t04_firstOfflineInference() = runBlocking {
        requireModel()

        val request = CompletionRequest(
            prompt = "<|im_start|>system\nYou are DPS, a professional assistant. " +
                "Answer in one short sentence.<|im_end|>\n" +
                "<|im_start|>user\nWhat is the capital of Pakistan?<|im_end|>\n" +
                "<|im_start|>assistant\n",
            config = ModelConfig.SECRETARY.copy(maxOutputTokens = 64),
            stopSequences = descriptor.stopSequences,
        )

        val started = System.currentTimeMillis()
        var firstTokenAt = 0L
        var tokenCount = 0
        val text = StringBuilder()
        var completed: CompletionChunk.Completed? = null
        var failure: CompletionChunk.Failed? = null

        container.aiEngine.generate(request).collect { chunk ->
            when (chunk) {
                is CompletionChunk.Token -> {
                    if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                    tokenCount++
                    text.append(chunk.text)
                }
                is CompletionChunk.Completed -> completed = chunk
                is CompletionChunk.Failed -> failure = chunk
            }
        }
        val totalMs = System.currentTimeMillis() - started

        assertTrue("Generation failed: ${failure?.error?.message}", failure == null)
        val done = requireNotNull(completed) { "No terminal Completed chunk" }

        val ttft = if (firstTokenAt > 0) firstTokenAt - started else -1
        perf("time_to_first_token_ms", "$ttft")
        perf("total_generation_ms", "$totalMs")
        perf("streamed_token_events", "$tokenCount")
        perf("prompt_tokens", "${done.completion.usage.promptTokens}")
        perf("completion_tokens", "${done.completion.usage.completionTokens}")
        perf("tokens_per_second", "%.2f".format(done.completion.tokensPerSecond))
        perf("finish_reason", "${done.completion.finishReason}")
        android.util.Log.i(PERF_TAG, "FIRST_OFFLINE_RESPONSE = ${done.completion.text.trim()}")

        assertTrue("Model produced no text", done.completion.text.isNotBlank())
        assertTrue("Expected streamed tokens", tokenCount > 0)
    }

    /**
     * Second inference, same session — isolates steady-state throughput from
     * one-off cost.
     *
     * The first inference pays for first-touch page-in of the mmap'd model:
     * the kernel faults ~1 GB in from storage as the forward pass walks the
     * weights. That cost is real but is paid once, and reporting it as
     * "tokens per second" would badly misrepresent what a user experiences on
     * their second message. Measuring both is the only honest way to state the
     * figure.
     */
    @Test
    fun t04b_warmInferenceThroughput() = runBlocking {
        requireModel()

        val request = CompletionRequest(
            prompt = "<|im_start|>system\nYou are DPS. Answer in one short sentence." +
                "<|im_end|>\n<|im_start|>user\nName three primary colours.<|im_end|>\n" +
                "<|im_start|>assistant\n",
            config = ModelConfig.SECRETARY.copy(maxOutputTokens = 64),
            stopSequences = descriptor.stopSequences,
        )

        val started = System.currentTimeMillis()
        var firstTokenAt = 0L
        var completed: CompletionChunk.Completed? = null

        container.aiEngine.generate(request).collect { chunk ->
            when (chunk) {
                is CompletionChunk.Token -> if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                is CompletionChunk.Completed -> completed = chunk
                is CompletionChunk.Failed -> Unit
            }
        }
        val totalMs = System.currentTimeMillis() - started
        val done = requireNotNull(completed) { "No terminal chunk on warm inference" }

        perf("warm_time_to_first_token_ms", "${firstTokenAt - started}")
        perf("warm_total_generation_ms", "$totalMs")
        perf("warm_prompt_tokens", "${done.completion.usage.promptTokens}")
        perf("warm_completion_tokens", "${done.completion.usage.completionTokens}")
        perf("warm_tokens_per_second", "%.2f".format(done.completion.tokensPerSecond))
        android.util.Log.i(PERF_TAG, "WARM_RESPONSE = ${done.completion.text.trim()}")

        assertTrue("Warm inference produced no text", done.completion.text.isNotBlank())
    }

    /** The full session pipeline: prompt build → generate → parse → conversation. */
    @Test
    fun t05_sessionPipelineProducesAssistantMessage() = runBlocking {
        requireModel()
        val session = container.sessionManager

        assertTrue(session.startSession() is DpsResult.Success)
        assertTrue(session.sendMessage("Reply with exactly: OK") is DpsResult.Success)

        val finished = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating && state.visibleMessages.any {
                    it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT &&
                        it.status is MessageStatus.Complete
                }
            }
        }

        assertTrue("Pipeline did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", finished != null)
        val reply = finished!!.visibleMessages.last()
        android.util.Log.i(PERF_TAG, "PIPELINE_REPLY = ${reply.content.trim()}")
        assertTrue("Assistant reply was blank", reply.content.isNotBlank())
    }

    // -----------------------------------------------------------------
    // Phase E — streaming and cancellation
    // -----------------------------------------------------------------

    /**
     * Cancellation must stop native work promptly, not merely detach the
     * collector. A generation left running burns battery producing tokens
     * nobody will read.
     */
    @Test
    fun t06_cancellationStopsGenerationPromptly() = runBlocking {
        requireModel()
        val session = container.sessionManager
        session.resetConversation()

        assertTrue(session.sendMessage("Write a long detailed essay about rivers.") is DpsResult.Success)

        // Let it get going, then stop it.
        withTimeoutOrNull(GENERATION_START_TIMEOUT_MILLIS) {
            session.conversation.first { it.isGenerating }
        }
        delay(CANCEL_AFTER_MILLIS)

        val cancelledAt = System.currentTimeMillis()
        session.cancelGeneration()

        val settled = withTimeoutOrNull(CANCEL_SETTLE_TIMEOUT_MILLIS) {
            session.conversation.first { !it.isGenerating }
        }
        val settleMs = System.currentTimeMillis() - cancelledAt

        perf("cancel_settle_ms", "$settleMs")
        assertTrue("Conversation did not leave generating state after cancel", settled != null)
    }

    // -----------------------------------------------------------------
    // Phase F — memory and warm load
    // -----------------------------------------------------------------

    /** Process memory while the model is resident, against the < 3 GB budget. */
    @Test
    fun t07_memoryFootprintWhileLoaded() {
        requireModel()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        perf("pss_total_kb", "${info.totalPss}")
        perf("private_dirty_kb", "${info.totalPrivateDirty}")
        perf("shared_dirty_kb", "${info.totalSharedDirty}")
        perf("native_heap_alloc_bytes", "${Debug.getNativeHeapAllocatedSize()}")
        perf("native_heap_size_bytes", "${Debug.getNativeHeapSize()}")

        // mmap means most of the model counts as clean, file-backed pages the
        // kernel can reclaim — which is exactly why mmap was chosen (ADR-002).
        // Private dirty is the memory genuinely attributable to this process.
        assertTrue("Private dirty memory implausibly low — is the model loaded?", info.totalPrivateDirty > 0)
    }

    /** Warm load after an explicit unload. Exercises the unload path too. */
    @Test
    fun t08_unloadThenWarmLoad() = runBlocking {
        requireModel()

        val unloadStart = System.currentTimeMillis()
        assertTrue(container.aiEngine.unloadModel() is DpsResult.Success)
        perf("unload_ms", "${System.currentTimeMillis() - unloadStart}")
        perf("native_heap_after_unload_bytes", "${Debug.getNativeHeapAllocatedSize()}")

        val warmStart = System.currentTimeMillis()
        val result = container.aiEngine.loadModel(descriptor, ModelConfig.SECRETARY)
        perf("warm_load_ms", "${System.currentTimeMillis() - warmStart}")

        assertTrue("Warm load failed", result is DpsResult.Success)
    }

    /** Leaves the device clean and verifies shutdown does not throw. */
    @Test
    fun t09_shutdownReleasesEverything() = runBlocking {
        container.aiEngine.shutdown()
        perf("native_heap_after_shutdown_bytes", "${Debug.getNativeHeapAllocatedSize()}")
        assertTrue(container.aiEngine.state.value is AiState.Idle)
    }

    companion object {
        private const val PERF_TAG = "DPS/Perf"
        private const val PIPELINE_TIMEOUT_MILLIS = 180_000L
        private const val GENERATION_START_TIMEOUT_MILLIS = 60_000L
        private const val CANCEL_AFTER_MILLIS = 3_000L
        private const val CANCEL_SETTLE_TIMEOUT_MILLIS = 30_000L

        /** One engine for the whole suite. See the `container` property. */
        private lateinit var sharedContainer: AiContainer

        @BeforeClass
        @JvmStatic
        fun banner() {
            android.util.Log.i(PERF_TAG, "===== Day 04 GGUF inference suite starting =====")
            sharedContainer = AiContainer(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }
    }
}
