package com.softwaremine.dps.domain.productivity.report

import java.time.LocalDate
import java.time.ZoneId

/**
 * A half-open `[startMillis, endMillis)` window used to slice productivity
 * records by date.
 *
 * ## Purpose — the Day 06 "monthly reporting foundation"
 * The brief asks for a date-range query abstraction without a full analytics
 * system. This is that abstraction: a day, a week or a month are all just a
 * [DateRange], filtered with [contains] against a record's own timestamp.
 * [ReportGenerator.summarize] does not special-case any period — a monthly
 * report is exactly the same aggregation run over [month] instead of [day],
 * so there is no separate monthly code path to keep in step.
 *
 * ## Dependencies
 * `java.time` only. Pure Kotlin.
 */
data class DateRange(val startMillis: Long, val endMillis: Long) {
    operator fun contains(instantMillis: Long): Boolean =
        instantMillis >= startMillis && instantMillis < endMillis

    companion object {
        fun day(date: LocalDate, zone: ZoneId): DateRange {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(start, end)
        }

        /** The Monday-to-Monday week containing [containing]. */
        fun week(containing: LocalDate, zone: ZoneId): DateRange {
            val start = containing.minusDays((containing.dayOfWeek.value - 1).toLong())
            val end = start.plusDays(7)
            return DateRange(
                start.atStartOfDay(zone).toInstant().toEpochMilli(),
                end.atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }

        fun month(containing: LocalDate, zone: ZoneId): DateRange {
            val start = containing.withDayOfMonth(1)
            val end = start.plusMonths(1)
            return DateRange(
                start.atStartOfDay(zone).toInstant().toEpochMilli(),
                end.atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
    }
}
