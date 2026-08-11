package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts a [DpsIntent] from whatever the model actually produced.
 *
 * ## Purpose
 * The model is asked for one JSON object. What comes back is frequently *nearly*
 * that: wrapped in a markdown fence, prefixed with "Sure, here you go:",
 * followed by an explanation, or with a trailing comma. A strict parser rejects
 * all of it, and the user sees DPS fail to understand a request it understood
 * perfectly well.
 *
 * This project runs a **1.5B** model on-device. Imperfect JSON is not an edge
 * case here, it is the normal case, and tolerating it is the difference between
 * a feature that works and one that works occasionally.
 *
 * ## What tolerance does *not* mean
 * Nothing is invented. Unparseable output resolves to [IntentType.CONVERSATION]
 * — the safe default — rather than to a guessed action. The parser recovers
 * *structure*; it never supplies *content*.
 *
 * ## Why this is pure Kotlin in `ai/`
 * Every recovery rule below is verified on the JVM against strings a model
 * really produced. Testing this against a live model would be slow, flaky, and
 * would not pin the behaviour down.
 *
 * ## Dependencies
 * kotlinx.serialization. No Android.
 */
class IntentJsonParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) {

    /**
     * Parses [rawModelOutput] into an intent.
     *
     * Never throws and never returns null: unusable output becomes
     * [IntentType.CONVERSATION], because failing safe is the whole point.
     *
     * A `"steps"` array — see [parsePlan] — is deliberately *not* recognised
     * here: a caller that only wants one intent and receives a compound reply
     * should see a single, safely-parsed object, not silently the first step
     * of a plan it never asked for.
     */
    fun parse(rawModelOutput: String): DpsIntent {
        val obj = parseObjectOrNull(rawModelOutput)
            ?: return conversation("no JSON object found in model output")

        return obj.toIntent()
    }

    /**
     * Parses [rawModelOutput] as a **plan** — one or more steps to carry out
     * in order (Day 05 Phase E Stage 2).
     *
     * ## The two shapes accepted
     * `{"steps":[{...},{...}]}` for a compound request ("...meeting schedule
     * kar do aur ... reminder laga do"), or a single bare object — Phase D's
     * original shape — treated as a one-step plan. Every existing
     * single-object caller of [parse] is therefore unaffected by this method
     * existing; only [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]
     * calls it, and only it needs to know a message might mean more than one
     * action.
     *
     * ## Never invents steps
     * An empty or malformed `steps` array, or a `steps` value that is not an
     * array at all, falls back to the single-object reading rather than
     * guessing which of several malformed entries might be salvageable —
     * the same "recover structure, never content" rule [parse] itself follows.
     */
    fun parsePlan(rawModelOutput: String): List<DpsIntent> {
        val obj = parseObjectOrNull(rawModelOutput)
            ?: return listOf(conversation("no JSON object found in model output"))

        val steps = runCatching { obj[KEY_STEPS]?.jsonArray }.getOrNull()
        if (steps == null || steps.isEmpty()) return listOf(obj.toIntent())

        val parsedSteps = steps.mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()?.toIntent()
        }
        // Every element failed to parse as an object — fall back rather than
        // return an empty plan, which the caller would have no honest way to
        // report on.
        return parsedSteps.ifEmpty { listOf(obj.toIntent()) }
    }

    private fun parseObjectOrNull(rawModelOutput: String): JsonObject? {
        val candidate = extractJsonObject(rawModelOutput) ?: return null
        val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull() ?: return null
        return runCatching { element.jsonObject }.getOrNull()
    }

    private fun JsonObject.toIntent(): DpsIntent {
        val type = IntentType.fromWire(stringOrNull(KEY_INTENT) ?: stringOrNull(KEY_ACTION))

        // The parameters object is optional and the model sometimes flattens it
        // into the top level, so both shapes are accepted rather than one being
        // declared correct.
        val paramsObject = runCatching { this[KEY_PARAMETERS]?.jsonObject }.getOrNull() ?: this

        return DpsIntent(
            type = type,
            parameters = paramsObject.toParameters(),
            confidence = floatOrNull(KEY_CONFIDENCE) ?: 0f,
            // Absent as often as not — IntentAction.fromWire defaults to CREATE,
            // and com.softwaremine.dps.ai.memory.ActionDetector catches the rest.
            action = IntentAction.fromWire(stringOrNull(KEY_ACTION_TYPE)),
        )
    }

    /**
     * Finds the first balanced `{...}` block in [text].
     *
     * Brace counting rather than a regex, because a regex cannot match nested
     * objects and the parameters block is nested by design. String literals are
     * tracked so a brace inside a message body — "meet me at {the cafe}" — does
     * not terminate the scan early.
     */
    internal fun extractJsonObject(text: String): String? {
        val cleaned = stripCodeFences(text)
        val start = cleaned.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until cleaned.length) {
            val char = cleaned[index]

            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, index + 1)
                }
            }
        }

        // Unbalanced — the model was cut off mid-object, which happens when the
        // token budget runs out. Nothing salvageable.
        return null
    }

    /**
     * Removes markdown code fences.
     *
     * Instruction-tuned models wrap JSON in ```` ```json ```` blocks
     * persistently, however firmly the prompt asks them not to.
     */
    internal fun stripCodeFences(text: String): String =
        text.replace(CODE_FENCE, "").trim()

    private fun JsonObject.toParameters(): IntentParameters = IntentParameters(
        person = stringOrNull("person") ?: stringOrNull("contact") ?: stringOrNull("name"),
        date = stringOrNull("date"),
        time = stringOrNull("time") ?: stringOrNull("datetime"),
        // Models use all three names for a message body interchangeably.
        message = stringOrNull("message") ?: stringOrNull("body") ?: stringOrNull("text"),
        title = stringOrNull("title") ?: stringOrNull("subject") ?: stringOrNull("summary"),
        description = stringOrNull("description") ?: stringOrNull("details"),
        email = stringOrNull("email") ?: stringOrNull("to"),
        phone = stringOrNull("phone") ?: stringOrNull("number"),
        priority = stringOrNull("priority"),
        duration = stringOrNull("duration"),
        period = stringOrNull("period"),
        // Day 08-B. A distinct key from "message" on purpose — see
        // IntentParameters.reply's doc for why the two must not collide.
        reply = stringOrNull("reply"),
    )

    /**
     * Reads a string field.
     *
     * Non-string primitives are coerced rather than discarded: a model asked for
     * a time will sometimes emit `1600` unquoted, and throwing away a value that
     * is plainly present would force a needless follow-up question.
     *
     * JSON `null` and the literal string `"null"` are both treated as absent —
     * models emit the latter surprisingly often.
     */
    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = runCatching { this[key]?.jsonPrimitive }.getOrNull() ?: return null
        val raw = primitive.contentOrNull?.trim() ?: return null
        return raw.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.floatOrNull(key: String): Float? =
        runCatching { (this[key] as? JsonPrimitive)?.floatOrNull }.getOrNull()

    private fun conversation(reason: String) = DpsIntent(
        type = IntentType.CONVERSATION,
        parameters = IntentParameters(),
        confidence = 0f,
    ).also { lastFallbackReason = reason }

    /** Why the last parse fell back. Diagnostics only; never shown to the user. */
    @Volatile
    var lastFallbackReason: String? = null
        private set

    private companion object {
        const val KEY_INTENT = "intent"
        const val KEY_ACTION = "action"
        const val KEY_PARAMETERS = "parameters"
        const val KEY_CONFIDENCE = "confidence"

        /**
         * Distinct from [KEY_ACTION] on purpose — that key is an alias some models
         * use for the intent *type* (e.g. `{"action":"reminder"}`). This one is the
         * create/update/cancel verb (Day 05 Phase E); reusing the same wire name
         * for both would make the two impossible to tell apart.
         */
        const val KEY_ACTION_TYPE = "action_type"

        /** Optional top-level array for a compound request. See [parsePlan]. */
        const val KEY_STEPS = "steps"

        val CODE_FENCE = Regex("```(?:json|JSON)?")
    }
}
