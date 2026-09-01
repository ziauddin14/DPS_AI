package com.softwaremine.dps.data.android.tool

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.memory.DpsMemoryDatabase
import com.softwaremine.dps.data.android.memory.LongTermMemoryStore
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device, real-Room, end-to-end verification of [AndroidMemoryTool] (M6) —
 * the tool layer above [LongTermMemoryStoreInstrumentedTest][com.softwaremine.dps.data.android.memory.LongTermMemoryStoreInstrumentedTest]'s
 * own repository-level coverage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMemoryToolInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private lateinit var database: DpsMemoryDatabase
    private lateinit var tool: AndroidMemoryTool

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, DpsMemoryDatabase::class.java).build()
        tool = AndroidMemoryTool(LongTermMemoryStore(database.semanticFactDao(), database.episodicMemoryDao(), logger))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun call(operation: String, vararg arguments: Pair<String, String>) =
        ToolCall(toolId = ToolId.MEMORY, operation = operation, arguments = arguments.toMap())

    @Test
    fun rememberThenRecallReturnsTheStoredFact() = runBlocking {
        val remembered = tool.execute(call("remember_fact", "title" to "Bilal", "message" to "mera developer hai"))
        assertTrue(remembered is ToolResult.Success)

        val recalled = tool.execute(call("recall_fact", "title" to "Bilal"))
        assertTrue(recalled is ToolResult.Success)
        assertTrue((recalled as ToolResult.Success).summary.contains("mera developer hai"))
    }

    @Test
    fun recallingAnUnknownSubjectNeverFabricatesAnAnswer() = runBlocking {
        val recalled = tool.execute(call("recall_fact", "title" to "Nobody"))

        assertTrue(recalled is ToolResult.Success)
        assertTrue((recalled as ToolResult.Success).summary.contains("don't have anything remembered"))
    }

    @Test
    fun forgettingTheOnlyMatchSucceedsAndSubsequentRecallFindsNothing() = runBlocking {
        tool.execute(call("remember_fact", "title" to "Bilal", "message" to "mera developer hai"))

        val forgotten = tool.execute(call("forget_fact", "title" to "Bilal"))
        assertTrue(forgotten is ToolResult.Success)

        val recalled = tool.execute(call("recall_fact", "title" to "Bilal"))
        assertTrue((recalled as ToolResult.Success).summary.contains("don't have anything remembered"))
    }

    @Test
    fun forgettingAnAmbiguousSubjectFailsAndAsksWhichOne() = runBlocking {
        tool.execute(call("remember_fact", "title" to "Bilal", "message" to "mera developer hai"))
        tool.execute(call("remember_fact", "title" to "Bilal", "message" to "mera dost bhi hai"))

        val forgotten = tool.execute(call("forget_fact", "title" to "Bilal"))

        assertTrue(forgotten is ToolResult.Failure)
        assertTrue((forgotten as ToolResult.Failure).reason.contains("Which one"))
    }

    @Test
    fun rememberingADisallowedFactFailsRatherThanStoringIt() = runBlocking {
        val result = tool.execute(
            call("remember_fact", "title" to "card", "message" to "the card number is 4111111111111111"),
        )

        assertTrue(result is ToolResult.Failure)

        val recalled = tool.execute(call("recall_fact", "title" to "card"))
        assertTrue((recalled as ToolResult.Success).summary.contains("don't have anything remembered"))
    }
}
