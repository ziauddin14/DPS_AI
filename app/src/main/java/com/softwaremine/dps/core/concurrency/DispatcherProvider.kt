package com.softwaremine.dps.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Supplies the coroutine dispatchers used across the app.
 *
 * ## Purpose
 * Injecting dispatchers rather than referencing [Dispatchers] directly makes
 * every layer testable with a deterministic scheduler, which matters
 * disproportionately here: the AI layer is the part of this product most worth
 * testing and the part least able to run on an emulator (ADR-009).
 *
 * ## The reason [inference] exists
 * This is the non-obvious member and the reason this interface is not just
 * three aliases for [Dispatchers].
 *
 * LLM inference is neither IO-bound nor an ordinary CPU task:
 *
 * - It must **not** run on [Dispatchers.IO]. That pool is sized for blocking
 *   IO (64+ threads) and would happily admit several concurrent generations,
 *   each spawning its own llama.cpp worker threads. On a 4–8 core phone that is
 *   a thermal and memory catastrophe.
 * - It must **not** run on [Dispatchers.Default] either. That pool is sized to
 *   the CPU count and is shared with everything else; a single generation
 *   occupying it for seconds starves UI-adjacent work.
 * - llama.cpp is **already internally multi-threaded**. The correct external
 *   concurrency is exactly one.
 *
 * So [inference] is a dedicated single-slot dispatcher. Serialising generation
 * is not a limitation being worked around — it is the correct behaviour, and it
 * also enforces ADR-002's single-resident-model rule at the scheduling level.
 *
 * ## Responsibilities
 * - Name the four execution contexts the app uses.
 * - Guarantee inference is serialised.
 *
 * ## Future extensions
 * Day 05 voice work adds an `audio` dispatcher; speech recognition has its own
 * latency profile and must not queue behind a long generation.
 *
 * ## Dependencies
 * kotlinx-coroutines only.
 */
interface DispatcherProvider {

    /** UI thread. Compose state updates and nothing else. */
    val main: CoroutineDispatcher

    /** General CPU-bound work: parsing, formatting, checksums. */
    val default: CoroutineDispatcher

    /** Blocking IO: file access, HTTP, model downloads. */
    val io: CoroutineDispatcher

    /**
     * Model loading and token generation. Serialised to one concurrent
     * operation. See the note above — this constraint is intentional.
     */
    val inference: CoroutineDispatcher
}

/**
 * Production [DispatcherProvider] backed by the standard kotlinx dispatchers.
 */
class DefaultDispatcherProvider : DispatcherProvider {

    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO

    @OptIn(ExperimentalCoroutinesApi::class)
    override val inference: CoroutineDispatcher = INFERENCE

    private companion object {
        /**
         * Held as a singleton, not a getter: `limitedParallelism` returns a new
         * view on each call, and a fresh view per call would defeat the whole
         * point by permitting concurrent generations.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val INFERENCE: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
