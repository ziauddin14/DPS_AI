package com.softwaremine.dps.data.android.memory.episodic

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.softwaremine.dps.domain.memory.EpisodicMemoryEntry

/**
 * The Room-persisted shape of [EpisodicMemoryEntry] (M6). See
 * [com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity]'s
 * own doc for why this is a separate type from its pure-Kotlin domain
 * counterpart rather than the same class reused.
 */
@Entity(tableName = "episodic_memory")
data class EpisodicMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMillis: Long,
    val summary: String,
    val toolId: String?,
) {
    fun toDomain(): EpisodicMemoryEntry = EpisodicMemoryEntry(
        id = id,
        timestampMillis = timestampMillis,
        summary = summary,
        toolId = toolId,
    )
}
