package com.softwaremine.dps.data.android.memory

import com.softwaremine.dps.ai.memory.MemoryPrivacyGuard
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryDao
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryEntity
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactDao
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity
import com.softwaremine.dps.domain.memory.EpisodicMemoryEntry
import com.softwaremine.dps.domain.memory.SemanticFact
import java.util.concurrent.TimeUnit

/**
 * The one class that touches [DpsMemoryDatabase]'s DAOs directly (M6).
 *
 * ## Why decision logic lives here, not in the DAOs
 * [SemanticFactDao]/[EpisodicMemoryDao] are deliberately thin — raw CRUD
 * only. Retention pruning, the privacy guard, and "which stored fact is
 * actually relevant" all live here instead, written as functions over plain
 * [SemanticFact]/[EpisodicMemoryEntry] lists wherever the logic itself does
 * not need a live database connection — so that logic is JVM-testable, even
 * though Room itself is not (see [DpsMemoryDatabase]'s own doc).
 *
 * ## Outcome of a refused fact
 * [rememberFact] returns `null` when [MemoryPrivacyGuard] refuses the
 * content — the caller ([com.softwaremine.dps.data.android.tool.AndroidMemoryTool])
 * turns that into an honest [com.softwaremine.dps.domain.tool.ToolResult.Failure],
 * never a silent partial store.
 */
class LongTermMemoryStore(
    private val semanticFactDao: SemanticFactDao,
    private val episodicMemoryDao: EpisodicMemoryDao,
    private val logger: DpsLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ------------------------------------------------------------- semantic

    /**
     * Persists a new fact, or refuses and returns `null` when
     * [MemoryPrivacyGuard] disallows [factText] — see this class's own doc.
     * Never merges with or overwrites an existing fact about [subject]; see
     * [SemanticFact]'s own doc for why conflicting facts simply coexist.
     */
    suspend fun rememberFact(subject: String, factText: String, sourceUtterance: String?): SemanticFact? {
        if (MemoryPrivacyGuard.isDisallowed(factText)) {
            logger.w(TAG, "Refused to store a fact matching the privacy deny-list")
            return null
        }

        val nowMillis = now()
        val entity = SemanticFactEntity.fromNewFact(subject, factText, sourceUtterance, nowMillis)
        val id = semanticFactDao.insert(entity)
        return entity.copy(id = id).toDomain()
    }

    /**
     * Facts about [subjectQuery], most relevant first. M6's own deterministic
     * retrieval: an exact (case-insensitive) subject match ranks above a
     * mere substring match, and within each tier, newest first — the
     * "relevant, not just newest" requirement, met without embeddings or
     * any similarity search (see the M6 plan's own §4 for why those are not
     * justified here).
     */
    suspend fun recallFacts(subjectQuery: String): List<SemanticFact> {
        val candidates = semanticFactDao.findBySubject(subjectQuery).map { it.toDomain() }
        return rankBySubjectRelevance(candidates, subjectQuery)
    }

    /** Every stored fact — memory-controls "what do you remember" only. */
    suspend fun allFacts(): List<SemanticFact> = semanticFactDao.findAll().map { it.toDomain() }

    /** `true` when a fact existed and was actually removed. */
    suspend fun forgetFact(id: Long): Boolean = semanticFactDao.deleteById(id) > 0

    // ------------------------------------------------------------- episodic

    /**
     * Logs one completed action. Always succeeds (never refused by the
     * privacy guard — episodic summaries are DPS's own deterministic,
     * templated text, not user-supplied free-form content; see
     * [EpisodicMemoryEntry]'s own doc). Prunes opportunistically afterward —
     * see [EpisodicMemoryDao]'s own retention doc.
     */
    suspend fun recordEpisode(summary: String, toolId: String?) {
        val entity = EpisodicMemoryEntity(timestampMillis = now(), summary = summary, toolId = toolId)
        episodicMemoryDao.insert(entity)
        pruneEpisodicIfNeeded()
    }

    /** Entries in `[fromMillis, toMillis]`, optionally narrowed by [keyword]. */
    suspend fun findEpisodic(fromMillis: Long, toMillis: Long, keyword: String? = null, limit: Int = MAX_EPISODIC_RESULTS): List<EpisodicMemoryEntry> {
        val entities = if (keyword.isNullOrBlank()) {
            episodicMemoryDao.findInRange(fromMillis, toMillis, limit)
        } else {
            episodicMemoryDao.findByKeyword(keyword, limit).filter { it.timestampMillis in fromMillis..toMillis }
        }
        return entities.map { it.toDomain() }
    }

    private suspend fun pruneEpisodicIfNeeded() {
        val cutoff = now() - TimeUnit.DAYS.toMillis(EPISODIC_RETENTION_DAYS)
        val prunedByAge = episodicMemoryDao.deleteOlderThan(cutoff)
        if (prunedByAge > 0) {
            logger.d(TAG, "Pruned $prunedByAge episodic entr${if (prunedByAge == 1) "y" else "ies"} older than $EPISODIC_RETENTION_DAYS days")
        }
        if (episodicMemoryDao.count() > EPISODIC_MAX_ROWS) {
            val prunedByCount = episodicMemoryDao.deleteOldestBeyond(EPISODIC_MAX_ROWS)
            logger.d(TAG, "Pruned $prunedByCount episodic entries beyond the $EPISODIC_MAX_ROWS-row cap")
        }
    }

    companion object {
        private const val TAG = "LongTermMemoryStore"

        /** Episodic retention (M6 architectural decision) — a semantic fact, by contrast, is never auto-pruned; see [SemanticFact]'s own doc. */
        const val EPISODIC_RETENTION_DAYS = 365L

        /** Hard safety ceiling regardless of age, so storage cannot grow unbounded even under unusually heavy daily use. */
        const val EPISODIC_MAX_ROWS = 5_000

        private const val MAX_EPISODIC_RESULTS = 20

        /**
         * Ranks [candidates] by relevance to [subjectQuery]: an exact
         * (case-insensitive) subject match first, then a substring match,
         * each tier already newest-first from the DAO's own ordering. Pure
         * function over domain types — no database access — so this is
         * independently JVM-testable from the DAO/Room plumbing around it.
         */
        internal fun rankBySubjectRelevance(candidates: List<SemanticFact>, subjectQuery: String): List<SemanticFact> {
            val normalizedQuery = subjectQuery.trim().lowercase()
            val (exact, partial) = candidates.partition { it.subject.trim().lowercase() == normalizedQuery }
            return exact + partial
        }
    }
}
