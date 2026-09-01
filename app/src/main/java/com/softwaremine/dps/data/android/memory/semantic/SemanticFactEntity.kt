package com.softwaremine.dps.data.android.memory.semantic

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.softwaremine.dps.domain.memory.SemanticFact

/**
 * The Room-persisted shape of [SemanticFact] (M6).
 *
 * ## Why this is not [SemanticFact] itself
 * [SemanticFact] is pure Kotlin — no Android, no Room — so it stays usable
 * and testable from `ai/` without pulling Room into that module. This is
 * the identical data-layer/domain-layer split
 * [com.softwaremine.dps.data.android.calendar.CalendarWriter.EventOccurrence]
 * already establishes for [com.softwaremine.dps.domain.proactive.UpcomingEventOccurrence] —
 * the mapping (`toDomain`/`toEntity` below) is a trivial field-for-field copy,
 * not new logic.
 *
 * ## Why `id` defaults to `0`
 * Room's own documented convention for `autoGenerate = true`: inserting a
 * row with `id = 0` tells SQLite to assign the next real id itself. A
 * freshly-constructed fact (not yet persisted) therefore always passes `0`
 * here — see [SemanticFactDao.insert]'s own doc for where the real id comes
 * from.
 */
@Entity(tableName = "semantic_facts")
data class SemanticFactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String,
    val factText: String,
    val sourceUtterance: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain(): SemanticFact = SemanticFact(
        id = id,
        subject = subject,
        factText = factText,
        sourceUtterance = sourceUtterance,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        /** `id` deliberately left at its `0` default — see this class's own doc. */
        fun fromNewFact(subject: String, factText: String, sourceUtterance: String?, nowMillis: Long): SemanticFactEntity =
            SemanticFactEntity(
                subject = subject,
                factText = factText,
                sourceUtterance = sourceUtterance,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            )
    }
}
