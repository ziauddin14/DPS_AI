package com.softwaremine.dps.ai.prompt

import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.core.result.getOrNull
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.TokenCounter
import com.softwaremine.dps.domain.conversation.ChatMessage
import com.softwaremine.dps.domain.conversation.ConversationState
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor

/**
 * Turns a conversation into a [CompletionRequest] the runtime can execute.
 *
 * ## Purpose
 * The "Prompt Builder" stage of the Phase G pipeline. It is the *only* place in
 * the application that constructs prompt text, which is what makes ADR-001's
 * central guarantee enforceable: both the Ollama and llama.cpp runtimes receive
 * byte-identical input, because there is exactly one code path that produces it.
 *
 * ## Responsibilities
 * 1. Prepend the system persona.
 * 2. Trim history to fit the context window.
 * 3. Apply the model's chat template ([ChatTemplate]).
 * 4. Emit a [CompletionRequest] with the model's stop sequences attached.
 *
 * ## What this deliberately is not
 * This is **not** a memory layer and **not** secretary logic — both are
 * explicitly out of scope for Day 02.
 *
 * The distinction matters and is easy to blur. Trimming here is *mechanical*:
 * it drops the oldest turns to respect a hard token limit. It does not decide
 * what is *important*, does not summarise, and does not retrieve. When the
 * memory layer arrives it will sit above this class and choose what to include;
 * this class will keep doing nothing but guaranteeing that whatever it is given
 * physically fits.
 *
 * ## The trimming policy
 * Preserved in priority order:
 *
 * 1. **The system prompt — always.** Dropping it produces a model that has
 *    silently forgotten who it is, which reads as erratic behaviour rather than
 *    as a truncation bug.
 * 2. **The newest user turn — always.** Dropping the actual question is
 *    self-evidently unacceptable.
 * 3. **Recent history, newest first**, while it fits.
 *
 * If (1) and (2) alone do not fit, this fails with
 * [DpsError.Runtime.ContextOverflow] rather than truncating. Silent truncation
 * would let the model answer a mutilated question confidently — for a secretary
 * confirming a meeting time, a confidently wrong answer is far worse than an
 * honest refusal.
 *
 * ## Future extensions
 * Day 03 injects tool definitions into the system section. Day 04+ inserts
 * retrieved memory between the system prompt and history.
 *
 * ## Dependencies
 * [ChatTemplateRegistry], [TokenCounter], [DpsLogger]. No Android framework.
 */
class PromptManager(
    private val templates: ChatTemplateRegistry,
    private val logger: DpsLogger,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {

    /**
     * Builds a request for the next assistant turn.
     *
     * @param conversation the current conversation; its newest message should
     *   be the user turn being answered.
     * @param descriptor the loaded model — supplies [PromptFormat] and stop
     *   sequences.
     * @param config supplies the context budget.
     * @param tokenCounter the loaded model's real tokenizer.
     */
    suspend fun build(
        conversation: ConversationState,
        descriptor: ModelDescriptor,
        config: ModelConfig,
        tokenCounter: TokenCounter,
    ): DpsResult<CompletionRequest> {
        val template = templates.templateFor(descriptor.promptFormat)

        val systemMessage = ChatMessage(
            id = SYSTEM_MESSAGE_ID,
            role = MessageRole.SYSTEM,
            content = systemPrompt,
            timestampEpochMillis = 0L,
            status = MessageStatus.Complete,
        )

        // Only complete, non-empty turns are eligible. A message that is still
        // streaming or that failed must never be fed back as history — doing so
        // teaches the model that truncated answers are acceptable output.
        val history = conversation.messages
            .filter { it.role != MessageRole.SYSTEM && it.isPromptEligible }

        if (history.isEmpty()) {
            return DpsResult.Failure(
                DpsError.Unexpected("Cannot build a prompt from an empty conversation."),
            )
        }

        val budget = contextBudget(config)
        val selected = selectWithinBudget(
            systemMessage = systemMessage,
            history = history,
            template = template,
            budgetTokens = budget,
            tokenCounter = tokenCounter,
        )

        return when (selected) {
            is DpsResult.Failure -> selected
            is DpsResult.Success -> {
                val prompt = template.render(selected.value)
                logger.d(
                    TAG,
                    "Built prompt: format=${descriptor.promptFormat} " +
                        "turns=${selected.value.size}/${history.size + 1} budget=$budget",
                )
                DpsResult.Success(
                    CompletionRequest(
                        prompt = prompt,
                        config = config,
                        // Descriptor sequences take precedence: a quantised
                        // artifact occasionally ships a different end token than
                        // its family's default, and the catalog is where that
                        // per-artifact truth is recorded.
                        stopSequences = (descriptor.stopSequences + template.stopSequences)
                            .distinct(),
                    ),
                )
            }
        }
    }

    /**
     * Tokens available for the prompt.
     *
     * The full context window minus the space reserved for the response, minus
     * a safety margin. The margin absorbs the handful of control tokens the
     * template adds around each turn — counting the rendered string rather than
     * the raw content would require rendering once per candidate history
     * length, which is quadratic for no real benefit.
     */
    private fun contextBudget(config: ModelConfig): Int =
        (config.contextLength - config.maxOutputTokens - SAFETY_MARGIN_TOKENS)
            .coerceAtLeast(0)

    /**
     * Selects the largest suffix of [history] that fits alongside the system
     * prompt and the newest user turn.
     */
    private suspend fun selectWithinBudget(
        systemMessage: ChatMessage,
        history: List<ChatMessage>,
        template: ChatTemplate,
        budgetTokens: Int,
        tokenCounter: TokenCounter,
    ): DpsResult<List<ChatMessage>> {
        val systemTokens = tokenCounter.count(systemMessage.content).getOrNull()
            ?: return DpsResult.Failure(DpsError.Runtime.NotLoaded)

        val newestTurn = history.last()
        val newestTokens = tokenCounter.count(newestTurn.content).getOrNull()
            ?: return DpsResult.Failure(DpsError.Runtime.NotLoaded)

        val mandatoryTokens = systemTokens + newestTokens
        if (mandatoryTokens > budgetTokens) {
            // Even the persona plus the question alone will not fit. Report it
            // rather than mutilating either one.
            return DpsResult.Failure(
                DpsError.Runtime.ContextOverflow(
                    requiredTokens = mandatoryTokens,
                    maxTokens = budgetTokens,
                ),
            )
        }

        // Walk backwards from the second-newest turn, admitting history while
        // it fits. Newest-first because recent context is more relevant, and
        // because it guarantees the newest turn is never the one dropped.
        var used = mandatoryTokens
        val included = ArrayDeque<ChatMessage>()
        included.addFirst(newestTurn)

        for (index in history.lastIndex - 1 downTo 0) {
            val message = history[index]
            val tokens = tokenCounter.count(message.content).getOrNull()
                ?: return DpsResult.Failure(DpsError.Runtime.NotLoaded)

            if (used + tokens > budgetTokens) break
            used += tokens
            included.addFirst(message)
        }

        if (included.size < history.size) {
            logger.i(
                TAG,
                "Trimmed history: kept ${included.size} of ${history.size} turns " +
                    "($used/$budgetTokens tokens)",
            )
        }

        return DpsResult.Success(listOf(systemMessage) + included)
    }

    companion object {
        private const val TAG = "PromptManager"
        private const val SYSTEM_MESSAGE_ID = "system"

        /**
         * Headroom for template control tokens and tokenizer boundary effects.
         * Cheap insurance against an off-by-a-few overflow, which on a native
         * runtime is a crash rather than an exception.
         */
        private const val SAFETY_MARGIN_TOKENS = 128

        /**
         * The Day 02 persona.
         *
         * Deliberately minimal. `ai_architecture.md` specifies a full executive
         * secretary persona with planning, tool selection and confirmation
         * rules — that belongs to Day 04, and writing it now would be secretary
         * logic, which this day explicitly excludes.
         *
         * What is here is only what the *conversation foundation* requires: an
         * identity, a register, and AI Rule 1 and 2 (never expose internal
         * architecture or tool names). Those two rules are included from the
         * start because they are safety properties, and retrofitting them after
         * the model has been observed leaking implementation details is much
         * harder than establishing them now.
         */
        const val DEFAULT_SYSTEM_PROMPT: String =
            "You are DPS, a professional personal assistant.\n" +
                "Be concise, clear and direct. Prefer short answers.\n" +
                "Never mention your internal architecture, models, or tools.\n" +
                "If you do not know something, say so plainly."
    }
}
