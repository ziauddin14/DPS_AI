package com.softwaremine.dps.domain.proactive

import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic overdue-task detection (M4-A).
 *
 * ## Purpose
 * Answers exactly one question — "which tasks are currently eligible for an
 * overdue proactive notification?" — with no side effects. Reading task
 * state, posting a notification and persisting a marker are all the caller's
 * job (see `com.softwaremine.dps.data.android.proactive.ProactiveCheckWorker`);
 * this class only decides.
 *
 * ## Why pure Kotlin, no hidden clock
 * No `android.*` import, no WorkManager import, no notification API, no
 * network, no randomness, and [nowMillis] is always a parameter, never read
 * internally (no `System.currentTimeMillis()` inside this file) — so a JVM
 * test can assert an exact, reproducible answer for an exact, reproducible
 * instant, the same discipline every M2/M3 pure-Kotlin component in this
 * codebase already follows (e.g. `ai.memory.TemporalPhraseResolver`'s own
 * injected `now: () -> Long`).
 *
 * ## The overdue rule
 * A task is eligible when **all** of:
 * 1. [Task.dueAtMillis] is non-null — a task the user never gave a due date
 *    is never guessed into one.
 * 2. `dueAtMillis < nowMillis` — **strictly** less than. A task due exactly
 *    at `now` has not yet passed its deadline; nothing in this codebase's
 *    existing product semantics calls for treating "due this instant" as
 *    already late, so the conservative choice (don't notify one tick early)
 *    is the one made here.
 * 3. [Task.status] is [TaskStatus.PENDING] — the *only* status meaning
 *    "still open." [TaskStatus.COMPLETED] is excluded because the work is
 *    done; [TaskStatus.CANCELLED] is excluded too, deliberately, even though
 *    the brief only named "incomplete" — a cancelled task is not something
 *    the user is still expected to act on, so it is not "still
 *    pending/incomplete" in this product's own sense either.
 * 4. Its id is not already present in [alreadyNotifiedTaskIds] — the
 *    caller's job to have already scoped that set to the current
 *    notification cycle (see [dateKeyFor]/[markerKey]); this class has no
 *    concept of "today" itself.
 */
object ProactiveRuleEvaluator {

    fun overdueTasks(
        tasks: List<Task>,
        nowMillis: Long,
        alreadyNotifiedTaskIds: Set<Int>,
    ): List<Task> = tasks.filter { task ->
        val dueAt = task.dueAtMillis
        dueAt != null &&
            dueAt < nowMillis &&
            task.status == TaskStatus.PENDING &&
            task.id !in alreadyNotifiedTaskIds
    }

    /**
     * The notification cycle a given instant falls into — the local calendar
     * date, so "already notified" naturally resets once a day rather than
     * needing separate expiry bookkeeping. [zone] is a parameter, not
     * `ZoneId.systemDefault()` read internally, for the same determinism
     * reason [nowMillis] is threaded everywhere else in this file.
     */
    fun dateKeyFor(nowMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()

    /**
     * The marker key one overdue notification for [taskId] on [dateKey]
     * is recorded under. Documented format: `task:<taskId>:overdue:<dateKey>`
     * — `dateKey` is an ISO-8601 local date (`toLocalDate().toString()`,
     * e.g. `2026-08-23`), which sorts lexicographically the same as
     * chronologically, a property [pruneMarkersOlderThan] relies on.
     */
    fun markerKey(taskId: Int, dateKey: String): String = "task:$taskId:overdue:$dateKey"

    /**
     * The task id embedded in [marker], but only when [marker] is an
     * overdue marker for exactly [dateKey] — a marker from a different day
     * must not suppress today's notification for the same task.
     */
    fun taskIdIfMarkerMatches(marker: String, dateKey: String): Int? {
        val parts = marker.split(':')
        if (parts.size != 4 || parts[0] != "task" || parts[2] != "overdue" || parts[3] != dateKey) return null
        return parts[1].toIntOrNull()
    }

    /**
     * Drops markers older than [cutoffDateKey], keeping [ProactiveStateStore]'s
     * durable state bounded rather than growing forever — the same
     * housekeeping [com.softwaremine.dps.data.android.reminder.ReminderStore.pruneExpired]
     * already performs for fired reminders. String comparison is sufficient
     * because ISO-8601 dates sort lexicographically in date order.
     */
    fun pruneMarkersOlderThan(markers: Set<String>, cutoffDateKey: String): Set<String> =
        markers.filterTo(mutableSetOf()) { marker ->
            val dateKey = marker.substringAfterLast(':')
            dateKey >= cutoffDateKey
        }

    // -----------------------------------------------------------------
    // Upcoming calendar occurrences (M4-B)
    // -----------------------------------------------------------------

    /**
     * Which occurrences are currently eligible for an "upcoming event"
     * proactive notification. Mirrors [overdueTasks]'s own shape and
     * discipline exactly: pure, no hidden clock, [nowMillis] and
     * [windowMillis] always parameters.
     *
     * ## The eligibility rule
     * An occurrence is eligible when **all** of:
     * 1. `!allDay` — an all-day event has no meaningful "starts soon"
     *    instant the way a timed meeting does (its begin/end are UTC
     *    midnight boundaries of the represented dates, not wall-clock
     *    times); M4-B deliberately excludes it rather than mis-treating it
     *    as a timed event. Not a temporary gap — a documented scope
     *    boundary for this milestone.
     * 2. `title.isNotBlank()` — mirrors [com.softwaremine.dps.data.android.calendar.CalendarWriter.findEvents]'s
     *    own `?: ""` convention for a null title; a notification naming no
     *    event would be confusing rather than useful, so a blank title is
     *    treated as not yet "usable" rather than shown as-is.
     * 3. `beginMillis > nowMillis` — **strictly** greater. An occurrence
     *    starting exactly now is treated as already starting/started, the
     *    same "the boundary belongs to the side that already happened"
     *    choice [overdueTasks] makes for `dueAtMillis == nowMillis`, mirrored
     *    rather than inverted for consistency.
     * 4. `beginMillis <= nowMillis + windowMillis` — **inclusive** at the far
     *    edge, the opposite boundary choice from (3), and deliberate:
     *    [com.softwaremine.dps.data.android.proactive.ProactiveCheckWorker]
     *    runs on an inexact ~30-minute `WorkManager` cadence, so erring
     *    toward catching an occurrence landing exactly on the window's edge
     *    is safer than the "obvious missed event" a strict `<` would
     *    occasionally produce.
     * 5. [eventOccurrenceMarkerKey] for this exact occurrence is not already
     *    present in [alreadyNotifiedMarkers] — computed here, not extracted
     *    from the marker the way [taskIdIfMarkerMatches] works for tasks,
     *    because an occurrence's full identity (source event id **and**
     *    begin instant) is already known up front; there is nothing to
     *    recover from the marker string that the caller doesn't already have.
     *
     * Results are de-duplicated by ([UpcomingEventOccurrence.sourceEventId],
     * [UpcomingEventOccurrence.beginMillis]) — the same occurrence appearing
     * twice in [occurrences] (a defensive guard, not an expected
     * [com.softwaremine.dps.data.android.calendar.CalendarWriter.findUpcomingInstances]
     * outcome) must never produce two notifications for one occurrence — and
     * sorted by [UpcomingEventOccurrence.beginMillis] ascending, the soonest
     * occurrence first, deterministically (re-sorted here rather than
     * trusted, since this function's own contract should not depend on the
     * caller having already sorted its input).
     *
     * @param occurrences [UpcomingEventOccurrence], not
     *   [com.softwaremine.dps.data.android.calendar.CalendarWriter.EventOccurrence]
     *   directly — this file has no dependency on `data.android` at all (see
     *   the class doc's "why pure Kotlin" section); the caller
     *   ([com.softwaremine.dps.data.android.proactive.ProactiveCheckWorker])
     *   maps the Android-facing type into this one field-for-field.
     */
    fun upcomingEvents(
        occurrences: List<UpcomingEventOccurrence>,
        nowMillis: Long,
        windowMillis: Long,
        alreadyNotifiedMarkers: Set<String>,
        zone: ZoneId,
    ): List<UpcomingEventOccurrence> = occurrences.filter { occurrence ->
        !occurrence.allDay &&
            occurrence.title.isNotBlank() &&
            occurrence.beginMillis > nowMillis &&
            occurrence.beginMillis <= nowMillis + windowMillis &&
            eventOccurrenceMarkerKey(occurrence.sourceEventId, occurrence.beginMillis, zone) !in alreadyNotifiedMarkers
    }.distinctBy { it.sourceEventId to it.beginMillis }
        .sortedBy { it.beginMillis }

    /**
     * The marker key one "upcoming event" notification for the occurrence
     * beginning at [beginMillis], of the recurring or one-time event
     * identified by [sourceEventId], is recorded under. Documented format:
     * `event:<sourceEventId>:upcoming:<beginMillis>:<dateKey>`.
     *
     * [beginMillis] — not [dateKey] — is what makes this occurrence-specific:
     * two future occurrences of the same recurring event share
     * [sourceEventId] but never [beginMillis], so "this week's 10am standup"
     * and "next week's 10am standup" always produce different keys even
     * though both might resolve to the same [dateKey] in a pathological
     * same-day re-check. [dateKey] is appended only so this marker's
     * trailing segment stays a valid input to the *existing*
     * [pruneMarkersOlderThan] — which already extracts and compares
     * `substringAfterLast(':')` — needing no change to that function at all.
     */
    fun eventOccurrenceMarkerKey(sourceEventId: Long, beginMillis: Long, zone: ZoneId): String {
        val dateKey = dateKeyFor(beginMillis, zone)
        return "event:$sourceEventId:upcoming:$beginMillis:$dateKey"
    }
}

/**
 * One calendar occurrence, as [ProactiveRuleEvaluator.upcomingEvents] needs
 * it (M4-B) — deliberately a pure domain type, not
 * [com.softwaremine.dps.data.android.calendar.CalendarWriter.EventOccurrence]
 * reused directly: that type lives in `data.android.calendar`, and this
 * package must depend on nothing there, the same one-way boundary
 * [com.softwaremine.dps.domain.productivity.Task] already keeps from
 * `data.android.productivity.AndroidTaskStore`. The mapping from the
 * Android-facing type to this one is a trivial field-for-field copy, done by
 * [com.softwaremine.dps.data.android.proactive.ProactiveCheckWorker] — the
 * one layer that is already allowed to know about both.
 *
 * [sourceEventId] alone is not a unique occurrence identity — see
 * [ProactiveRuleEvaluator.eventOccurrenceMarkerKey]'s own doc for why the
 * pair with [beginMillis] is what this milestone actually keys on.
 */
data class UpcomingEventOccurrence(
    val sourceEventId: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val title: String,
    val allDay: Boolean,
)
