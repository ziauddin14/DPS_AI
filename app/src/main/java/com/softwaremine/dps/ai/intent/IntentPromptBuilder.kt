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
 * ## Why `Now:` sits near the end, not the top (Day 08-D)
 * Everything from the opening instruction through the end of Rules is
 * identical on every single classification call for the life of a loaded
 * model — no user data, no timestamp, no conversation state. [Now][now]
 * changes on every call, and having it appear second (right after the
 * opening line) meant that one line broke
 * [com.softwaremine.dps.data.runtime.llamacpp.LlamaCppRuntimeProvider]'s KV
 * cache prefix match for the other ~90% of the prompt that never changes —
 * confirmed on-device (Day 08 audit, then the Day 08-D investigation):
 * consecutive classification calls in the same session were reusing only
 * ~11% of the prompt from cache. Moving `Now:` — and only `Now:`, same
 * wording, same value — to immediately before `User:` lets the stable block
 * above it be reused on every subsequent call, while `Now:` still sits right
 * next to the message it resolves relative times for.
 *
 * This is a pure reordering. No word in the stable block changed, and rule
 * 1 ("Resolve relative times against Now") still reads correctly — the
 * model sees the whole prompt before generating regardless of where within
 * it any one line sits.
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
 * ## `parameters.reply` carries the conversational answer (Day 08-B)
 * The Day 08 audit measured ordinary conversation paying for two full
 * inference passes: this classification call, then a *second*, unrelated
 * [com.softwaremine.dps.ai.prompt.PromptManager]-built generation just to
 * answer. `reply` lets the model answer directly, in the same pass, when
 * `intent` is `"conversation"`, so one pass can serve both jobs.
 *
 * This is a field of its own rather than reusing `message` — an early
 * on-device measurement did exactly that and the model echoed the user's
 * own text back instead of answering. `message` already means "body text to
 * send someone" everywhere else in this schema; asking it to also mean "your
 * reply to the user" measurably confused a model this size (see
 * [com.softwaremine.dps.domain.intent.IntentParameters.reply]'s own doc).
 *
 * This is opportunistic, not required: [recentContext] gives the model just
 * enough of the last exchange to answer sensibly, not the full history
 * budget [PromptManager] uses, so a reply here is not guaranteed to be as
 * context-aware as one from the full pipeline. Nothing downstream trusts
 * this field blindly —
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] falls back to
 * the existing, unchanged streaming-generation pass whenever `reply` comes
 * back blank, which is exactly what already happened before this field
 * existed. A model that never reliably populates it costs nothing extra
 * beyond the few added schema/rule tokens; one that does removes an entire
 * redundant pass.
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
     * @param recentContext the last exchange, pre-rendered as plain text (e.g.
     *   `"User: ...\nDPS: ...\n"`), or `null` when there is none yet. Bounded
     *   and small by construction of the caller — see the class doc for why
     *   this is deliberately not the full conversation history.
     */
    fun build(userMessage: String, pendingQuestion: String? = null, recentContext: String? = null): String {
        val reference = now()
        val date = reference.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = reference.format(DateTimeFormatter.ofPattern("HH:mm"))
        val day = reference.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        return buildString {
            // Day 08-D: everything from here through the end of Rules is
            // byte-for-byte identical on every call, for the life of a
            // loaded model — no user data, no timestamp, no conversation
            // state. That makes it a stable prefix 08-A's cache-reuse can
            // hit on every classification call after the first one in a
            // session, rather than just the first line. Nothing below this
            // comment differs in wording from before Day 08-D — only
            // "Now: ..." moved, from here down to just above "User:".
            appendLine("Classify the user's request as JSON. Reply with JSON only.")
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
            appendLine(
                "- If intent is \"conversation\", put your full reply to the user in " +
                    "parameters.reply — never in parameters.message, which is only for " +
                    "text you are sending someone else. Be concise, professional and " +
                    "direct, as DPS always is. Never just repeat the user's own words.",
            )
            // --- end of the stable prefix ---

            if (pendingQuestion != null) {
                appendLine()
                appendLine("You just asked: \"$pendingQuestion\"")
                appendLine("The user is answering it. Fill only the fields their answer provides.")
            }

            if (!recentContext.isNullOrBlank()) {
                appendLine()
                appendLine("Recent conversation:")
                append(recentContext)
            }

            // Now: kept immediately beside the message it resolves relative
            // times for, rather than at the top — same content, same
            // wording, only relocated. See the class doc's KV-cache note.
            appendLine()
            appendLine("Now: $day $date $time")
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
            {"intent":"...","action_type":"create|update|cancel|complete|list","parameters":{"person":"","date":"","time":"","message":"","title":"","description":"","email":"","phone":"","priority":"","duration":"","period":"","reply":""}}
        """.trimIndent()
    }
}
