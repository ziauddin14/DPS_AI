package com.softwaremine.dps.data.android.memory

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that [LongTermMemoryStore] runs against a real Room
 * database, not just against the fake DAOs the JVM suite uses.
 *
 * ## Why in-memory Room, not the real on-disk file
 * [DpsMemoryDatabase.create] opens the one, shared, on-disk database file —
 * using it here would leak state between test runs and between this suite
 * and manual app use on the same device.
 * [LongTermMemoryProcessDeathInstrumentedTest] is the one test in this
 * codebase that deliberately needs the real file, for exactly the process
 * restart it exists to prove; every other on-device behaviour (query
 * correctness, retention pruning) is equally real against an in-memory
 * database, since both run the identical generated SQL against a real
 * SQLite engine.
 */
@RunWith(AndroidJUnit4::class)
class LongTermMemoryStoreInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private lateinit var database: DpsMemoryDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, DpsMemoryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun store(now: () -> Long = System::currentTimeMillis) =
        LongTermMemoryStore(database.semanticFactDao(), database.episodicMemoryDao(), logger, now)

    @Test
    fun rememberingAFactPersistsItThroughRealSqlite() = runBlocking {
        val memory = store()

        val saved = memory.rememberFact("Bilal", "mera developer hai", sourceUtterance = "Bilal mera developer hai")!!
        val recalled = memory.recallFacts("Bilal")

        assertTrue(saved.id > 0)
        assertEquals(1, recalled.size)
        assertEquals("mera developer hai", recalled.single().factText)
    }

    @Test
    fun forgettingAFactRemovesOnlyThatRowFromRealSqlite() = runBlocking {
        val memory = store()
        val bilal = memory.rememberFact("Bilal", "mera developer hai", sourceUtterance = null)!!
        memory.rememberFact("Ali", "mera dost hai", sourceUtterance = null)

        val forgotten = memory.forgetFact(bilal.id)

        assertTrue(forgotten)
        assertTrue(memory.recallFacts("Bilal").isEmpty())
        assertEquals(1, memory.recallFacts("Ali").size)
    }

    @Test
    fun aDisallowedFactIsNeverWrittenToRealSqlite() = runBlocking {
        val memory = store()

        val saved = memory.rememberFact("card", "the card number is 4111111111111111", sourceUtterance = null)

        assertNull(saved)
        assertTrue(memory.allFacts().isEmpty())
    }

    @Test
    fun recordingAnEpisodeMakesItFindableByRangeAndKeyword() = runBlocking {
        val memory = store(now = { 10_000L })

        memory.recordEpisode("Reminder set.", "reminder")

        val byRange = memory.findEpisodic(fromMillis = 0, toMillis = 20_000)
        val byKeyword = memory.findEpisodic(fromMillis = 0, toMillis = 20_000, keyword = "reminder")

        assertEquals(1, byRange.size)
        assertEquals(1, byKeyword.size)
        assertEquals("Reminder set.", byRange.single().summary)
    }

    @Test
    fun episodicEntriesBeyondTheRowCapArePrunedOldestFirstByRealSqlite() = runBlocking {
        var clock = 0L
        val memory = store(now = { clock })

        repeat(LongTermMemoryStore.EPISODIC_MAX_ROWS + 3) { index ->
            clock += 1
            memory.recordEpisode("entry $index", null)
        }

        val remaining = memory.findEpisodic(fromMillis = 0, toMillis = clock, limit = LongTermMemoryStore.EPISODIC_MAX_ROWS)
        assertEquals(LongTermMemoryStore.EPISODIC_MAX_ROWS, remaining.size)
        assertTrue(remaining.none { it.summary == "entry 0" })
    }
}
