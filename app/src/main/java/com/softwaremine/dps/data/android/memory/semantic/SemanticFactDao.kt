package com.softwaremine.dps.data.android.memory.semantic

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/**
 * Raw CRUD for [SemanticFactEntity] (M6). No decision logic lives here —
 * retrieval relevance, the privacy deny-list, and every other judgment call
 * belong in [com.softwaremine.dps.data.android.memory.LongTermMemoryStore],
 * kept there specifically so that logic stays pure-Kotlin and JVM-testable;
 * this interface exists only to be the thin, Room-generated persistence
 * boundary underneath it. See [SemanticFactEntity]'s own doc for why this
 * table has no soft-delete/inactive concept — [delete] removes a row
 * outright.
 *
 * ## Why every function is `suspend`, not fire-and-forget
 * A detached, unawaited write racing a process death is exactly the bug
 * class M5-C's own "window-D" fix exists to prevent, in a different store.
 * Every caller of this DAO awaits these calls — SQLite's own transaction
 * durability is the correctness guarantee here, the same role `.commit()`
 * plays for the `SharedPreferences`-backed stores elsewhere in this
 * codebase.
 */
@Dao
interface SemanticFactDao {

    /** Returns the newly assigned row id — see [SemanticFactEntity]'s own doc on `id = 0`. */
    @Insert
    suspend fun insert(fact: SemanticFactEntity): Long

    /**
     * Every fact whose [SemanticFactEntity.subject] contains [subjectQuery],
     * case-insensitively, newest first. Deliberately a substring match, not
     * an exact one — "Bilal kaun hai?" and a fact stored for "Bilal Ahmed"
     * should still connect. Relevance beyond this raw match (M6's own
     * "relevant, not just newest" requirement) is decided by
     * [com.softwaremine.dps.data.android.memory.LongTermMemoryStore], not
     * here.
     */
    @Query("SELECT * FROM semantic_facts WHERE subject LIKE '%' || :subjectQuery || '%' COLLATE NOCASE ORDER BY createdAtMillis DESC")
    suspend fun findBySubject(subjectQuery: String): List<SemanticFactEntity>

    /** Every stored fact — used only by memory-controls "what do you remember" listings, never by ordinary conversation handling. */
    @Query("SELECT * FROM semantic_facts ORDER BY createdAtMillis DESC")
    suspend fun findAll(): List<SemanticFactEntity>

    @Delete
    suspend fun delete(fact: SemanticFactEntity): Int

    @Query("DELETE FROM semantic_facts WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
