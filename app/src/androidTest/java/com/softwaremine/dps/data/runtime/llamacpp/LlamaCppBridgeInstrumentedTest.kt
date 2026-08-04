package com.softwaremine.dps.data.runtime.llamacpp

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.concurrency.DefaultDispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.runtime.RuntimeId
import com.softwaremine.dps.domain.runtime.RuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * On-device verification of the JNI seam (Day 03, Phase 4).
 *
 * ## Purpose
 * Proves that the Kotlin ↔ native contract works at runtime, not merely that
 * the symbol names line up. Static verification with `llvm-nm` shows the two
 * sides *match*; only executing on an ARM64 device shows they *work*.
 *
 * ## Why these tests do not load a real model
 * GGUF execution is explicitly out of scope for Day 03, and the model catalog
 * is unprovisioned in any case. That constraint is less limiting than it
 * sounds: every one of the five native entry points can be driven through its
 * error and lifecycle paths without a model file, and those are precisely the
 * paths where JNI marshalling, null-handle safety and cleanup go wrong.
 *
 * What is genuinely *not* covered here is inference itself — see the Day 03
 * report for that gap stated plainly.
 *
 * ## Method ordering
 * Fixed with [MethodSorters.NAME_ASCENDING] and numeric prefixes. These tests
 * share process-global native state (the llama.cpp backend is initialised once
 * per process), so library availability must be established before anything
 * depends on it, and the leak check must run last.
 *
 * ## Dependencies
 * Requires a connected arm64-v8a device. Cannot run on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LlamaCppBridgeInstrumentedTest {

    private val logger = AndroidDpsLogger()
    private val dispatchers = DefaultDispatcherProvider()

    /**
     * The load-bearing test. Everything else is meaningless if this fails.
     *
     * A false result here means `System.loadLibrary("dps_llama")` threw
     * `UnsatisfiedLinkError` — wrong ABI, missing `.so`, or an unresolved
     * dependency.
     */
    @Test
    fun t01_nativeLibraryLoads() {
        assertTrue(
            "libdps_llama.so failed to load: ${LlamaCppBridge.unavailableReason}",
            LlamaCppBridge.isAvailable,
        )
        assertNull("Expected no failure reason", LlamaCppBridge.unavailableReason)
    }

    /**
     * Calling a native method at all proves JNI resolved the symbol.
     *
     * `freeModel(0)` is documented as safe, so this exercises the binding
     * itself: if the exported symbol name did not match what the JVM looks up,
     * this throws `UnsatisfiedLinkError` rather than returning.
     */
    @Test
    fun t02_freeModelWithNullHandleIsSafe() {
        LlamaCppBridge.freeModel(0L)   // must not crash, must not throw
    }

    /** `cancel` is called from another thread in production; must tolerate a null handle. */
    @Test
    fun t03_cancelWithNullHandleIsSafe() {
        LlamaCppBridge.cancel(0L)
    }

    /** Invalid handle must return a negative count rather than dereference it. */
    @Test
    fun t04_tokenCountWithInvalidHandleReturnsNegative() {
        val count = LlamaCppBridge.tokenCount(0L, "hello")
        assertTrue("Expected negative for invalid handle, got $count", count < 0)
    }

    /**
     * A missing model file must return handle 0, not crash.
     *
     * This is the single most important failure path in the whole bridge: the
     * native loader mmaps and dereferences the file directly, so a bad path
     * that is not handled cleanly terminates the process by signal — something
     * no Kotlin `try/catch` can intercept.
     */
    @Test
    fun t05_loadModelWithMissingFileReturnsNullHandle() {
        val missing = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "does-not-exist.gguf",
        )
        val handle = LlamaCppBridge.loadModel(missing.absolutePath, 2048, 4, 0)
        assertEquals("Missing file must yield a null handle", 0L, handle)
    }

    /** An empty path must be rejected before reaching the native loader. */
    @Test
    fun t06_loadModelWithEmptyPathReturnsNullHandle() {
        assertEquals(0L, LlamaCppBridge.loadModel("", 2048, 4, 0))
    }

    /**
     * Drives the same surface through the production Kotlin wrapper rather than
     * the raw bridge, so the full Kotlin → provider → JNI → native → Kotlin
     * round trip is exercised, including `DpsResult` mapping and `StateFlow`
     * transitions.
     */
    @Test
    fun t07_runtimeProviderReportsAvailableAndFailsCleanlyOnMissingModel() = runBlocking {
        val provider = LlamaCppRuntimeProvider(dispatchers = dispatchers, logger = logger)

        assertEquals(RuntimeId.LLAMA_CPP, provider.id)
        assertTrue("Provider must report available on arm64 device", provider.isAvailable())
        assertTrue(
            "Status should be Available before load, was ${provider.status.value}",
            provider.status.value is RuntimeStatus.Available,
        )

        val missing = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "missing.gguf",
        )
        val result = provider.load(missing, ModelConfig.SECRETARY)

        assertTrue("Expected failure for missing file", result is DpsResult.Failure)
        val error = (result as DpsResult.Failure).error
        assertTrue(
            "Expected Runtime.LoadFailed, got ${error::class.simpleName}",
            error is DpsError.Runtime.LoadFailed,
        )

        // unload() is documented as idempotent and is called from memory-pressure
        // handlers, where throwing would turn a recoverable trim into a crash.
        assertTrue(provider.unload() is DpsResult.Success)
        assertTrue(provider.unload() is DpsResult.Success)
    }

    /** Generation without a loaded model must surface NotLoaded, not crash. */
    @Test
    fun t08_generateWithoutLoadedModelFailsCleanly() = runBlocking {
        val provider = LlamaCppRuntimeProvider(dispatchers = dispatchers, logger = logger)
        provider.isAvailable()

        val chunks = provider.generate(
            com.softwaremine.dps.domain.ai.CompletionRequest(
                prompt = "test",
                config = ModelConfig.SECRETARY,
                stopSequences = emptyList(),
            ),
        ).let { flow ->
            val collected = mutableListOf<com.softwaremine.dps.domain.ai.CompletionChunk>()
            flow.collect { collected.add(it) }
            collected
        }

        assertEquals("Expected exactly one terminal chunk", 1, chunks.size)
        val chunk = chunks.first()
        assertTrue(
            "Expected Failed chunk, got $chunk",
            chunk is com.softwaremine.dps.domain.ai.CompletionChunk.Failed,
        )
        assertTrue(
            (chunk as com.softwaremine.dps.domain.ai.CompletionChunk.Failed).error
                is DpsError.Runtime.NotLoaded,
        )
    }

    /**
     * Repeated failed loads must not leak native memory.
     *
     * Each `loadModel` on a missing file allocates a `Session`, attempts the
     * load, and destroys it via `unique_ptr` when the load fails. If that RAII
     * path were wrong, the leak would be invisible to the JVM heap — native
     * allocations are not tracked by the garbage collector — and would surface
     * only as the process being killed after prolonged use.
     *
     * The threshold is deliberately generous. `llama_backend_init` allocates
     * once per process and the allocator does not return freed pages
     * immediately, so a small positive delta is normal; an unbounded one is not.
     */
    @Test
    fun t09_repeatedFailedLoadsDoNotLeakNativeMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.filesDir, "leak-probe.gguf").absolutePath

        // Warm up so one-time backend initialisation is not counted as a leak.
        repeat(WARMUP_ITERATIONS) { LlamaCppBridge.freeModel(LlamaCppBridge.loadModel(missing, 512, 2, 0)) }

        val before = Debug.getNativeHeapAllocatedSize()
        repeat(LEAK_ITERATIONS) {
            val handle = LlamaCppBridge.loadModel(missing, 512, 2, 0)
            assertEquals(0L, handle)
            LlamaCppBridge.freeModel(handle)
            LlamaCppBridge.cancel(handle)
        }
        val after = Debug.getNativeHeapAllocatedSize()
        val growthBytes = after - before

        android.util.Log.i(
            "DPS/LeakProbe",
            "native heap before=$before after=$after delta=$growthBytes over $LEAK_ITERATIONS iterations",
        )

        assertTrue(
            "Native heap grew $growthBytes bytes over $LEAK_ITERATIONS failed loads " +
                "(limit $MAX_ACCEPTABLE_GROWTH_BYTES) — suggests a leak in the load failure path",
            growthBytes < MAX_ACCEPTABLE_GROWTH_BYTES,
        )
    }

    private companion object {
        const val WARMUP_ITERATIONS = 3
        const val LEAK_ITERATIONS = 100

        /** 2 MiB across 100 iterations. A real per-iteration leak overruns this easily. */
        const val MAX_ACCEPTABLE_GROWTH_BYTES = 2L * 1024 * 1024
    }
}
