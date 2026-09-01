package com.softwaremine.dps.data.android.memory

import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryDao
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryEntity
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactDao
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity
import com.softwaremine.dps.domain.memory.SemanticFact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the M6 long-term memory repository.
 *
 * ## Why the DAOs are fakes, not Room
 * Room itself has no JVM seam (see [DpsMemoryDatabase]'s own doc) — every
 * decision this class makes (retrieval ranking, retention pruning, the
 * privacy guard) is pure Kotlin over these thin DAO interfaces, so an
 * in-memory fake exercises the real logic without needing an instrumented
 * test for it.
 */
class LongTermMemoryStoreTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private class FakeSemanticFactDao : SemanticFactDao {
        private val facts = mutableListOf<SemanticFactEntity>()
        private var nextId = 1L
        override suspend fun insert(fact: SemanticFactEntity): Long {
            val assigned = fact.copy(id = nextId++)
            facts += assigned
            return assigned.id
        }
        override suspend fun findBySubject(subjectQuery: String): List<SemanticFactEntity> =
            facts.filter { it.subject.contains(subjectQuery, ignoreCase = true) }.sortedByDescending { it.createdAtMillis }
        override suspend fun findAll(): List<SemanticFactEntity> = facts.sortedByDescending { it.createdAtMillis }
        override suspend fun delete(fact: SemanticFactEntity): Int = if (facts.removeAll { it.id == fact.id }) 1 else 0
        override suspend fun deleteById(id: Long): Int = if (facts.removeAll { it.id == id }) 1 else 0
    }

    private class FakeEpisodicMemoryDao : EpisodicMemoryDao {
        val entries = mutableListOf<EpisodicMemoryEntity>()
        private var nextId = 1L
        override suspend fun insert(entry: EpisodicMemoryEntity): Long {
            val assigned = entry.copy(id = nextId++)
            entries += assigned
            return assigned.id
        }
        override suspend fun findInRange(fromMillis: Long, toMillis: Long, limit: Int): List<EpisodicMemoryEntity> =
            entries.filter { it.timestampMillis in fromMillis..toMillis }.sortedByDescending { it.timestampMillis }.take(limit)
        override suspend fun findByKeyword(keyword: String, limit: Int): List<EpisodicMemoryEntity> =
            entries.filter { it.summary.contains(keyword, ignoreCase = true) }.sortedByDescending { it.timestampMillis }.take(limit)
        override suspend fun count(): Int = entries.size
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
            val before = entries.size
            entries.removeAll { it.timestampMillis < cutoffMillis }
            return before - entries.size
        }
        override suspend fun deleteOldestBeyond(keepNewest: Int): Int {
            if (entries.size <= keepNewest) return 0
            val toKeep = entries.sortedByDescending { it.id }.take(keepNewest).map { it.id }.toSet()
            val before = entries.size
            entries.removeAll { it.id !in toKeep }
            return before - entries.size
        }
    }

    private fun store(
        semanticFactDao: SemanticFactDao = FakeSemanticFactDao(),
        episodicMemoryDao: EpisodicMemoryDao = FakeEpisodicMemoryDao(),
        now: () -> Long = System::currentTimeMillis,
    ) = LongTermMemoryStore(semanticFactDao, episodicMemoryDao, silentLogger, now)

    // -----------------------------------------------------------------
    // rankBySubjectRelevance — pure function
    // -----------------------------------------------------------------

    private fun fact(id: Long, subject: String, createdAtMillis: Long) = SemanticFact(
        id = id,
        subject = subject,
        factText = "fact $id",
        sourceUtterance = null,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = createdAtMillis,
    )

    @Test
    fun `an exact subject match ranks above a mere substring match`() {
        val exact = fact(1, "Bilal", createdAtMillis = 1_000)
        val substring = fact(2, "Bilal Ahmed", createdAtMillis = 2_000)

        val ranked = LongTermMemoryStore.rankBySubjectRelevance(listOf(substring, exact), "Bilal")

        assertEquals(listOf(exact, substring), ranked)
    }

    @Test
    fun `an exact match is case-insensitive`() {
        val exact = fact(1, "bilal", createdAtMillis = 1_000)

        val ranked = LongTermMemoryStore.rankBySubjectRelevance(listOf(exact), "Bilal")

        assertEquals(listOf(exact), ranked)
    }

    @Test
    fun `within a tier the caller's own newest-first ordering is preserved, not re-sorted`() {
        // rankBySubjectRelevance only partitions exact-vs-partial — "newest
        // first" is a property of the DAO's own ORDER BY createdAtMillis
        // DESC that this pure function relies on and must not disturb.
        val newer = fact(2, "Bilal", createdAtMillis = 2_000)
        val older = fact(1, "Bilal", createdAtMillis = 1_000)

        val ranked = LongTermMemoryStore.rankBySubjectRelevance(listOf(newer, older), "Bilal")

        assertEquals(listOf(newer, older), ranked)
    }

    // -----------------------------------------------------------------
    // Semantic facts
    // -----------------------------------------------------------------

    @Test
    fun `remembering a fact persists it and assigns a real id`() = runTest {
        val saved = store().rememberFact("Bilal", "mera developer hai", sourceUtterance = "Bilal mera developer hai")!!

        assertEquals("Bilal", saved.subject)
        assertEquals("mera developer hai", saved.factText)
        assertTrue(saved.id > 0)
    }

    @Test
    fun `a disallowed fact is refused and never persisted`() = runTest {
        val memory = store()

        val saved = memory.rememberFact("card", "the card number is 4111111111111111", sourceUtterance = null)

        assertNull(saved)
        assertTrue(memory.allFacts().isEmpty())
    }

    @Test
    fun `conflicting facts about the same subject coexist rather than merge`() = runTest {
        val memory = store()
        memory.rememberFact("Bilal", "mera developer hai", sourceUtterance = null)
        memory.rememberFact("Bilal", "mera dost hai", sourceUtterance = null)

        assertEquals(2, memory.recallFacts("Bilal").size)
    }

    @Test
    fun `forgetting a fact removes only that fact`() = runTest {
        val memory = store()
        val first = memory.rememberFact("Bilal", "mera developer hai", sourceUtterance = null)!!
        memory.rememberFact("Ali", "mera dost hai", sourceUtterance = null)

        val forgotten = memory.forgetFact(first.id)

        assertTrue(forgotten)
        assertTrue(memory.recallFacts("Bilal").isEmpty())
        assertEquals(1, memory.recallFacts("Ali").size)
    }

    @Test
    fun `forgetting a fact that never existed reports false`() = runTest {
        assertTrue(!store().forgetFact(999L))
    }

    // -----------------------------------------------------------------
    // Episodic memory and retention
    // -----------------------------------------------------------------

    @Test
    fun `recording an episode makes it findable in range`() = runTest {
        val memory = store(now = { 5_000L })
        memory.recordEpisode("Reminder set.", "reminder")

        val found = memory.findEpisodic(fromMillis = 0, toMillis = 10_000)

        assertEquals(1, found.size)
        assertEquals("Reminder set.", found.single().summary)
    }

    @Test
    fun `episodic entries older than the retention window are pruned on the next insert`() = runTest {
        val episodicDao = FakeEpisodicMemoryDao()
        var clock = 0L
        val memory = store(episodicMemoryDao = episodicDao, now = { clock })

        memory.recordEpisode("old entry", null)
        clock += java.util.concurrent.TimeUnit.DAYS.toMillis(LongTermMemoryStore.EPISODIC_RETENTION_DAYS) + 1
        memory.recordEpisode("new entry", null)

        val remaining = episodicDao.entries.map { it.summary }
        assertEquals(listOf("new entry"), remaining)
    }

    @Test
    fun `episodic entries beyond the row cap are pruned oldest-first`() = runTest {
        val episodicDao = FakeEpisodicMemoryDao()
        val memory = store(episodicMemoryDao = episodicDao)

        repeat(LongTermMemoryStore.EPISODIC_MAX_ROWS + 5) { index ->
            memory.recordEpisode("entry $index", null)
        }

        assertEquals(LongTermMemoryStore.EPISODIC_MAX_ROWS, episodicDao.entries.size)
        assertTrue(episodicDao.entries.none { it.summary == "entry 0" })
        assertTrue(episodicDao.entries.any { it.summary == "entry ${LongTermMemoryStore.EPISODIC_MAX_ROWS + 4}" })
    }
}
