package com.softwaremine.dps.domain.productivity

import kotlinx.serialization.Serializable

/**
 * A lightweight record of work done on a given day.
 *
 * ## Why duration is never derived from thin air
 * The Day 06 brief is explicit: "Do not fabricate duration." If the user only
 * says "3 ghante kaam kiya", [durationMinutes] is set and [startAtMillis]/
 * [endAtMillis] stay `null` — this class does not invent when that work
 * started. If only the activity is known, none of the three are set.
 * [com.softwaremine.dps.domain.productivity.report.ReportGenerator] sums
 * whichever of [durationMinutes] or the start/end span is actually present
 * per entry; it never assumes one from the other.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
data class WorkLog(
    val id: Int,
    /** The calendar day this entry is for, normalized to local midnight, epoch millis. */
    val dateMillis: Long,
    val startAtMillis: Long? = null,
    val endAtMillis: Long? = null,
    /** Set only when the user stated a duration directly, e.g. "3 ghante". */
    val durationMinutes: Long? = null,
    val activity: String,
    val category: String? = null,
    val notes: String? = null,
    val createdAtMillis: Long,
) {
    /**
     * Minutes worked, from whichever source is available: an explicit
     * duration first, else the start/end span, else `null`.
     */
    val effectiveMinutes: Long?
        get() = durationMinutes ?: run {
            val start = startAtMillis
            val end = endAtMillis
            if (start != null && end != null && end > start) (end - start) / 60_000L else null
        }
}

interface WorkLogRepository {
    fun all(): List<WorkLog>
    fun find(id: Int): WorkLog?
    fun save(entry: WorkLog): WorkLog
    fun delete(id: Int): Boolean
    fun nextId(): Int
}
