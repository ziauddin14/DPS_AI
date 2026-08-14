package com.softwaremine.dps.domain.intent

import com.softwaremine.dps.domain.tool.ToolId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user is asking DPS to do.
 *
 * ## Purpose
 * The structured form of a natural-language request, sitting between the model
 * and the tool layer. The LLM produces one of these; [IntentParameters] carries
 * the details.
 *
 * ## Why an intent layer exists at all
 * The model could in principle emit a `ToolCall` directly. It is not asked to,
 * for two reasons.
 *
 * First, tool names, operations and argument keys are implementation detail.
 * Coupling the prompt to them means every tool rename becomes a prompt change,
 * and the model's output becomes untestable without the tool layer present.
 *
 * Second, and more importantly, an intent can be **incomplete**. "Remind me
 * about the meeting" is a valid, well-understood intent with a missing time —
 * something a `ToolCall` cannot represent, because a tool call is by
 * construction a complete instruction. Modelling intent separately is what
 * makes asking a follow-up question possible instead of guessing.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
enum class IntentType(
    /** Wire name the model emits. Lowercase and stable. */
    val wireName: String,
) {
    @SerialName("reminder")
    REMINDER("reminder"),

    @SerialName("calendar_event")
    CALENDAR_EVENT("calendar_event"),

    @SerialName("notification")
    NOTIFICATION("notification"),

    @SerialName("contact_lookup")
    CONTACT_LOOKUP("contact_lookup"),

    @SerialName("whatsapp_message")
    WHATSAPP_MESSAGE("whatsapp_message"),

    @SerialName("email_message")
    EMAIL_MESSAGE("email_message"),

    /**
     * Open the system dialer, pre-filled with a grounded contact's number
     * (Contacts + Calling milestone).
     *
     * The model only ever names *who* — never the number that reaches
     * [com.softwaremine.dps.data.android.tool.AndroidCallTool]. See
     * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s
     * `PERSON_GROUNDING_TYPES`/`CALL_CONFIRMATION_TYPES` for how the real,
     * contact-sourced number is what the confirmation question shows and
     * what the tool call actually carries.
     */
    @SerialName("call_contact")
    CALL_CONTACT("call_contact"),

    // --- Day 06: productivity secretary ---

    @SerialName("task")
    TASK("task"),

    @SerialName("work_log")
    WORK_LOG("work_log"),

    @SerialName("meeting_note")
    MEETING_NOTE("meeting_note"),

    @SerialName("action_item")
    ACTION_ITEM("action_item"),

    @SerialName("report")
    REPORT("report"),

    /**
     * Not a request for an action — ordinary conversation.
     *
     * The default and the safe one. A model uncertain about intent should land
     * here, because answering conversationally when an action was wanted merely
     * disappoints, whereas acting when only conversation was wanted can send a
     * message or create an event the user never asked for.
     */
    @SerialName("conversation")
    CONVERSATION("conversation"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wireName }

        /** Resolves a wire name, defaulting to [CONVERSATION] for anything unknown. */
        fun fromWire(name: String?): IntentType =
            BY_WIRE[name?.lowercase()?.trim()] ?: CONVERSATION
    }
}

/**
 * Details extracted from a request.
 *
 * ## Purpose
 * One flat bag rather than a type per intent. The model fills what it found and
 * omits the rest; which fields *matter* is decided by [IntentType.requiredFields],
 * not by the shape of the data.
 *
 * A per-intent hierarchy was considered and rejected: it forces the model to
 * pick a schema before it has finished understanding the request, and produces
 * a parse failure where a missing-field question would be far more useful.
 *
 * ## Fields
 * Exactly the nine the Phase D brief names. Every one is nullable because the
 * model routinely finds only some of them, and that is a normal outcome rather
 * than an error.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
data class IntentParameters(
    /** A person's name, as the user said it. Resolved to a contact later. */
    val person: String? = null,

    /** Calendar date, ideally `YYYY-MM-DD`. */
    val date: String? = null,

    /** Time of day, ideally `HH:MM` in 24-hour form. */
    val time: String? = null,

    /** Body text for a message or notification. */
    val message: String? = null,

    /** Short label for a reminder, event or notification. */
    val title: String? = null,

    /** Longer detail for a calendar event. */
    val description: String? = null,

    /** An explicit email address, when the user gave one. */
    val email: String? = null,

    /** An explicit phone number, when the user gave one. */
    val phone: String? = null,

    /** `high`, `normal` or `low`. Advisory. */
    val priority: String? = null,

    /**
     * The id of the item this request acts on, when [action] is [IntentAction.UPDATE]
     * or [IntentAction.CANCEL].
     *
     * Never supplied by the model — a user saying "cancel the reminder" does not
     * know or state its numeric id. This is filled in afterwards by
     * [com.softwaremine.dps.ai.memory.ReferenceResolver] from
     * [com.softwaremine.dps.domain.memory.ConversationMemory], which is why it
     * defaults to `null` here rather than being part of the model's JSON schema.
     */
    val targetId: String? = null,

    /**
     * A spoken duration — "3 ghante", "2 hours" — for a work log entry.
     *
     * Day 06. Kept as the raw string rather than parsed minutes: the model is
     * asked to extract it, not to do arithmetic, and
     * [com.softwaremine.dps.ai.intent.ToolSelector] converts it deterministically.
     * Never invented when the user never stated a duration — see
     * [com.softwaremine.dps.domain.productivity.WorkLog]'s "no fabrication" rule.
     */
    val duration: String? = null,

    /**
     * The report scope — `day`, `week` or `month` — for [IntentType.REPORT].
     *
     * Day 06. Absent defaults to `day` in [com.softwaremine.dps.ai.intent.ToolSelector]:
     * showing a day's report when the scope was ambiguous surfaces real stored
     * data rather than fabricating anything, so it is a safe default rather
     * than a guess this class needs to avoid.
     */
    val period: String? = null,

    /**
     * DPS's own answer, when [type][DpsIntent.type] is [IntentType.CONVERSATION]
     * (Day 08-B).
     *
     * Deliberately a field of its own rather than reusing [message]. An early
     * on-device measurement did exactly that and the model echoed the user's
     * own text back instead of answering — [message] already means "body
     * text to send someone" everywhere else in this schema (WhatsApp, email,
     * a notification), and asking the same key to *also* mean "your reply to
     * the user" measurably confused a model this size. A distinct key with
     * an unambiguous rule removed the collision.
     *
     * Not part of [IntentField]/[value] — those name facts the model
     * extracts *about* a request; this is DPS's own output, never something
     * a clarification question would ask for.
     */
    val reply: String? = null,

    /**
     * The user's own words about *when*, quoted verbatim — never a date or
     * time the model computed itself (Day 08-E).
     *
     * The Day 08-D investigation found that asking a 1.5B model to both
     * classify a request *and* resolve "kal shaam 7 baje" against an
     * injected `Now:` was unreliable regardless of where `Now:` sat in the
     * prompt — in a controlled fixed-clock retest the model sometimes
     * substituted `Now:`'s own value for the time the user actually stated,
     * once producing a structurally successful but factually wrong
     * reminder. [rawWhen] replaces that arithmetic with a quoting task: the
     * model copies the phrase, and
     * [com.softwaremine.dps.ai.memory.TemporalPhraseResolver] — deterministic
     * Kotlin, reading the real system clock, never the model — turns it into
     * [date]/[time]. This also means the classification prompt no longer
     * needs `Now:` at all, restoring the byte-for-byte-stable prefix Day
     * 08-D was chasing without the regression it caused.
     *
     * Not part of [IntentField]/[value] — like [reply], this names an
     * intermediate quote the model produced, not a fact
     * [com.softwaremine.dps.ai.intent.ClarificationEngine] gates
     * completeness on; [date]/[time] remain the fields that gate it, exactly
     * as before.
     */
    val rawWhen: String? = null,
) {

    /** Non-blank value for [field], or `null`. */
    fun value(field: IntentField): String? = when (field) {
        IntentField.PERSON -> person
        IntentField.DATE -> date
        IntentField.TIME -> time
        IntentField.MESSAGE -> message
        IntentField.TITLE -> title
        IntentField.DESCRIPTION -> description
        IntentField.EMAIL -> email
        IntentField.PHONE -> phone
        IntentField.PRIORITY -> priority
        IntentField.DURATION -> duration
        IntentField.PERIOD -> period
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** `true` when [field] carries a usable value. */
    fun has(field: IntentField): Boolean = value(field) != null

    /** `true` when any of [fields] carries a usable value. */
    fun hasAny(fields: Set<IntentField>): Boolean = fields.any(::has)
}

/** The nine extractable fields, named so requirements can be expressed over them. */
@Serializable
enum class IntentField(val displayName: String) {
    PERSON("person"),
    DATE("date"),
    TIME("time"),
    MESSAGE("message"),
    TITLE("title"),
    DESCRIPTION("description"),
    EMAIL("email"),
    PHONE("phone"),
    PRIORITY("priority"),
    DURATION("duration"),
    PERIOD("period"),
}

/**
 * What kind of change a request makes.
 *
 * ## Why this exists (Day 05 Phase E)
 * Phase D's [ToolSelector][com.softwaremine.dps.ai.intent.ToolSelector] always
 * built a *creating* [com.softwaremine.dps.domain.tool.ToolCall] — every
 * reminder was `create_reminder`. That was correct for a first request but
 * cannot express "usko 30 minute pehle kar do" (move an *existing* reminder
 * earlier) or "reminder cancel kar do" (remove one). [action] is what lets a
 * classified intent say which of those three this is.
 *
 * [CREATE] is the default so every Phase D call site and test — which never
 * populated this field — keeps meaning exactly what it meant before.
 */
@Serializable
enum class IntentAction(
    /**
     * Wire name the model emits, and the alias set
     * [com.softwaremine.dps.ai.intent.IntentJsonParser] accepts.
     */
    val wireName: String,
) {
    /** A new reminder, event, notification, message or lookup. */
    @SerialName("create")
    CREATE("create"),

    /** A change to something that already exists — resolved via conversation memory. */
    @SerialName("update")
    UPDATE("update"),

    /** Removing something that already exists — resolved via conversation memory. */
    @SerialName("cancel")
    CANCEL("cancel"),

    /**
     * Marking something already tracked as done (Day 06) — a task or action
     * item. Distinct from [UPDATE]: completing sets status and a completion
     * timestamp rather than changing arbitrary fields.
     */
    @SerialName("complete")
    COMPLETE("complete"),

    /** Asking to see existing records rather than acting on one (Day 06). */
    @SerialName("list")
    LIST("list"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wireName }

        /** Resolves a wire name, defaulting to [CREATE] for anything unknown or absent. */
        fun fromWire(name: String?): IntentAction =
            BY_WIRE[name?.lowercase()?.trim()] ?: CREATE
    }
}

/**
 * A parsed request: what to do, and with what.
 *
 * [confidence] is carried but deliberately not used to gate execution. A 1.5B
 * model's self-reported confidence is not reliable enough to make an
 * irreversible decision on, so genuine safety comes from the confirmation flows
 * and from asking when fields are missing — not from a number the model chose.
 * It is logged, so its usefulness can be judged from evidence later.
 */
@Serializable
data class DpsIntent(
    val type: IntentType,
    val parameters: IntentParameters = IntentParameters(),
    val confidence: Float = 0f,

    /** Create, update or cancel. See [IntentAction]. Defaults to [IntentAction.CREATE]. */
    val action: IntentAction = IntentAction.CREATE,
)

/**
 * Field groups each intent needs before a tool can be called.
 *
 * ## Structure
 * A list of alternative groups. An intent is satisfiable when **any one group**
 * is fully present — modelling that "message Abdul" and "message +923001234567"
 * are both complete, by different routes.
 *
 * Expressing it as `Set<Set<IntentField>>` rather than as bespoke per-intent
 * logic keeps the rule declarative and testable, and means the clarification
 * engine can derive its question from the data instead of from a `when` that
 * has to be kept in step.
 */
val IntentType.requiredFields: List<Set<IntentField>>
    get() = when (this) {
        IntentType.REMINDER -> listOf(
            // A time is non-negotiable — a reminder without one cannot exist.
            // The label may come from either field.
            setOf(IntentField.TITLE, IntentField.TIME),
            setOf(IntentField.MESSAGE, IntentField.TIME),
        )

        IntentType.CALENDAR_EVENT -> listOf(
            setOf(IntentField.TITLE, IntentField.DATE),
            setOf(IntentField.TITLE, IntentField.TIME),
        )

        IntentType.NOTIFICATION -> listOf(
            setOf(IntentField.TITLE),
            setOf(IntentField.MESSAGE),
        )

        IntentType.CONTACT_LOOKUP -> listOf(
            setOf(IntentField.PERSON),
            setOf(IntentField.PHONE),
        )

        IntentType.WHATSAPP_MESSAGE -> listOf(
            setOf(IntentField.PERSON, IntentField.MESSAGE),
            setOf(IntentField.PHONE, IntentField.MESSAGE),
        )

        IntentType.EMAIL_MESSAGE -> listOf(
            setOf(IntentField.PERSON, IntentField.MESSAGE),
            setOf(IntentField.EMAIL, IntentField.MESSAGE),
        )

        // A call needs only a target, never a message body — same shape as
        // CONTACT_LOOKUP's own requirement.
        IntentType.CALL_CONTACT -> listOf(
            setOf(IntentField.PERSON),
            setOf(IntentField.PHONE),
        )

        // Day 06. LIST requests (e.g. "mere pending tasks dikhao") need none of
        // this — com.softwaremine.dps.ai.intent.ClarificationEngine special-cases
        // IntentAction.LIST before ever consulting requiredFields, so the groups
        // below only ever gate a CREATE.
        IntentType.TASK -> listOf(
            setOf(IntentField.TITLE),
        )

        IntentType.WORK_LOG -> listOf(
            // "Aaj 10 se 12 baje tak DBPMS par kaam kiya" names the activity;
            // "3 ghante kaam kiya" names only a duration. Either is a complete,
            // honest log entry — see WorkLog's "no fabrication" doc.
            setOf(IntentField.TITLE),
            setOf(IntentField.DURATION),
        )

        IntentType.MEETING_NOTE -> listOf(
            // A title, or failing that the raw note content itself.
            setOf(IntentField.TITLE),
            setOf(IntentField.DESCRIPTION),
        )

        IntentType.ACTION_ITEM -> listOf(
            setOf(IntentField.TITLE),
        )

        // A report never blocks on a missing field — an unstated scope
        // defaults to "day" in ToolSelector rather than being asked about,
        // since showing more real data than requested causes no harm.
        IntentType.REPORT -> emptyList()

        // Conversation needs nothing; it is not routed to a tool.
        IntentType.CONVERSATION -> emptyList()
    }

/**
 * Which tool answers this intent type, for callers that need to know *after*
 * execution which tool ran — [com.softwaremine.dps.ai.memory.ConversationMemoryUpdater]
 * uses this to decide which [ConversationMemory][com.softwaremine.dps.domain.memory.ConversationMemory]
 * slot to update. [com.softwaremine.dps.ai.intent.ToolSelector] already knows
 * this mapping implicitly in the [com.softwaremine.dps.domain.tool.ToolCall] it
 * builds; this is the same fact named explicitly rather than re-derived.
 */
val IntentType.toolId: ToolId?
    get() = when (this) {
        IntentType.REMINDER -> ToolId.REMINDER
        IntentType.CALENDAR_EVENT -> ToolId.CALENDAR
        IntentType.NOTIFICATION -> ToolId.NOTIFICATION
        IntentType.CONTACT_LOOKUP -> ToolId.CONTACTS
        IntentType.WHATSAPP_MESSAGE -> ToolId.WHATSAPP
        IntentType.EMAIL_MESSAGE -> ToolId.GMAIL
        IntentType.CALL_CONTACT -> ToolId.PHONE
        IntentType.TASK -> ToolId.TASK
        IntentType.WORK_LOG -> ToolId.WORK_LOG
        IntentType.MEETING_NOTE -> ToolId.MEETING
        IntentType.ACTION_ITEM -> ToolId.ACTION_ITEM
        IntentType.REPORT -> ToolId.REPORT
        IntentType.CONVERSATION -> null
    }
