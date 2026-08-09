package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.logging.redact
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentResolution
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.intent.PendingPermissionAction
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.tool.ToolExecutor
import com.softwaremine.dps.domain.tool.ToolRegistry
import com.softwaremine.dps.domain.tool.ToolResult
import com.softwaremine.dps.core.result.DpsResult

/**
 * Connects the offline model to the Android tool framework.
 *
 * ## The pipeline
 * ```
 * user text
 *   → IntentPromptBuilder        build a compact classification prompt
 *   → AiEngine                   one inference pass
 *   → IntentJsonParser           tolerant structure recovery
 *   → ClarificationEngine        complete? or what should we ask?
 *   → ToolSelector               intent → ToolCall
 *   → ToolExecutor               permission gate, timeout, dispatch
 *   → ToolResponseGenerator      ToolResult → a sentence
 * ```
 *
 * ## One inference pass, not two
 * The model is used **only** to classify. Phrasing the reply is templated
 * ([ToolResponseGenerator]). On hardware where a generation costs 5–15 s
 * (`docs/PERFORMANCE-DAY-04.md`), a second pass to rewrite text DPS already
 * knows would roughly double every action's latency — and would let the model
 * claim a message was sent when the user has not yet pressed send.
 *
 * ## Failing towards conversation
 * Every uncertainty resolves to [IntentType.CONVERSATION]: unparseable output,
 * an unknown intent name, an unregistered tool. Answering conversationally when
 * an action was wanted merely disappoints. Acting when only conversation was
 * wanted can create an event or open a message the user never asked for.
 *
 * That asymmetry decides the direction of every fallback here.
 *
 * ## Responsibilities
 * Sequencing only. It parses nothing, matches nothing, and phrases nothing —
 * each of those lives in a pure class that can be tested without a model.
 *
 * ## Dependencies
 * [AiEngine], [ToolExecutor], [ToolRegistry] and the pure intent components.
 * No Android.
 */
class ToolOrchestrator(
    private val engine: AiEngine,
    private val executor: ToolExecutor,
    private val registry: ToolRegistry,
    private val promptBuilder: IntentPromptBuilder,
    private val parser: IntentJsonParser,
    private val clarification: ClarificationEngine,
    private val selector: ToolSelector,
    private val responses: ToolResponseGenerator,
    private val logger: DpsLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** What the orchestrator produced for one user message. */
    sealed interface Outcome {
        /** Not an action. The normal chat pipeline should answer. */
        data class Conversational(val reason: String) : Outcome

        /** An action ran. [reply] is ready to show. */
        data class Handled(
            val reply: String,
            val intent: DpsIntent,
            val result: ToolResult,
        ) : Outcome

        /** Understood but incomplete. [question] should be shown and answered. */
        data class Clarify(
            val question: String,
            val resolution: IntentResolution.NeedsClarification,
        ) : Outcome

        /** Blocked on a permission. The action is held for resumption. */
        data class NeedsPermission(
            val reply: String,
            val result: ToolResult.PermissionRequired,
        ) : Outcome
    }

    /** The follow-up DPS last asked, and what it already understood. */
    private var pendingClarification: IntentResolution.NeedsClarification? = null

    /** An action waiting on a permission grant. */
    private var pendingPermission: PendingPermissionAction? = null

    /**
     * Handles one user message.
     *
     * Never throws: every failure resolves to an [Outcome], because an
     * exception here would surface to the user as a crash rather than as
     * something the assistant can explain.
     */
    suspend fun handle(userMessage: String): Outcome {
        val awaiting = pendingClarification

        val intent = classify(userMessage, awaiting?.question)
            ?: return Outcome.Conversational("classification failed")

        // An answer to a follow-up carries only the missing piece, so it is
        // merged into what was already understood. Without this, "at 4pm" after
        // "remind me about the meeting" would lose the meeting.
        val resolved = if (awaiting != null && intent.type != IntentType.CONVERSATION) {
            intent.copy(parameters = clarification.merge(awaiting.partial, intent.parameters))
        } else if (awaiting != null) {
            // The model saw plain conversation, but we are mid-question — treat
            // the answer as filling the pending intent rather than discarding it.
            awaiting.intent.copy(
                parameters = clarification.merge(awaiting.partial, intent.parameters),
            )
        } else {
            intent
        }

        if (resolved.type == IntentType.CONVERSATION) {
            pendingClarification = null
            return Outcome.Conversational("model classified as conversation")
        }

        return when (val check = clarification.check(resolved)) {
            is ClarificationEngine.Check.Missing -> {
                val needs = IntentResolution.NeedsClarification(
                    intent = resolved,
                    question = check.question,
                    missing = check.fields,
                    partial = resolved.parameters,
                )
                pendingClarification = needs
                logger.i(TAG, "Clarifying ${resolved.type}: missing ${check.fields}")
                Outcome.Clarify(check.question, needs)
            }

            ClarificationEngine.Check.Complete -> {
                pendingClarification = null
                executeIntent(resolved)
            }
        }
    }

    /**
     * Runs the tool for a complete intent.
     *
     * ## Why this is `internal`, not `private` (Day 05 Phase E)
     * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] needs to
     * execute an intent it resolved from conversation memory — a reminder
     * update whose `targetId` came from
     * [com.softwaremine.dps.ai.memory.ReferenceResolver], not from a fresh
     * [classify] pass — without re-running clarification or classification a
     * second time. Exposing this seam lets it reuse the exact same execution,
     * permission-holding and response-phrasing logic [handle] already uses,
     * instead of duplicating any of it (the brief's explicit "do not bypass
     * ToolOrchestrator").
     */
    internal suspend fun executeIntent(intent: DpsIntent): Outcome {
        val call = selector.select(intent)
            ?: return Outcome.Conversational("intent does not map to a tool")

        if (registry.find(call.toolId) == null) {
            // Defensive: the selector only names ids that exist, but a future
            // wiring change could break that, and it must not surface as a crash.
            logger.w(TAG, "Selector produced an unregistered tool: ${call.toolId}")
            return Outcome.Conversational("tool not registered")
        }

        logger.i(TAG, "Executing ${call.toolId.toolName}.${call.operation}")
        val result = executor.execute(call)

        if (result is ToolResult.PermissionRequired) {
            // Held so granting the permission resumes the action instead of
            // making the user repeat their request.
            pendingPermission = PendingPermissionAction(
                call = call,
                intent = intent,
                requestedAtMillis = now(),
            )
            return Outcome.NeedsPermission(responses.describe(result, intent), result)
        }

        return Outcome.Handled(
            reply = responses.describe(result, intent),
            intent = intent,
            result = result,
        )
    }

    /**
     * Retries the action that was blocked on a permission.
     *
     * Called after the user grants one. Returns `null` when nothing is pending
     * or the request has gone stale — a permission granted ten minutes later no
     * longer implies the user still wants the action taken.
     */
    suspend fun resumeAfterPermissionGrant(): Outcome? {
        val pending = pendingPermission ?: return null
        pendingPermission = null

        if (!pending.isFresh(now())) {
            logger.i(TAG, "Discarding stale pending action for ${pending.call.toolId}")
            return null
        }

        logger.i(TAG, "Resuming ${pending.call.toolId.toolName} after permission grant")
        val result = executor.execute(pending.call)

        if (result is ToolResult.PermissionRequired) {
            // Still refused — the user declined, or granted something else.
            return Outcome.NeedsPermission(
                responses.describe(result, pending.intent),
                result,
            )
        }

        return Outcome.Handled(
            reply = responses.describe(result, pending.intent),
            intent = pending.intent,
            result = result,
        )
    }

    /** Discards any pending question or held action. */
    fun reset() {
        pendingClarification = null
        pendingPermission = null
    }

    /** The follow-up currently awaiting an answer, if any. */
    fun pendingQuestion(): String? = pendingClarification?.question

    /** Whether an action is held pending a permission grant. */
    fun hasPendingPermissionAction(): Boolean = pendingPermission != null

    /**
     * Runs one classification pass, parsed as a single intent.
     *
     * Returns `null` only when generation itself failed, which the caller
     * treats as conversation.
     *
     * `internal` rather than `private` so
     * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] can classify
     * through this exact method — same prompt, same parsing, same
     * [ai.memory.ActionDetector]-free baseline — rather than re-implementing
     * classification. See [executeIntent] for the matching rationale.
     */
    internal suspend fun classify(userMessage: String, pendingQuestion: String?): DpsIntent? =
        generateClassification(userMessage, pendingQuestion)?.let(parser::parse)

    /**
     * Runs the same classification pass as [classify], parsed as a **plan**
     * — one step for an ordinary request, more than one for a compound
     * request such as "...meeting schedule kar do aur ... reminder laga do"
     * (Day 05 Phase E Stage 2). Still exactly one inference call either way;
     * only the parsing differs, via [IntentJsonParser.parsePlan].
     *
     * `internal` for the same reason as [classify]:
     * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] needs this
     * exact prompt and parsing, not a reimplementation of either.
     */
    internal suspend fun classifyPlan(userMessage: String, pendingQuestion: String?): List<DpsIntent>? =
        generateClassification(userMessage, pendingQuestion, MAX_PLAN_CLASSIFICATION_TOKENS)?.let(parser::parsePlan)

    private suspend fun generateClassification(
        userMessage: String,
        pendingQuestion: String?,
        maxOutputTokens: Int = MAX_CLASSIFICATION_TOKENS,
    ): String? {
        val prompt = promptBuilder.build(userMessage, pendingQuestion)

        val request = CompletionRequest(
            prompt = prompt,
            // Deterministic and short. Classification wants exactly one answer,
            // not a creative one, and the JSON needed is small — capping output
            // keeps the pass fast on hardware where every token costs ~240 ms.
            config = ModelConfig.DETERMINISTIC.copy(maxOutputTokens = maxOutputTokens),
            stopSequences = emptyList(),
        )

        return when (val completion = engine.generateOnce(request)) {
            is DpsResult.Success -> {
                val text = completion.value.text
                logger.d(TAG, "Classification produced ${redact(text)}")
                text
            }

            is DpsResult.Failure -> {
                logger.w(TAG, "Classification failed: ${completion.error.message}")
                null
            }
        }
    }

    private companion object {
        const val TAG = "ToolOrchestrator"

        /**
         * Output cap for a single-step classification.
         *
         * The JSON needed is well under this. A cap matters because an
         * unconstrained small model will happily continue explaining itself
         * after the object closes, and every extra token is ~240 ms the user
         * waits for output that is discarded.
         */
        const val MAX_CLASSIFICATION_TOKENS = 160

        /**
         * Output cap for [classifyPlan]. Doubled from [MAX_CLASSIFICATION_TOKENS]
         * because a `steps` array needs room for more than one object — the
         * model's own decision at generation time, not something the caller
         * can predict in advance, so the cap has to accommodate the plan
         * case even for a request that turns out to be a single step.
         */
        const val MAX_PLAN_CLASSIFICATION_TOKENS = 320
    }
}
