package com.softwaremine.dps.data.android.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6: real Android process-death validation for the long-term memory Room
 * database — the M3-D methodology
 * ([com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest])
 * applied to [DpsMemoryDatabase] instead of `SharedPreferences`.
 *
 * ## Why two `@Test` methods, run as two separate `am instrument` invocations
 * See [com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest]'s
 * own doc for the full reasoning — a single JUnit method cannot outlive its
 * own process, so proving "written in process A, read in process B" requires
 * a real `adb shell am force-stop` between two separate invocations:
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.memory.LongTermMemoryProcessDeathInstrumentedTest#phase1WriteRealMemoryBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.memory.LongTermMemoryProcessDeathInstrumentedTest#phase2VerifyRestoredMemoryAfterProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * ## Why [DpsMemoryDatabase.create], not `Room.inMemoryDatabaseBuilder`
 * [LongTermMemoryStoreInstrumentedTest] deliberately uses an in-memory
 * database because it must not leak state between test methods — the exact
 * opposite of what this class needs. [DpsMemoryDatabase.create] opens the
 * one, real, on-disk `dps_long_term_memory.db` file, the same file
 * [com.softwaremine.dps.di.AiContainer] opens in the running app, so a
 * genuine kill-and-restart is what actually gets proven here.
 *
 * ## What phase1 leaves behind, deliberately
 * Phase1 does not clean up the fact it stores — that row is exactly what
 * phase2 needs to still find after the kill. Phase2 deletes it once it has
 * finished asserting against what survived.
 */
@RunWith(AndroidJUnit4::class)
class LongTermMemoryProcessDeathInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun store(): LongTermMemoryStore {
        val database = DpsMemoryDatabase.create(context)
        return LongTermMemoryStore(database.semanticFactDao(), database.episodicMemoryDao(), logger)
    }

    @Test
    fun phase1WriteRealMemoryBeforeProcessDeath() = runBlocking {
        val memory = store()

        memory.rememberFact(SUBJECT, "mera developer hai", sourceUtterance = "Bilal mera developer hai")
        memory.recordEpisode(EPISODE_SUMMARY, "reminder")

        assertEquals(1, memory.recallFacts(SUBJECT).size)
    }

    @Test
    fun phase2VerifyRestoredMemoryAfterProcessDeath() = runBlocking {
        // A fresh AndroidDpsLogger and a fresh DpsMemoryDatabase.create() call
        // — nothing here is the same in-process object phase1 used. Only the
        // on-disk SQLite file connects them, exactly as a real process
        // restart of the shipping app would.
        val memory = store()

        val recalledFacts = memory.recallFacts(SUBJECT)
        assertEquals("The fact written before process death must survive it", 1, recalledFacts.size)
        val restoredFact = recalledFacts.single()
        assertEquals("mera developer hai", restoredFact.factText)

        val recalledEpisodes = memory.findEpisodic(fromMillis = 0, toMillis = Long.MAX_VALUE, keyword = EPISODE_SUMMARY)
        assertTrue("The episodic entry written before process death must survive it", recalledEpisodes.isNotEmpty())

        // Cleanup, now that both survivals are proven — mirrors
        // ProcessDeathPersistenceInstrumentedTest's own phase2 doing the
        // cleanup for both phases once it is done asserting.
        memory.forgetFact(restoredFact.id)
        assertTrue(memory.recallFacts(SUBJECT).isEmpty())
    }

    private companion object {
        const val SUBJECT = "Bilal-M6-ProcessDeath"
        const val EPISODE_SUMMARY = "M6-ProcessDeath-Episode-Marker"
    }
}
