package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentType

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
 * ## Why there is no `Now:` in this prompt, and no `date`/`time` in the schema (Day 08-E)
 * There used to be both — the model was asked to resolve "tomorrow at 4"
 * against an injected `Now:` into an absolute date/time. Day 08-D moved
 * `Now:` to try to make the rest of this prompt a stable, cacheable prefix,
 * and a controlled fixed-clock retest (identical `Now:`, only its position
 * changed) proved the reordering caused the model to substitute `Now:`'s own
 * value for the time the user actually stated — once producing a
 * structurally successful but factually wrong reminder. That change was
 * rejected.
 *
 * The real problem was never *where* `Now:` sat — the same failure existed
 * with `Now:` at the top too, just less often. It is that a 1.5B model
 * asked to do date arithmetic is unreliable, full stop. So this prompt asks
 * for something the model is actually good at instead: quoting. `raw_when`
 * is the user's own words about *when*, copied verbatim
 * (`"kal shaam 7 baje"`, `"20 August"`, `"4 baje"`) — never computed. Turning
 * that quote into an absolute `date`/`time` is
 * [com.softwaremine.dps.ai.memory.TemporalPhraseResolver]'s job: deterministic
 * Kotlin, reading the real system clock, run after classification, never
 * trusting the model for the final value. See
 * [com.softwaremine.dps.domain.intent.IntentParameters.rawWhen]'s doc for the
 * full account.
 *
 * A pleasant side effect: with no `Now:` and no `date`/`time` to fill, this
 * entire prompt is now identical on every single classification call for the
 * life of a loaded model — no per-call dynamic content survives except the
 * user's own message (and the optional pending-question/recent-context
 * blocks, which already varied before this change). That gives
 * [com.softwaremine.dps.data.runtime.llamacpp.LlamaCppRuntimeProvider]'s
 * KV-cache reuse a genuinely stable prefix to hit — the goal Day 08-D was
 * chasing — without asking the model to compute anything it cannot reliably
 * compute.
 *
 * ## The optional `steps` array (Day 05 Phase E Stage 2; salience fix Day 09)
 * "...meeting schedule kar do aur ... reminder laga do" is two actions in one
 * message. The original version of this prompt named the `{"steps":[...]}`
 * shape only in a single `Rules:` bullet, while `Shape:` showed just the
 * single-object form — real-device evidence (Day 09) found the model never
 * once produced a `steps` array across 30 compound-message calls, always
 * defaulting to the one shape shown prominently. `Shape:` now names both
 * forms as equally-weighted, schema-only entries — still no field-value
 * example, so the "small models copy content, not shape" principle below is
 * unchanged — so the model has an actual structural choice to make instead
 * of one shape shown and a second one merely described in prose.
 * [IntentJsonParser.parsePlan] treats a bare object as a one-step plan, so
 * this changes nothing for the common case; it only gives the model a real
 * way to say "and" when the request actually has one.
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
 * Domain intent types only. No `java.time`, no Android — this class no
 * longer has anything time-dependent to inject (Day 08-E).
 */
class IntentPromptBuilder {

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
        return buildString {
            appendLine("Classify the user's request as JSON. Reply with JSON only.")
            appendLine()
            appendLine("intent must be one of:")
            appendLine(ROUTABLE_INTENTS.joinToString(", ") { it.wireName } + ", conversation")
            appendLine()
            appendLine("Use \"conversation\" for anything that is not a request to act.")
            appendLine()
            appendLine("Shape:")
            appendLine("One action:")
            appendLine(SCHEMA_SINGLE)
            appendLine("More than one action:")
            appendLine(SCHEMA_STEPS)
            appendLine()
            appendLine("Rules:")
            appendLine(
                "- For raw_when, copy the user's own words about when — \"kal shaam 7 baje\", " +
                    "\"20 August\", \"4 baje\" — exactly as they said it. Never compute a date " +
                    "or time yourself, and never guess one that was not said.",
            )
            appendLine("- Omit fields you did not find. Never invent a time or a name.")
            appendLine(
                "- action_type is \"create\" unless the user is changing or removing " +
                    "something that already exists (\"update\"/\"cancel\"), marking " +
                    "something done (\"complete\"), or asking to see existing things (\"list\").",
            )
            appendLine("- If the message names more than one action, use the second shape above, one entry per action.")
            appendLine(
                "- If intent is \"conversation\", put your full reply to the user in " +
                    "parameters.reply — never in parameters.message, which is only for " +
                    "text you are sending someone else. Be concise, professional and " +
                    "direct, as DPS always is. Never just repeat the user's own words.",
            )

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
        val SCHEMA_SINGLE = """
            {"intent":"...","action_type":"create|update|cancel|complete|list","parameters":{"person":"","raw_when":"","message":"","title":"","description":"","email":"","phone":"","priority":"","duration":"","period":"","reply":""}}
        """.trimIndent()

        /**
         * Structural placeholders only, deliberately not each step's full
         * field list — that would visually dwarf [SCHEMA_SINGLE] and risk
         * steering the model right back toward the single-object shape it
         * already over-favors (Day 09). The array-of-objects shape is the
         * only thing this needs to convey; a step's actual fields are
         * exactly [SCHEMA_SINGLE]'s `parameters`, already shown above.
         */
        val SCHEMA_STEPS = """
            {"steps":[{...},{...}]}
        """.trimIndent()
    }
}
