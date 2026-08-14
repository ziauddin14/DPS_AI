package com.softwaremine.dps.ai.memory

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns the user's own words about *when* into an absolute date/time —
 * deterministically, never by asking the model (Day 08-E).
 *
 * ## Why this exists
 * Day 08-D asked the classification model to resolve "kal shaam 7 baje"
 * against an injected `Now:` into `YYYY-MM-DD`/`HH:MM` itself. A controlled
 * fixed-clock retest (identical `Now:`, only its prompt position changed)
 * proved that a 1.5B model doing that arithmetic is unreliable regardless of
 * where `Now:` sits — it sometimes substitutes `Now:`'s own value for the
 * time the user actually stated, once producing a structurally successful
 * but factually wrong reminder. That is the one failure this class exists to
 * make structurally impossible: it is the *only* path by which a request
 * reaches [com.softwaremine.dps.ai.intent.ToolSelector] with a `date`/`time`
 * — see [com.softwaremine.dps.ai.intent.IntentJsonParser]'s doc for why the
 * model's own `date`/`time` keys, if it emits them anyway, are never read.
 *
 * ## What this is not
 * A general natural-language date parser. Like [ReferenceResolver]'s
 * pronoun vocabulary and [ActionDetector]'s keyword list, this matches a
 * fixed, tested, documented set of Roman Urdu and English temporal cues.
 * Anything outside it — "agle hafte", a malformed hour, a bare "X baje"
 * with no AM/PM cue — resolves to nothing rather than a guess, which is by
 * design: [com.softwaremine.dps.ai.intent.ClarificationEngine] already asks
 * a sensible follow-up question whenever `date`/`time` come back missing,
 * so failing closed here costs nothing and invents nothing.
 *
 * ## Why a bare "X baje" is never resolved
 * Spoken Roman Urdu routinely omits AM/PM — "4 baje" alone is genuinely
 * ambiguous between four in the morning and four in the afternoon, with no
 * reliable convention to pick one. Guessing either would be exactly the
 * "structurally successful but factually wrong" failure this class exists
 * to prevent. A period word (subah/dopahar/shaam/raat) is what disambiguates
 * it; without one, [resolve] leaves the time unset.
 *
 * ## Contract with the rest of the pipeline
 * Output is the exact same shape [com.softwaremine.dps.ai.intent.ToolSelector]
 * already accepts from the model today — ISO `date` (`YYYY-MM-DD`) and 24-hour
 * `time` (`HH:mm`) — so nothing downstream of
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] changes at all.
 *
 * ## Dependencies
 * `java.time` only. No Android, no model call — like [ActionDetector], this
 * never costs a token.
 */
class TemporalPhraseResolver(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> LocalDateTime = { LocalDateTime.now(zone) },
) {

    /** What [resolve] found. Either half may be `null` — see the class doc on failing closed. */
    data class Resolution(val date: String?, val time: String?)

    /** Nothing recognised. A named constant so callers can compare against it directly. */
    val unresolved = Resolution(date = null, time = null)

    /**
     * Resolves [rawWhen] — the model's verbatim quote of the user's words
     * about when — against the real clock at [now].
     *
     * Never throws, never guesses. A phrase this class does not recognise,
     * or recognises only partially (a day with no time, as in "kal" alone),
     * returns whatever part *was* confidently resolved and leaves the rest
     * `null` — exactly the shape [com.softwaremine.dps.ai.intent.ClarificationEngine]
     * already knows how to turn into a follow-up question.
     */
    fun resolve(rawWhen: String?): Resolution {
        val normalized = rawWhen?.lowercase()?.trim()?.let(::stripParticles) ?: return unresolved
        if (normalized.isEmpty()) return unresolved

        val reference = now()
        val date = resolveDate(normalized, reference)
        val time = resolveTime(normalized)

        return Resolution(date = date, time = time)
    }

    /** Strips trailing Urdu grammatical particles ("20 august ko" → "20 august"). */
    private fun stripParticles(text: String): String {
        var result = text
        var changed = true
        while (changed) {
            changed = false
            for (particle in TRAILING_PARTICLES) {
                if (result.endsWith(" $particle")) {
                    result = result.removeSuffix(" $particle").trim()
                    changed = true
                }
            }
        }
        return result
    }

    // -----------------------------------------------------------------
    // Date
    // -----------------------------------------------------------------

    private fun resolveDate(normalized: String, reference: LocalDateTime): String? {
        DAY_WORDS.forEach { (word, offset) ->
            if (containsWord(normalized, word)) {
                return reference.toLocalDate().plusDays(offset)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
        }

        parseAbsoluteDate(normalized, reference.toLocalDate())?.let {
            return it.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }

        return null
    }

    /**
     * `"20 august"` / `"august 20"` with no year stated.
     *
     * Mirrors [com.softwaremine.dps.ai.intent.ToolSelector]'s existing
     * "next occurrence" convention for a bare time: if the day/month named
     * has already passed this year, it means next year, not a year-old date
     * nobody would ask to be reminded about.
     */
    private fun parseAbsoluteDate(normalized: String, today: LocalDate): LocalDate? {
        val match = ABSOLUTE_DATE_PATTERN.find(normalized) ?: return null

        val dayGroup = match.groups["day1"]?.value ?: match.groups["day2"]?.value
        val monthGroup = match.groups["month1"]?.value ?: match.groups["month2"]?.value
        val day = dayGroup?.toIntOrNull() ?: return null
        val monthNumber = monthGroup?.let(::monthNumberOf) ?: return null

        val candidate = runCatching { LocalDate.of(today.year, monthNumber, day) }.getOrNull() ?: return null
        return if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
    }

    private fun monthNumberOf(name: String): Int? = MONTHS[name]

    // -----------------------------------------------------------------
    // Time
    // -----------------------------------------------------------------

    private fun resolveTime(normalized: String): String? {
        // An unambiguous 24-hour value stated directly — "16:00", "4:30".
        // Never matched when immediately followed by an am/pm marker: a
        // colon-time paired with am/pm is a 12-hour value this vocabulary
        // does not convert, and silently keeping the digits while dropping
        // "pm" would resolve "7:30 pm" as 07:30 — exactly the
        // structurally-successful-but-factually-wrong failure this whole
        // class exists to prevent (Day 08-E audit finding). Falling through
        // to fail closed is correct; guessing which am/pm was meant is not.
        val colonMatch = COLON_TIME_PATTERN.find(normalized)
        if (colonMatch != null && !isFollowedByAmPm(normalized, colonMatch.range.last + 1)) {
            val hour = colonMatch.groupValues[1].toInt()
            val minute = colonMatch.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) {
                return "%02d:%02d".format(hour, minute)
            }
        }

        val bajeMatch = BAJE_PATTERN.find(normalized) ?: return null
        val hour = bajeMatch.groupValues[1].toIntOrNull() ?: return null
        if (hour !in 1..12) return null

        val period = PERIOD_WORDS.entries.firstOrNull { (word, _) -> containsWord(normalized, word) }
            ?: return null // Bare "X baje" with no period word — genuinely ambiguous. Fail closed.

        val resolvedHour = period.value(hour)
        return "%02d:00".format(resolvedHour)
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(text)

    /** `true` when the text right after a colon-time match starts with "am"/"pm" — see [resolveTime]. */
    private fun isFollowedByAmPm(text: String, afterIndex: Int): Boolean {
        val rest = text.substring(afterIndex).trimStart()
        return rest.startsWith("am") || rest.startsWith("pm")
    }

    private companion object {
        /** Day offsets from today. "Kal"/"parso" read forward — see the class doc. */
        val DAY_WORDS = listOf(
            "aaj" to 0L,
            "today" to 0L,
            "kal" to 1L,
            "tomorrow" to 1L,
            "parso" to 2L,
            "parson" to 2L,
        )

        val TRAILING_PARTICLES = listOf("ko", "ke", "ki")

        /** `subah`/`dopahar`/`shaam`/`raat` + a 1-12 hour → the 24-hour equivalent. */
        val PERIOD_WORDS: Map<String, (Int) -> Int> = linkedMapOf(
            "subah" to { hour -> if (hour == 12) 0 else hour },
            "dopahar" to { hour -> if (hour == 12) 12 else hour + 12 },
            "dopher" to { hour -> if (hour == 12) 12 else hour + 12 },
            "shaam" to { hour -> if (hour == 12) 12 else hour + 12 },
            "raat" to { hour -> if (hour == 12) 0 else hour + 12 },
        )

        val BAJE_PATTERN = Regex("(\\d{1,2})\\s*baje")
        val COLON_TIME_PATTERN = Regex("\\b(\\d{1,2}):(\\d{2})\\b")

        /** `"20 august"` or `"august 20"`. Year is never stated — see [parseAbsoluteDate]. */
        val ABSOLUTE_DATE_PATTERN = Regex(
            "(?:(?<day1>\\d{1,2})\\s+(?<month1>[a-z]+)|(?<month2>[a-z]+)\\s+(?<day2>\\d{1,2}))",
        )

        // Full month names only — the documented vocabulary is "DD Month"
        // ("20 August"), not its abbreviations; adding "aug"/"jan" etc. is
        // an easy, deliberate follow-up, not something to slip in here.
        val MONTHS: Map<String, Int> = (1..12).associate { month ->
            java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() to month
        }
    }
}
