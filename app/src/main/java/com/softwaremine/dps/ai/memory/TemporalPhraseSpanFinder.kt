package com.softwaremine.dps.ai.memory

/**
 * Locates temporal phrases — and only their *positions* in the user's raw
 * message — deterministically. Resolving what a phrase means is
 * [TemporalPhraseResolver]'s job; this class exists solely to answer "where,
 * if anywhere, in what the user actually typed does a genuine temporal
 * phrase occur" (Day 08-E multi-step follow-up).
 *
 * ## Why this exists
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator.handlePlan]'s
 * original multi-step grounding checked a step's `raw_when` against the
 * *whole* compound message — proven, by a JVM regression test, to accept a
 * hallucinated `raw_when` on one step merely because a *different* step's
 * genuinely-said phrase happens to appear somewhere in the same message.
 * This class is what makes real per-step attribution possible: it finds the
 * actual, genuine temporal phrases in the message, in order, so each step
 * can be matched against one specific occurrence rather than the message
 * as a whole — see [TemporalStepAttributor] for how that matching happens.
 *
 * ## Why this delegates to TemporalPhraseResolver rather than duplicating vocabulary
 * The question "is this window of words a real temporal phrase" is exactly
 * what [TemporalPhraseResolver.resolve] already answers correctly and
 * safely — bare-hour refusal, the am/pm fail-closed fix, the documented
 * vocabulary. Rather than reimplementing that recognition logic here and
 * risking the two classes drifting apart, this asks the resolver whether a
 * given window is genuinely resolvable, keeping the vocabulary defined in
 * exactly one place.
 *
 * ## Matching strategy — grow while compatible, then trim to necessity
 * An earlier version of this class tried to *grow* a window outward from a
 * starting position, using a word-count gap budget plus a small conjunction
 * blocklist to decide when to stop. A real-device message —
 * `"Buy milk ka task bana do kal shaam 7 baje"` — proved that approach
 * brittle: scanning from `"task"`, the window `"task bana do kal"` "resolves
 * something" (it contains `"kal"`) within the tolerated gap, with no
 * conjunction in between to block it, producing a wrong, fragmented span.
 *
 * The rule that replaced it needs no word lists at all: resolve the entire
 * remainder from a starting position in one call, and trim from both ends
 * while the resolution stays identical. That version had its own bug —
 * [TemporalPhraseResolver.resolveDate] checks its day-word vocabulary
 * (`kal`/`aaj`/...) *before* falling back to an absolute date, found
 * anywhere in the string regardless of position. A message like
 * `"20 August ko ... aur kal shaam 7 baje ..."` contains both an absolute
 * date and a later, unrelated day word; resolving the whole remainder in
 * one call always returns the day word's date, so trimming collapsed onto
 * only the `"kal ..."` phrase and silently discarded `"20 August"` — a real
 * regression, not merely a missed case.
 *
 * The fix: never resolve the whole remainder in one call when it might
 * contain more than one genuine phrase. Instead, **grow** the window one
 * word at a time from the starting position, tracking the resolution as it
 * goes, and stop the moment growing would *change* an already-established
 * date or time to a genuinely different value — evidence the window has
 * crossed into a second, independent phrase. Growing past a word that
 * leaves an still-open field unset (`null`), or that fills a still-open
 * field for the first time, is not a conflict — it's the normal way a
 * single phrase is built up word by word (`"kal shaam"` then later
 * `"7 baje"` fills in the time that `"kal shaam"` alone left open). Once
 * growth stops (at a real conflict, or at the end of the message), the
 * existing trim rule runs exactly as before, but bounded by that growth
 * instead of the whole remainder:
 * 1. **Trim from the back**: drop the last word as long as the resolution
 *    stays *identical* to the target.
 * 2. **Trim from the front**: drop the first word as long as the resolution
 *    stays *identical* to the target.
 *
 * This is a strict refinement of the previous rule, not a different one: in
 * every message where growth never hits a real conflict, the target it
 * finds is exactly the same value the old whole-remainder resolve would
 * have found, so it produces the same result — the only messages it treats
 * differently are the ones where a *later*, independent phrase was silently
 * hijacking an earlier one's resolution.
 *
 * What remains after trimming is provably minimal — every remaining word is
 * necessary for the resolution, and every removed word was provably not.
 * Trimming only ever removes from the two ends, never the middle, so a
 * filler word sitting *between* the two halves of one genuine phrase
 * ("kal shaam **ko** 7 baje") is automatically preserved with no
 * special-casing, while unrelated leading or trailing words fall away
 * because removing them provably changes nothing about what resolves.
 *
 * ## What this is not
 * Not a step-attribution strategy. This class only ever answers "where are
 * the genuine temporal phrases", never "which step do they belong to" —
 * see [TemporalStepAttributor] for that.
 *
 * ## Dependencies
 * [TemporalPhraseResolver] only. No Android, no model call.
 */
class TemporalPhraseSpanFinder(
    private val resolver: TemporalPhraseResolver = TemporalPhraseResolver(),
) {

    /** One genuine temporal phrase found in a message, as its literal words. */
    data class TemporalSpan(val text: String)

    /**
     * Finds every non-overlapping, genuine temporal phrase in [message], in
     * the order they occur. An empty list means the message contains no
     * temporal phrase [TemporalPhraseResolver] recognises at all.
     */
    fun findSpans(message: String): List<TemporalSpan> {
        val words = WORD_PATTERN.findAll(message).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()

        val spans = mutableListOf<TemporalSpan>()
        var index = 0
        while (index < words.size) {
            val bounds = minimalResolvableBounds(words, index)
            if (bounds == null) {
                index += 1
                continue
            }
            val (start, end) = bounds
            spans += TemporalSpan(words.subList(start, end).joinToString(" ").lowercase())
            index = end
        }
        return spans
    }

    /**
     * The `[start, end)` bounds of the minimal window, beginning no earlier
     * than [start], whose resolution matches the furthest *conflict-free*
     * growth reachable from [start] — or `null` if nothing there resolves
     * at all. See the class doc for why growing to the first conflict, then
     * trimming to necessity, is the right rule.
     */
    private fun minimalResolvableBounds(words: List<String>, start: Int): Pair<Int, Int>? {
        var target = resolver.unresolved
        var windowEnd = start
        for (end in (start + 1)..words.size) {
            val candidate = resolver.resolve(words.subList(start, end).joinToString(" "))
            if (!isCompatible(target, candidate)) break
            target = merge(target, candidate)
            windowEnd = end
        }
        if (target.date == null && target.time == null) return null

        var end = windowEnd
        while (end > start + 1 && resolver.resolve(words.subList(start, end - 1).joinToString(" ")) == target) {
            end -= 1
        }

        var trimmedStart = start
        while (
            trimmedStart < end - 1 &&
            resolver.resolve(words.subList(trimmedStart + 1, end).joinToString(" ")) == target
        ) {
            trimmedStart += 1
        }

        return trimmedStart to end
    }

    /**
     * `true` when [candidate] does not contradict [target] — every field
     * both have set agrees. A field either side leaves `null` never
     * conflicts, since `null` means "not yet resolved from this window",
     * not "resolved to nothing".
     */
    private fun isCompatible(
        target: TemporalPhraseResolver.Resolution,
        candidate: TemporalPhraseResolver.Resolution,
    ): Boolean =
        (target.date == null || candidate.date == null || target.date == candidate.date) &&
            (target.time == null || candidate.time == null || target.time == candidate.time)

    /** Combines two known-[isCompatible] resolutions, preferring whichever side has a value. */
    private fun merge(
        target: TemporalPhraseResolver.Resolution,
        candidate: TemporalPhraseResolver.Resolution,
    ): TemporalPhraseResolver.Resolution =
        TemporalPhraseResolver.Resolution(
            date = candidate.date ?: target.date,
            time = candidate.time ?: target.time,
        )

    private companion object {
        /**
         * Includes `:` so a colon-time like "16:00" survives tokenization as
         * one word — a plain `[A-Za-z0-9]+` split would drop the colon
         * entirely, turning it into "16" and "00" and making it permanently
         * unmatchable against [TemporalPhraseResolver]'s own colon pattern.
         */
        val WORD_PATTERN = Regex("[A-Za-z0-9:]+")
    }
}
