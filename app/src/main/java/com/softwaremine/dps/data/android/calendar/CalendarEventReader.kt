package com.softwaremine.dps.data.android.calendar

/**
 * Reads back one calendar event's verification-relevant fields (M7).
 *
 * ## Why this is a separate interface from [CalendarWriter] itself
 * [CalendarWriter] is a concrete class with a direct `Context`/
 * `CalendarContract` dependency — it cannot be constructed on the JVM. M7's
 * [com.softwaremine.dps.data.android.secretary.ExecutionVerifier] needs to
 * be JVM-testable against a deterministic fake, the same reason
 * [com.softwaremine.dps.domain.productivity.TaskRepository] already exists
 * as an interface alongside its Android-backed
 * [com.softwaremine.dps.data.android.productivity.AndroidTaskStore]
 * implementation. This interface is that same split, applied to the one
 * read [ExecutionVerifier] needs from the calendar side — nothing more of
 * [CalendarWriter] is exposed through it.
 */
interface CalendarEventReader {

    /** One event's verification-relevant fields, as [CalendarWriter.readEventSnapshot] reads them. */
    data class EventSnapshot(val title: String, val startMillis: Long, val endMillis: Long)

    /** Outcome of reading one event by id for verification. */
    sealed interface EventSnapshotOutcome {
        data class Found(val snapshot: EventSnapshot) : EventSnapshotOutcome
        data object NotFound : EventSnapshotOutcome
        data class Failed(val reason: String) : EventSnapshotOutcome
    }

    /**
     * Reads [eventId]'s title, start and end, for comparison against what a
     * `create_event` call was asked to produce.
     *
     * Deliberately its own outcome type rather than reusing [CalendarWriter.readEvent]'s
     * older `null`-on-either-not-found-or-error contract — see
     * [CalendarWriter.readEventSnapshot]'s own doc for why that distinction
     * matters for M7 specifically.
     */
    fun readEventSnapshot(eventId: Long): EventSnapshotOutcome
}
