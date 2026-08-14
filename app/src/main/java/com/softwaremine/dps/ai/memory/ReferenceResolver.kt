package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.memory.ConversationMemory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fills in what a follow-up message leaves unsaid, using
 * [ConversationMemory] instead of asking the user to repeat themselves.
 *
 * ## What this is not
 * A general pronoun resolver. It matches a fixed, tested vocabulary of Roman
 * Urdu and English reference phrases — "usko", "uski", "us reminder", "kal
 * wali meeting" and their listed variants — against memory. Anything outside
 * that vocabulary is left for the model's own classification, exactly as
 * before. This mirrors [com.softwaremine.dps.domain.contact.ContactResolver]'s
 * phone-matching: a real, bounded capability with a stated limit, not a claim
 * of full natural-language understanding.
 *
 * ## Why it runs after action detection
 * Whether "us reminder"/"the reminder" should resolve to
 * [ConversationMemory.lastReminder] depends on whether this message is a
 * CREATE or an UPDATE/CANCEL — "set a reminder" must never resolve to the
 * *previous* reminder just because the word appears. [resolve] therefore takes
 * the intent's already-detected [IntentAction] rather than making its own
 * guess about it.
 *
 * ## Relative time offsets
 * "30 minute pehle kar do" names a change to an existing time, not a new
 * absolute one — the model cannot resolve it because it does not know the
 * target reminder's current time. [resolve] finds the delta (amount, unit,
 * direction) in the raw text, applies it to
 * [com.softwaremine.dps.domain.memory.ReminderMemory.triggerAtMillis], and
 * writes the result back as an explicit `date`/`time` pair — the same shape
 * [com.softwaremine.dps.ai.intent.ToolSelector] already knows how to combine,
 * so no change to that combination logic is needed.
 *
 * ## Dependencies
 * Domain intent and memory types, `java.time`. No Android, no model call.
 */
class ReferenceResolver(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Returns [intent] with gaps filled from [memory], or [intent] unchanged
     * when [rawText] carries none of the reference cues this class knows.
     */
    fun resolve(rawText: String, intent: DpsIntent, memory: ConversationMemory): DpsIntent {
        val normalized = rawText.lowercase().trim()

        var parameters = intent.parameters

        if (parameters.person == null && PERSON_CUES.any { normalized.contains(it) }) {
            val referencedPerson = memory.lastContact?.displayName ?: memory.lastReferencedPerson
            if (referencedPerson != null) {
                parameters = parameters.copy(person = referencedPerson)
            }
        }

        val targetsExisting = intent.action != IntentAction.CREATE

        if (parameters.targetId == null) {
            when (intent.type) {
                IntentType.REMINDER -> {
                    val referencesReminder = REMINDER_CUES.any { normalized.contains(it) } ||
                        (targetsExisting && parameters.title == null && parameters.message == null)
                    if (referencesReminder) {
                        memory.lastReminder?.let { parameters = parameters.copy(targetId = it.id.toString()) }
                    }
                }

                IntentType.CALENDAR_EVENT -> {
                    // The model classifying this as CALENDAR_EVENT is not by
                    // itself evidence of what the user's words actually
                    // named — a cue for a different remembered entity
                    // (reminder, task) means this must stay unresolved
                    // rather than guess against the last calendar event.
                    val hasConflictingCue = REMINDER_CUES.any { normalized.contains(it) } ||
                        TASK_CUES.any { normalized.contains(it) }
                    val referencesEvent = !hasConflictingCue &&
                        (CALENDAR_CUES.any { normalized.contains(it) } || (targetsExisting && parameters.title == null))
                    if (referencesEvent) {
                        memory.lastCalendarEvent?.let { parameters = parameters.copy(targetId = it.id.toString()) }
                    }
                }

                // Day 06. Unlike REMINDER/CALENDAR_EVENT, a title also
                // addresses a task directly ("DBPMS wala task complete kar
                // do") — see AndroidTaskTool. So a cue or a bare "us
                // task"-shaped follow-up only resolves against memory when no
                // title was given; a stated title always wins as the address.
                IntentType.TASK -> {
                    val referencesTask = parameters.title == null &&
                        (TASK_CUES.any { normalized.contains(it) } || targetsExisting)
                    if (referencesTask) {
                        memory.lastTask?.let { parameters = parameters.copy(targetId = it.id.toString()) }
                    }
                }

                else -> Unit
            }
        }

        if (intent.type == IntentType.REMINDER &&
            parameters.targetId == memory.lastReminder?.id?.toString() &&
            memory.lastReminder != null
        ) {
            findRelativeOffsetMillis(normalized)?.let { offsetMillis ->
                val newTrigger = memory.lastReminder.triggerAtMillis + offsetMillis
                val zoned = Instant.ofEpochMilli(newTrigger).atZone(zone)
                parameters = parameters.copy(
                    date = zoned.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    time = zoned.format(DateTimeFormatter.ofPattern("HH:mm")),
                )
            }
        }

        return if (parameters == intent.parameters) intent else intent.copy(parameters = parameters)
    }

    /**
     * Finds a spoken time delta — "30 minute pehle", "2 hours later" — and
     * returns it in milliseconds, negative for "earlier/pehle", positive for
     * "later/baad". `null` when [normalized] names no such delta.
     */
    private fun findRelativeOffsetMillis(normalized: String): Long? {
        val match = OFFSET_PATTERN.find(normalized) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        val direction = match.groupValues[3]

        val unitMillis = if (unit in HOUR_UNITS) MILLIS_PER_HOUR else MILLIS_PER_MINUTE
        val sign = if (direction in EARLIER_WORDS) -1 else 1

        return sign * amount * unitMillis
    }

    private companion object {
        val PERSON_CUES = listOf(
            "usko", "uska", "uski", "usay", "usse",
            "unhe", "unhein", "unko", "unka", "unki",
            "iska", "iski", "isay",
        )

        val REMINDER_CUES = listOf(
            "us reminder", "uss reminder", "woh reminder", "wo reminder",
            "is reminder", "reminder ko", "that reminder", "the reminder",
            "uska reminder", "uski reminder", "iska reminder", "iski reminder",
        )

        val CALENDAR_CUES = listOf(
            "us meeting", "uss meeting", "woh meeting", "wo meeting", "is meeting",
            "meeting ko", "that meeting", "the meeting",
            "us event", "that event", "the event",
            "kal wali meeting", "aaj wali meeting", "wo wali meeting", "woh wali meeting",
            "uska meeting", "uski meeting", "iska meeting", "iski meeting",
            "uska event", "uski event",
        )

        val TASK_CUES = listOf(
            "us task", "uss task", "woh task", "wo task", "is task",
            "task ko", "that task", "the task", "ye task", "yeh task",
            "uska task", "uski task", "iska task", "iski task",
        )

        val HOUR_UNITS = setOf("hour", "hours", "ghanta", "ghante", "ghanton")
        val EARLIER_WORDS = setOf("pehle", "pahle", "earlier", "before")

        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_HOUR = 3_600_000L

        /** `(amount)(unit)(direction)`, tolerant of the space before "pehle"/"baad" etc. */
        val OFFSET_PATTERN = Regex(
            "(\\d+)\\s*(minute|minutes|min|hour|hours|ghanta|ghante|ghanton)\\s*" +
                "(pehle|pahle|earlier|before|baad|bad|later|after)",
        )
    }
}
