package com.softwaremine.dps.data.android.memory.episodic

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Raw CRUD for [EpisodicMemoryEntity] (M6) — see [SemanticFactDao][com.softwaremine.dps.data.android.memory.semantic.SemanticFactDao]'s
 * own doc for why this stays a thin persistence boundary with no decision
 * logic, and why every function is `suspend` rather than fire-and-forget.
 *
 * ## Retention (M6 architectural decision)
 * Unlike [SemanticFactEntity][com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity],
 * which is never pruned automatically — a semantic fact is meant to be
 * durable — episodic entries are bounded on two independent axes: age
 * (older than [com.softwaremine.dps.data.android.memory.LongTermMemoryStore.EPISODIC_RETENTION_DAYS])
 * and count (more than [com.softwaremine.dps.data.android.memory.LongTermMemoryStore.EPISODIC_MAX_ROWS]
 * total, oldest first). Pruning is checked opportunistically after each
 * insert — mirroring [com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator.pruneMarkersOlderThan]'s
 * own established pattern — rather than via a new scheduled job, which
 * would be background-execution surface area outside M6's scope.
 */
@Dao
interface EpisodicMemoryDao {

    @Insert
    suspend fun insert(entry: EpisodicMemoryEntity): Long

    /** Every entry starting in `[fromMillis, toMillis]`, newest first, capped at [limit]. */
    @Query("SELECT * FROM episodic_memory WHERE timestampMillis BETWEEN :fromMillis AND :toMillis ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun findInRange(fromMillis: Long, toMillis: Long, limit: Int): List<EpisodicMemoryEntity>

    /** Every entry whose [EpisodicMemoryEntity.summary] contains [keyword], case-insensitively, newest first, capped at [limit]. */
    @Query("SELECT * FROM episodic_memory WHERE summary LIKE '%' || :keyword || '%' COLLATE NOCASE ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun findByKeyword(keyword: String, limit: Int): List<EpisodicMemoryEntity>

    @Query("SELECT COUNT(*) FROM episodic_memory")
    suspend fun count(): Int

    @Query("DELETE FROM episodic_memory WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    /**
     * Deletes the oldest rows beyond the newest [keepNewest] — the count-based
     * half of the retention policy. `id` (an auto-incrementing primary key)
     * is used as the tie-breaker/ordering proxy for "oldest," equivalent to
     * `timestampMillis` for rows this DAO itself ever inserts (always
     * increasing), and cheaper for SQLite to sort on.
     */
    @Query(
        "DELETE FROM episodic_memory WHERE id NOT IN " +
            "(SELECT id FROM episodic_memory ORDER BY id DESC LIMIT :keepNewest)",
    )
    suspend fun deleteOldestBeyond(keepNewest: Int): Int
}
