package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the prompt that asks the model to classify a request.
 *
 * ## Purpose
 * One place that decides how DPS asks for structured output. Prompt wording is
 * as much a piece of engineering as the parser that consumes it, and scattering
 * it would make the two impossible to keep in step.
 *
 * ## Why the prompt is short
 * Prompt ingestion is **76% of inference cost** on this hardware
 * (`docs/PERFORMANCE-DAY-04.md`), and it scales with length. A verbose schema
 * with an example per intent would be clearer to read and would add seconds to
 * every single message the user sends.
 *
 * So the prompt is compact by necessity, and the [IntentJsonParser] absorbs the
 * resulting imprecision — accepting field aliases, unquoted numbers and
 * markdown fences. **Tolerance in the parser buys brevity in the prompt**, and
 * on a device where every prompt token is paid for at ~200 ms, that trade is
 * strongly worth making.
 *
 * ## Why the current date and time are injected
 * The model cannot know them. Without them it cannot resolve "tomorrow at 4"
 * into a date, and the alternative — parsing relative expressions in Kotlin —
 * would guess worse and without the conversation for context.
 *
 * ## The optional `steps` array (Day 05 Phase E Stage 2)
 * "...meeting schedule kar do aur ... reminder laga do" is two actions in one
 * message. Rather than grow the schema with a worked example — which the
 * prompt's own design principle below rejects — a single rule line tells the
 * model it may reply with `{"steps":[{...},{...}]}` instead of a bare object
 * when there is more than one. [IntentJsonParser.parsePlan] treats a bare
 * object as a one-step plan, so this changes nothing for the common case; it
 * only gives the model a way to say "and" when the request actually has one.
 *
 * ## Why conversation memory is not injected here (Day 05 Phase E)
 * "Usko", "us reminder" and a relative offset like "30 minute pehle" are all
 * resolved deterministically, after classification, by
 * [com.softwaremine.dps.ai.memory.ReferenceResolver] — reading
 * [com.softwaremine.dps.domain.memory.ConversationMemory] directly rather than
 * describing it in prose the model has to re-parse. Putting memory into the
 * prompt instead would grow it, and prompt tokens are what Day 04 measured as
 * the dominant cost; the classifier does not need to see memory to do its one
 * job of naming the intent type.
 *
 * ## Dependencies
 * Domain intent types, `java.time`. No Android.
 */
class IntentPromptBuilder(
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {

    /**
     * Builds the classification prompt for [userMessage].
     *
     * @param pendingQuestion the follow-up DPS last asked, when this message is
     *   an answer to it. Supplying it lets the model interpret "at 4pm" as a
     *   time rather than as a fresh, meaningless request.
     */
    fun build(userMessage: String, pendingQuestion: String? = null): String {
        val reference = now()
        val date = reference.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = reference.format(DateTimeFormatter.ofPattern("HH:mm"))
        val day = reference.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        return buildString {
            appendLine("Classify the user's request as JSON. Reply with JSON only.")
            appendLine()
            appendLine("Now: $day $date $time")
            appendLine()
            appendLine("intent must be one of:")
            appendLine(ROUTABLE_INTENTS.joinToString(", ") { it.wireName } + ", conversation")
            appendLine()
            appendLine("Use \"conversation\" for anything that is not a request to act.")
            appendLine()
            appendLine("Shape:")
            appendLine(SCHEMA)
            appendLine()
            appendLine("Rules:")
            appendLine("- Resolve relative times against Now. Use YYYY-MM-DD and HH:MM.")
            appendLine("- Omit fields you did not find. Never invent a time or a name.")
            appendLine(
                "- action_type is \"create\" unless the user is changing or removing " +
                    "something that already exists (\"update\"/\"cancel\"), marking " +
                    "something done (\"complete\"), or asking to see existing things (\"list\").",
            )
            appendLine("- Multiple actions: use {\"steps\":[{...}]} instead of one object.")

            if (pendingQuestion != null) {
                appendLine()
                appendLine("You just asked: \"$pendingQuestion\"")
                appendLine("The user is answering it. Fill only the fields their answer provides.")
            }

            appendLine()
            appendLine("User: $userMessage")
            appendLine("JSON:")
        }
    }

    private companion object {
        /** Everything except conversation, which is described separately. */
        val ROUTABLE_INTENTS = IntentType.entries.filter { it != IntentType.CONVERSATION }

        /**
         * Field list rather than a worked example.
         *
         * Examples steer a small model strongly — often into copying the
         * example's *content* rather than its shape. Naming the fields is
         * shorter and, in this case, less misleading.
         */
        val SCHEMA = """
            {"intent":"...","action_type":"create|update|cancel|complete|list","parameters":{"person":"","date":"","time":"","message":"","title":"","description":"","email":"","phone":"","priority":"","duration":"","period":""}}
        """.trimIndent()
    }
}
