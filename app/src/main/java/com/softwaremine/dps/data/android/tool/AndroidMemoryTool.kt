package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.data.android.memory.LongTermMemoryStore
import com.softwaremine.dps.domain.memory.SemanticFact
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Remembers, recalls and forgets durable facts (M6).
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `remember_fact` | `title` (subject, required), `message` (the fact, required) | [ToolResult.Success], or [ToolResult.Failure] if the content is disallowed |
 * | `recall_fact` | `title` (subject, required) | [ToolResult.Success] — never fabricates a fact that was never stored |
 * | `forget_fact` | `title` (subject, required) | [ToolResult.Success], or [ToolResult.Failure] if none or several match |
 *
 * ## Why `title`/`message`, not new argument names
 * Mirrors [IntentField.TITLE][com.softwaremine.dps.domain.intent.IntentField]/`MESSAGE`
 * exactly as [com.softwaremine.dps.ai.intent.ToolSelector] already builds
 * every other [ToolCall] — see [com.softwaremine.dps.domain.intent.IntentType.REMEMBER_FACT]'s
 * own doc for why the subject is not carried as `person` instead: reusing
 * that field here would risk misfiring
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s unrelated
 * contact-grounding logic, which keys specifically off a populated `person`
 * parameter.
 *
 * ## Why `forget_fact` can refuse rather than always succeeding
 * Several facts can legitimately share a subject (M6: conflicting facts
 * coexist by design — see [SemanticFact]'s own doc). An ambiguous subject
 * match is reported back as a [ToolResult.Failure] naming the candidates,
 * mirroring [AndroidTaskTool]'s own `notFound`/"Several tasks match, which
 * one?" pattern exactly, rather than guessing which one the user meant or
 * deleting all of them.
 *
 * ## No episodic logging of memory-tool calls themselves
 * Deliberately not wired into [com.softwaremine.dps.ai.memory.EpisodicMemoryRecorder] —
 * logging "DPS remembered a fact" as its own episodic entry would be a
 * confusing, self-referential entry in a history meant to describe *other*
 * completed actions.
 *
 * ## Permissions
 * None — this is on-device storage the app already has private access to,
 * the same reasoning [AndroidTaskTool]'s own doc gives for its identical
 * empty permission set.
 *
 * ## Dependencies
 * [LongTermMemoryStore], [ToolArguments]. No direct Android imports.
 */
class AndroidMemoryTool(
    private val store: LongTermMemoryStore,
) : AndroidTool {

    override val id: ToolId = ToolId.MEMORY

    override val operations: Set<String> = setOf(OP_REMEMBER, OP_RECALL, OP_FORGET)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_REMEMBER -> rememberFact(call)
        OP_RECALL -> recallFact(call)
        OP_FORGET -> forgetFact(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun rememberFact(call: ToolCall): ToolResult {
        val subject = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }
        val factText = when (val parsed = ToolArguments.required(call, ARG_MESSAGE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val saved = store.rememberFact(subject, factText, sourceUtterance = null)
            ?: return ToolResult.Failure(
                reason = "That's not something I can remember for you.",
                retryable = false,
            )

        return ToolResult.Success(
            summary = "Got it — I'll remember that $subject $factText.",
            data = mapOf("fact_id" to saved.id.toString()),
        )
    }

    private suspend fun recallFact(call: ToolCall): ToolResult {
        val subject = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val facts = store.recallFacts(subject)
        if (facts.isEmpty()) {
            return ToolResult.Success(summary = "I don't have anything remembered about $subject.")
        }

        return ToolResult.Success(
            summary = facts.joinToString(" ") { "${it.subject} ${it.factText}." },
            data = buildMap {
                put("count", facts.size.toString())
                facts.forEachIndexed { index, fact ->
                    put("fact_${index}_id", fact.id.toString())
                    put("fact_${index}_text", fact.factText)
                }
            },
        )
    }

    private suspend fun forgetFact(call: ToolCall): ToolResult {
        val subject = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val matches = store.recallFacts(subject)
        return when {
            matches.isEmpty() -> ToolResult.Failure(
                reason = "I couldn't find anything remembered about $subject.",
                retryable = false,
            )

            matches.size > 1 -> ToolResult.Failure(
                reason = "Several things are remembered about $subject: " +
                    matches.joinToString(", ") { it.factText } + ". Which one should I forget?",
                retryable = false,
            )

            else -> {
                val fact = matches.single()
                store.forgetFact(fact.id)
                ToolResult.Success(
                    summary = "Forgot that $subject ${fact.factText}.",
                    data = mapOf("fact_id" to fact.id.toString()),
                )
            }
        }
    }

    private companion object {
        const val OP_REMEMBER = "remember_fact"
        const val OP_RECALL = "recall_fact"
        const val OP_FORGET = "forget_fact"

        const val ARG_TITLE = "title"
        const val ARG_MESSAGE = "message"
    }
}
