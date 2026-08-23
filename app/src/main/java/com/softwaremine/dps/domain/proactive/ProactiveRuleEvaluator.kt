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
}
