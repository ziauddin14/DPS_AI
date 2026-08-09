package com.softwaremine.dps.domain.productivity

import kotlinx.serialization.Serializable

/**
 * A record of a meeting the user reported.
 *
 * ## Why [notes] carries the free-form content
 * "Aaj Hassan bhai ke saath DBPMS meeting hui. Unhon ne kaha Friday tak
 * prototype complete karna hai." names a decision and a deadline inside
 * ordinary prose. A 1.5B on-device classifier extracting that reliably into
 * separate structured fields would routinely be wrong, and a wrong decision
 * or deadline is worse than an unstructured one — the Day 06 brief forbids
 * inventing either. So [notes] keeps the user's own words verbatim, always
 * faithful; [decisions] is a separate, optional field populated only when a
 * decision is explicit enough to state on its own (e.g. the user says it as
 * a distinct follow-up step in a plan, not extracted from [notes] by
 * guessing).
 *
 * ## Participants
 * A plain list because the model may report zero, one or several names in
 * one message ("Hassan bhai, Ali"). Never invented when absent.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
data class MeetingNote(
    val id: Int,
    val title: String,
    /** The day of the meeting, epoch millis. */
    val dateMillis: Long,
    val participants: List<String> = emptyList(),
    val notes: String? = null,
    /** Only ever set from an explicit statement — never inferred from [notes]. */
    val decisions: String? = null,
    val createdAtMillis: Long,
)

interface MeetingNoteRepository {
    fun all(): List<MeetingNote>
    fun find(id: Int): MeetingNote?
    fun save(note: MeetingNote): MeetingNote
    fun delete(id: Int): Boolean
    fun nextId(): Int
}
