package com.softwaremine.dps

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolResponseGenerator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.secretary.SecretaryOrchestrator
import com.softwaremine.dps.core.logging.DpsLogger
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
import kotlinx.coroutines.launch
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
import java.time.LocalDateTime

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

    /** Runs one generation to completion and returns the assembled text. */
    private suspend fun generateText(prompt: String, maxTokens: Int = 64): String {
        val request = CompletionRequest(
            prompt = prompt,
            config = ModelConfig.SECRETARY.copy(maxOutputTokens = maxTokens),
            stopSequences = descriptor.stopSequences,
        )
        val text = StringBuilder()
        container.aiEngine.generate(request).collect { chunk ->
            if (chunk is CompletionChunk.Token) text.append(chunk.text)
        }
        return text.toString()
    }

    /**
     * Day 08-A. The second prompt here is the first prompt plus its own reply
     * plus a new question — the same shape [com.softwaremine.dps.ai.prompt.PromptManager]
     * builds turn to turn in a growing conversation. Unlike [t04b_warmInferenceThroughput]'s
     * unrelated prompt, this one shares a real, long prefix with the call
     * before it, which is exactly the case KV cache reuse targets.
     *
     * This cannot assert a specific cache-hit count from Kotlin — the native
     * layer only logs it (`PROFILE ... cache_reused=N cache_new=M`), matching
     * this project's established measurement discipline of reading real
     * figures back from logcat rather than estimating them. What this proves
     * is correctness: reusing the shared prefix must not corrupt either reply.
     */
    @Test
    fun t04c_secondTurnWithSharedPrefixProducesCoherentReply() = runBlocking {
        requireModel()

        val firstTurn = "<|im_start|>system\nYou are DPS. Answer in one short sentence." +
            "<|im_end|>\n<|im_start|>user\nWhat is 2 plus 2?<|im_end|>\n<|im_start|>assistant\n"
        val firstReply = generateText(firstTurn)
        perf("shared_prefix_first_reply", firstReply.trim())
        assertTrue("First reply was blank", firstReply.isNotBlank())

        val secondTurn = firstTurn + firstReply.trim() + "<|im_end|>\n" +
            "<|im_start|>user\nNow what is 3 plus 3?<|im_end|>\n<|im_start|>assistant\n"
        val secondReply = generateText(secondTurn)
        perf("shared_prefix_second_reply", secondReply.trim())
        assertTrue("Second reply (shared-prefix turn) was blank", secondReply.isNotBlank())
    }

    /**
     * Day 08-A. A conversation reset — or simply a new, unrelated message —
     * means the next prompt shares little with what the cache holds from the
     * call before it, unlike [t04c_secondTurnWithSharedPrefixProducesCoherentReply]'s
     * long shared prefix. Proves the prefix-diff logic still produces a
     * correct reply when the reusable prefix is short (realistically, just
     * the system preamble), not only when a real conversation continues.
     */
    @Test
    fun t04d_unrelatedPromptAfterPriorTurnStartsClean() = runBlocking {
        requireModel()

        generateText(
            "<|im_start|>system\nYou are DPS. Answer in one short sentence." +
                "<|im_end|>\n<|im_start|>user\nWhat is the capital of France?<|im_end|>\n<|im_start|>assistant\n",
        )

        val unrelated = generateText(
            "<|im_start|>system\nYou are DPS. Answer in one short sentence." +
                "<|im_end|>\n<|im_start|>user\nName a fruit.<|im_end|>\n<|im_start|>assistant\n",
        )
        perf("unrelated_after_prior_turn_reply", unrelated.trim())
        assertTrue("Reply after an unrelated prompt was blank", unrelated.isNotBlank())
    }

    /**
     * Day 08-A. `llama_decode`'s own documented contract allows a cancelled
     * (aborted) call to leave part of its batch committed to the KV cache.
     * The native `reconcile_cache()` step exists precisely so the session's
     * bookkeeping never trusts more than what actually landed — this test
     * proves that from the outside, the only way it is observable from
     * Kotlin: a normal follow-up call after a cancellation must still succeed.
     */
    @Test
    fun t04e_cancellationMidGenerationLeavesSessionUsableForNextCall() = runBlocking {
        requireModel()

        val longRequest = CompletionRequest(
            prompt = "<|im_start|>system\nYou are DPS.<|im_end|>\n" +
                "<|im_start|>user\nWrite a long detailed essay about rivers.<|im_end|>\n" +
                "<|im_start|>assistant\n",
            config = ModelConfig.SECRETARY.copy(maxOutputTokens = 200),
            stopSequences = descriptor.stopSequences,
        )

        val job = launch { container.aiEngine.generate(longRequest).collect { } }
        delay(CANCEL_AFTER_MILLIS)
        job.cancel()
        job.join()

        val followUp = generateText(
            "<|im_start|>system\nYou are DPS. Answer in one short sentence." +
                "<|im_end|>\n<|im_start|>user\nWhat is 5 plus 5?<|im_end|>\n<|im_start|>assistant\n",
        )
        perf("after_cancellation_reply", followUp.trim())
        assertTrue("Reply after a cancelled generation was blank", followUp.isNotBlank())
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

    /**
     * Day 08-B. An ordinary conversational message through the real,
     * unscripted pipeline.
     *
     * This does not assert an inference-pass count directly — that number is
     * read back from the `DPS/llama_jni PROFILE` logcat lines this run
     * produces, one per native `generate()` call, exactly the measurement
     * discipline the rest of this suite already uses. What this proves is
     * functional correctness: the real classification prompt, now carrying
     * the Day 08-B schema addition, still produces a real model, and the
     * reply that reaches the conversation is non-blank and was not silently
     * corrupted by skipping the second pass.
     */
    @Test
    fun t05b_ordinaryConversationReachesAReply() = runBlocking {
        requireModel()
        val session = container.sessionManager
        session.resetConversation()

        android.util.Log.i(PERF_TAG, "DAY08B_CONVERSATION_START")
        assertTrue(session.sendMessage("Salam DPS, kya haal hai?") is DpsResult.Success)

        val finished = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating && state.visibleMessages.any {
                    it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT &&
                        it.status is MessageStatus.Complete
                }
            }
        }

        assertTrue("Conversation did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", finished != null)
        val reply = finished!!.visibleMessages.last()
        android.util.Log.i(PERF_TAG, "DAY08B_CONVERSATION_REPLY = ${reply.content.trim()}")
        assertTrue("Assistant reply was blank", reply.content.isNotBlank())
    }

    /**
     * Day 08-B. A genuine tool request through the real pipeline — must be
     * completely unaffected by the reply-shortcut addition: same one
     * classification pass, real tool execution, real stored data.
     */
    @Test
    fun t05c_toolRequestStillExecutes() = runBlocking {
        requireModel()
        val session = container.sessionManager
        session.resetConversation()

        android.util.Log.i(PERF_TAG, "DAY08B_TOOL_START")
        assertTrue(session.sendMessage("Mere tasks dikhao.") is DpsResult.Success)

        val finished = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating && state.visibleMessages.any {
                    it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT &&
                        it.status is MessageStatus.Complete
                }
            }
        }

        assertTrue("Tool request did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", finished != null)
        val reply = finished!!.visibleMessages.last()
        android.util.Log.i(PERF_TAG, "DAY08B_TOOL_REPLY = ${reply.content.trim()}")
        assertTrue("Assistant reply was blank", reply.content.isNotBlank())
    }

    /**
     * Day 08-C. The deterministic fast path this codebase already has (Day
     * 05 Phase E Stage 2) for a reply to a pending confirmation, through the
     * real, unscripted pipeline. Existing JVM tests already prove this at
     * the scripted-engine level; this is the first real-device confirmation.
     *
     * Uses a TASK deletion request rather than a reminder/calendar-event
     * creation on purpose. `askDeleteConfirmation()` fires purely from
     * classification (`intent.type == TASK`, `action == CANCEL`, a title
     * present) — it does not depend on any prior successful tool execution,
     * unlike a follow-up suggestion, which only appears after a *successful*
     * reminder/event creation. An earlier version of this test used a
     * reminder for that setup step and repeatedly hit a real, pre-existing
     * finding instead: the model's Roman Urdu date/time resolution is
     * unreliable enough that "kal raat 11 baje" (tomorrow 11 PM) was
     * sometimes read as a time already in the past, so the reminder never
     * reached the state this test needs. That is a genuine, separate
     * limitation (documented in the completion notes) — not something a
     * task-deletion request depends on at all, which is why this version
     * uses it instead. The `DAY08C_` markers bound the window a human (or
     * this report) reads back from logcat: one `PROFILE` line is expected
     * for the delete request itself — none after, for the decline.
     */
    @Test
    fun t05d_decliningAFollowUpSuggestionCostsNoExtraInference() = runBlocking {
        requireModel()
        val session = container.sessionManager
        session.resetConversation()

        android.util.Log.i(PERF_TAG, "DAY08C_CONFIRMATION_START")
        assertTrue(session.sendMessage("Buy milk wala task delete kar do.") is DpsResult.Success)

        val afterDeleteRequest = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating && state.visibleMessages.any {
                    it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT &&
                        it.status is MessageStatus.Complete
                }
            }
        }
        assertTrue("Delete request did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", afterDeleteRequest != null)
        val confirmationQuestion = afterDeleteRequest!!.visibleMessages.last { it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT }
        android.util.Log.i(PERF_TAG, "DAY08C_DELETE_REQUEST_REPLY = ${confirmationQuestion.content.trim()}")
        val assistantMessagesBeforeDecline = afterDeleteRequest.visibleMessages.count {
            it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT
        }

        android.util.Log.i(PERF_TAG, "DAY08C_DECLINE_SENT")
        val declineStart = System.currentTimeMillis()
        assertTrue(session.sendMessage("nahi") is DpsResult.Success)

        // Must check for a *new* assistant message specifically — right
        // after sendMessage() appends the user's "nahi", that is briefly the
        // last message and is already MessageStatus.Complete (user turns are
        // never streamed), which would satisfy a role-blind check before any
        // processing has actually happened.
        val afterDecline = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating &&
                    state.visibleMessages.count { it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT } >
                    assistantMessagesBeforeDecline
            }
        }
        val declineElapsedMs = System.currentTimeMillis() - declineStart
        assertTrue("Decline did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", afterDecline != null)

        val declineReply = afterDecline!!.visibleMessages.last { it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT }
        perf("day08c_decline_wall_clock_ms", "$declineElapsedMs")
        android.util.Log.i(PERF_TAG, "DAY08C_DECLINE_REPLY = ${declineReply.content.trim()}")
        android.util.Log.i(PERF_TAG, "DAY08C_CONFIRMATION_END")
        assertTrue("Decline reply was blank", declineReply.content.isNotBlank())
    }

    /**
     * Day 08-D A/B evidence. Run this exact test unmodified against both
     * the pre-08-D and post-08-D [com.softwaremine.dps.ai.intent.IntentPromptBuilder]
     * (via `git stash` on that one file only) to get directly comparable
     * real-device numbers for the same messages in the same session — this
     * test's own behavior does not know or care how the prompt is ordered
     * internally, only what the classification/tool/reply outcome is, so it
     * is valid evidence either way and worth keeping as permanent tool-
     * routing regression coverage afterward.
     *
     * Each scenario resets the conversation first so results are
     * independent and comparable turn-for-turn. `DAY08D_<tag>_REPLY` lines
     * are read back from logcat alongside the native `PROFILE` lines for
     * `prompt_decode`, `decode`, `cache_reused`/`cache_new`, and total time.
     */
    @Test
    fun t05e_day08dToolRoutingAndDateTimeMatrix() = runBlocking {
        requireModel()
        val session = container.sessionManager

        runScenario(session, "TASK_CREATE", "Buy milk ka task bana do.")
        runScenario(session, "TASK_DELETE", "Buy milk wala task delete kar do.")
        runScenario(session, "REMINDER_ABS_DATE", "20 August ko meeting ka reminder laga do.")
        runScenario(session, "REMINDER_ABS_TIME", "4 baje mujhe call karne ka reminder laga do.")
        runScenario(session, "REMINDER_KAL_BARE", "Kal mujhe yaad dila dena ke meeting hai.")
        runScenario(session, "REMINDER_KAL_SHAAM_7", "Kal shaam 7 baje mujhe yaad dila dena.")
        runScenario(session, "REMINDER_KAL_RAAT_11", "Kal raat 11 baje mujhe yaad dila dena.")
        runScenario(session, "CONVERSATION", "Salam DPS, kya haal hai?")
        runScenario(session, "CLARIFICATION", "Remind me about the meeting.")
    }

    /** One scenario: reset, send [message], wait for the assistant's reply, log it tagged with [tag]. */
    private suspend fun runScenario(session: com.softwaremine.dps.ai.session.AiSessionManager, tag: String, message: String) {
        session.resetConversation()
        android.util.Log.i(PERF_TAG, "DAY08D_${tag}_START")
        val started = System.currentTimeMillis()
        assertTrue("$tag: sendMessage failed", session.sendMessage(message) is DpsResult.Success)

        val finished = withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) {
            session.conversation.first { state ->
                !state.isGenerating && state.visibleMessages.any {
                    it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT &&
                        it.status is MessageStatus.Complete
                }
            }
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("$tag did not complete within ${PIPELINE_TIMEOUT_MILLIS}ms", finished != null)

        val reply = finished!!.visibleMessages.last { it.role == com.softwaremine.dps.domain.conversation.MessageRole.ASSISTANT }
        perf("day08d_${tag.lowercase()}_wall_clock_ms", "$elapsed")
        android.util.Log.i(PERF_TAG, "DAY08D_${tag}_REPLY = ${reply.content.trim()}")
        assertTrue("$tag: reply was blank", reply.content.isNotBlank())
    }

    /**
     * Day 08-D controlled retest: OLD vs NEW `Now:` position, same fixed
     * clock, same five date/time messages.
     *
     * ## Why this bypasses [container.sessionManager]
     * The production [SecretaryOrchestrator] is wired (in [AiContainer])
     * against a default, real-clock [IntentPromptBuilder] — exactly what
     * made the first Day 08-D A/B run uncontrolled: the "before" and
     * "after" runs happened at genuinely different wall-clock times, so a
     * changed date/time reply could have come from the reordering, from
     * the different real `Now:` value, or from ordinary model variance,
     * and there was no way to tell which. This test builds its own
     * [ToolOrchestrator]/[SecretaryOrchestrator] pair — reusing the real,
     * on-device [AiContainer.aiEngine], [AiContainer.toolExecutor] and
     * [AiContainer.toolRegistry], so the model and tools are exactly what
     * production uses — but with [IntentPromptBuilder] given a fixed
     * [FIXED_NOW] instead of [LocalDateTime.now]. Run once against the
     * `IntentPromptBuilder.kt` on disk, then again after `git stash` /
     * `git stash pop` on that single file to flip old vs new ordering:
     * with the clock held constant, any difference in the logged
     * `DAY08D_CLOCK_*` results can only come from where `Now:` sits in
     * the prompt.
     *
     * `FIXED_NOW` (Tuesday 2026-08-11 09:00) was chosen so every case has
     * an unambiguous answer: "kal" is tomorrow (08-12), 4pm is hours away
     * the same day, tomorrow evening/night are both comfortably in the
     * future, and 20 August is nine days out — nothing here should
     * plausibly resolve to "in the past" against this clock.
     *
     * A fresh orchestrator pair per case keeps the five results
     * independent of each other (no clarification or memory carried over
     * from one case into the next), the same isolation
     * [t05e_day08dToolRoutingAndDateTimeMatrix] gets via
     * `session.resetConversation()`.
     */
    @Test
    fun t05f_day08dFixedClockDateTimeMatrix() = runBlocking {
        requireModel()

        logClockCase("CASE_A_ABS_DATE", "20 August ko meeting ka reminder laga do.")
        logClockCase("CASE_B_ABS_TIME", "4 baje mujhe call karne ka reminder laga do.")
        logClockCase("CASE_C_KAL_BARE", "Kal mujhe yaad dila dena ke meeting hai.")
        logClockCase("CASE_D_KAL_SHAAM_7", "Kal shaam 7 baje mujhe yaad dila dena.")
        logClockCase("CASE_E_KAL_RAAT_11", "Kal raat 11 baje mujhe yaad dila dena.")
    }

    private suspend fun logClockCase(tag: String, message: String) {
        val outcome = freshSecretary().handle(message)
        android.util.Log.i(PERF_TAG, "DAY08D_CLOCK_${tag}_NOW = $FIXED_NOW")
        android.util.Log.i(PERF_TAG, "DAY08D_CLOCK_${tag}_RESULT = ${summarize(outcome)}")
    }

    private fun summarize(outcome: ToolOrchestrator.Outcome): String = when (outcome) {
        is ToolOrchestrator.Outcome.Handled ->
            "HANDLED date=${outcome.intent.parameters.date} time=${outcome.intent.parameters.time} " +
                "result=${outcome.result} reply=${outcome.reply.trim()}"
        is ToolOrchestrator.Outcome.Clarify ->
            "CLARIFY question=${outcome.question.trim()} " +
                "partialDate=${outcome.resolution.partial.date} partialTime=${outcome.resolution.partial.time}"
        is ToolOrchestrator.Outcome.NeedsPermission ->
            "NEEDS_PERMISSION reply=${outcome.reply.trim()} result=${outcome.result}"
        is ToolOrchestrator.Outcome.Conversational ->
            "CONVERSATIONAL reply=${outcome.replyText?.trim()}"
    }

    /** A fresh, isolated orchestrator pair sharing the real engine/tools but a fixed clock. */
    private fun freshSecretary(): SecretaryOrchestrator {
        val toolOrchestrator = ToolOrchestrator(
            engine = container.aiEngine,
            executor = container.toolExecutor,
            registry = container.toolRegistry,
            promptBuilder = IntentPromptBuilder(now = { FIXED_NOW }),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )
        return SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            logger = silentLogger,
        )
    }

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
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

        /** Tuesday 2026-08-11, 09:00 — see [t05f_day08dFixedClockDateTimeMatrix]'s doc for why. */
        private val FIXED_NOW = LocalDateTime.of(2026, 8, 11, 9, 0)

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
