package com.softwaremine.dps.data.android.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.softwaremine.dps.core.logging.DpsLogger

/**
 * Reads and writes the Android Calendar Provider.
 *
 * ## Purpose
 * The only class that talks to `CalendarContract`. Isolating it keeps provider
 * column names, the writable-calendar search and the insert contract in one
 * place, so a future "list events" or "update event" tool reuses this rather
 * than growing a second, subtly different copy.
 *
 * ## APIs used â€” verified against official documentation
 * | API | Notes |
 * |---|---|
 * | `CalendarContract.Events.CONTENT_URI` | insert target |
 * | `CALENDAR_ID`, `DTSTART`, `DTEND`, `TITLE`, `EVENT_TIMEZONE` | **all required** for a non-recurring insert |
 * | `DESCRIPTION`, `EVENT_LOCATION`, `ALL_DAY` | optional |
 * | `ContentResolver.insert(uri, values)` | returns a Uri whose last path segment is the event id |
 * | `CalendarContract.Calendars` | source of a writable calendar id |
 * | `ContentUris.withAppendedId(Events.CONTENT_URI, id)` | addresses a single event for update/delete |
 * | `ContentResolver.update(uri, values, null, null)` | returns rows affected; `0` means no such event |
 * | `ContentResolver.delete(uri, null, null)` | same return-value contract; an application delete sets `_deleted=1` |
 *
 * Documentation is explicit that non-recurring events must supply `DTEND` and
 * that recurring events use `DURATION` with `RRULE` instead. Only non-recurring
 * inserts are implemented, so `DTEND` is always required here. TITLE, DTSTART
 * and DTEND are documented as writable by an application for update.
 *
 * ## Choosing a calendar
 * `CALENDAR_ID` is required and there is no "default calendar" API. A device
 * commonly has several â€” a Google account, a local one, read-only subscribed
 * holiday feeds. Writing to a read-only calendar fails in ways that surface
 * long after the insert appears to succeed.
 *
 * Calendars are therefore filtered by `CALENDAR_ACCESS_LEVEL`, preferring
 * `IS_PRIMARY`, and the absence of any writable calendar is reported as a
 * distinct outcome rather than as a generic failure â€” the user needs to know
 * they have no calendar to write to, not that "something went wrong".
 *
 * ## Dependencies
 * `android.provider.CalendarContract`, `android.content`. Android layer only.
 */
class CalendarWriter(
    private val context: Context,
    private val logger: DpsLogger,
) {

    /** Outcome of looking for somewhere to write. */
    sealed interface CalendarTarget {
        data class Found(val calendarId: Long, val displayName: String) : CalendarTarget

        /** The device exposes no Calendar Provider at all. */
        data object NoProvider : CalendarTarget

        /** A provider exists but no calendar accepts writes. */
        data object NoWritableCalendar : CalendarTarget

        data class Failed(val reason: String) : CalendarTarget
    }

    /** The two columns [readEvent] needs to preserve duration across a reschedule. */
    data class EventTimes(val startMillis: Long, val endMillis: Long)

    /** Outcome of an insert. */
    sealed interface InsertOutcome {
        data class Created(val eventId: Long) : InsertOutcome
        data class Failed(val reason: String) : InsertOutcome
        data object NoProvider : InsertOutcome
    }

    /**
     * Outcome of an update or delete.
     *
     * ## Why `NotFound` is distinct from `Failed`
     * `ContentResolver.update`/`.delete` never throw for a missing row — per
     * the documented contract, they return the count of rows affected, `0`
     * when nothing matched (docs: "the documentation does not explicitly
     * state that exceptions are thrown for missing events; instead, callers
     * should check if the return value is greater than 0"). Collapsing that
     * into `Failed` would tell the user "something went wrong" about an event
     * that simply no longer exists — stale [com.softwaremine.dps.domain.memory.CalendarEventMemory],
     * or one already removed from the Calendar app directly.
     */
    sealed interface MutationOutcome {
        data object Updated : MutationOutcome
        data object Deleted : MutationOutcome
        data object NotFound : MutationOutcome
        data class Failed(val reason: String) : MutationOutcome
        data object NoProvider : MutationOutcome
    }

    /**
     * Finds a calendar that accepts writes, preferring the primary one.
     *
     * `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR` is the documented
     * threshold for being able to add events. Subscribed holiday calendars sit
     * below it and are correctly excluded.
     */
    fun findWritableCalendar(): CalendarTarget {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                // Primary first, so a device with several accounts lands on the
                // one the user thinks of as "my calendar".
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            ) ?: return CalendarTarget.NoProvider

            cursor.use {
                if (!it.moveToFirst()) return CalendarTarget.NoWritableCalendar

                val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                val name = it.getString(
                    it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                ) ?: "Calendar"

                logger.d(TAG, "Writable calendar: id=$id name=$name")
                CalendarTarget.Found(calendarId = id, displayName = name)
            }
        } catch (security: SecurityException) {
            // Should not happen â€” the executor gates on permission first â€” but
            // a provider can still refuse, and this must not propagate.
            logger.w(TAG, "Calendar query denied", security)
            CalendarTarget.Failed("Calendar access was denied.")
        } catch (throwable: Throwable) {
            logger.e(TAG, "Calendar query failed", throwable)
            CalendarTarget.Failed(throwable.message ?: "Calendar query failed.")
        }
    }

    /**
     * Inserts a non-recurring event.
     *
     * @param startMillis UTC epoch millis. Required.
     * @param endMillis UTC epoch millis. Required â€” documentation mandates
     *   `DTEND` for non-recurring events.
     * @param timezone IANA zone id stored as `EVENT_TIMEZONE`. Required.
     */
    fun insertEvent(
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        timezone: String,
    ): InsertOutcome {
        val values = ContentValues().apply {
            // --- required by the provider ---
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezone)

            // --- optional ---
            description?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return InsertOutcome.Failed("The calendar provider rejected the event.")

            // Documented: the returned Uri's last path segment is the event id.
            // ContentUris.parseId is used rather than string-splitting because
            // it is the API contract rather than an assumption about format.
            val eventId = ContentUris.parseId(uri)
            logger.i(TAG, "Created calendar event id=$eventId")
            InsertOutcome.Created(eventId)
        } catch (security: SecurityException) {
            logger.w(TAG, "Calendar insert denied", security)
            InsertOutcome.Failed("Calendar access was denied.")
        } catch (illegal: IllegalArgumentException) {
            // Raised when the provider is absent, so the Uri resolves to nothing.
            logger.w(TAG, "No calendar provider", illegal)
            InsertOutcome.NoProvider
        } catch (throwable: Throwable) {
            logger.e(TAG, "Calendar insert failed", throwable)
            InsertOutcome.Failed(throwable.message ?: "Could not create the event.")
        }
    }

    /**
     * Reads an event's current start/end, so a reschedule that only names a
     * new start time can preserve the event's length rather than leaving
     * `DTEND` at its old value (which "5 baje" moving a 4–5pm meeting to
     * 5–5pm would otherwise do). `null` when the event no longer exists or
     * the provider is unavailable — the caller falls back to changing only
     * what it was told to.
     */
    fun readEvent(eventId: Long): EventTimes? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)

        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val start = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                val end = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                EventTimes(start, end)
            }
        } catch (throwable: Throwable) {
            logger.w(TAG, "Could not read event id=$eventId before updating", throwable)
            null
        }
    }

    /**
     * Changes one or more fields of an existing event.
     *
     * Only [title], [startMillis] and [endMillis] are supported — the fields
     * DPS's tools ever need to change, and all three are documented as
     * writable by an application (`CalendarContract.Events` reference: TITLE,
     * DTSTART, DTEND). Absent parameters are left untouched; `ContentValues`
     * simply omits keys nobody supplied.
     *
     * @return [MutationOutcome.Updated] on `rows > 0`, [MutationOutcome.NotFound]
     *   on `rows == 0` — the documented way to detect a missing event, since
     *   the provider does not throw for one.
     */
    fun updateEvent(
        eventId: Long,
        title: String? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
    ): MutationOutcome {
        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            startMillis?.let { put(CalendarContract.Events.DTSTART, it) }
            endMillis?.let { put(CalendarContract.Events.DTEND, it) }
        }
        if (values.size() == 0) return MutationOutcome.NotFound

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

        return try {
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows > 0) {
                logger.i(TAG, "Updated calendar event id=$eventId rows=$rows")
                MutationOutcome.Updated
            } else {
                MutationOutcome.NotFound
            }
        } catch (security: SecurityException) {
            logger.w(TAG, "Calendar update denied", security)
            MutationOutcome.Failed("Calendar access was denied.")
        } catch (illegal: IllegalArgumentException) {
            logger.w(TAG, "No calendar provider", illegal)
            MutationOutcome.NoProvider
        } catch (throwable: Throwable) {
            logger.e(TAG, "Calendar update failed", throwable)
            MutationOutcome.Failed(throwable.message ?: "Could not update the event.")
        }
    }

    /**
     * Removes an event.
     *
     * Documented distinction: "An application delete sets the `_deleted`
     * column to 1... A sync adapter delete removes the event from the
     * database along with all its associated data." This is an application
     * delete — DPS is not a sync adapter — so the row is flagged for removal
     * and propagation to the account's server, which is exactly what deleting
     * the same event from the Calendar app itself would do.
     */
    fun deleteEvent(eventId: Long): MutationOutcome {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                logger.i(TAG, "Deleted calendar event id=$eventId rows=$rows")
                MutationOutcome.Deleted
            } else {
                MutationOutcome.NotFound
            }
        } catch (security: SecurityException) {
            logger.w(TAG, "Calendar delete denied", security)
            MutationOutcome.Failed("Calendar access was denied.")
        } catch (illegal: IllegalArgumentException) {
            logger.w(TAG, "No calendar provider", illegal)
            MutationOutcome.NoProvider
        } catch (throwable: Throwable) {
            logger.e(TAG, "Calendar delete failed", throwable)
            MutationOutcome.Failed(throwable.message ?: "Could not delete the event.")
        }
    }

    private companion object {
        const val TAG = "CalendarWriter"
    }
}
