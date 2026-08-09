package com.softwaremine.dps.data.android.productivity

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.tool.AndroidActionItemTool
import com.softwaremine.dps.data.android.tool.AndroidMeetingNoteTool
import com.softwaremine.dps.data.android.tool.AndroidReportTool
import com.softwaremine.dps.data.android.tool.AndroidTaskTool
import com.softwaremine.dps.data.android.tool.AndroidWorkLogTool
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Day 06 productivity layer.
 *
 * ## What only a device can prove
 * [AndroidTaskStore] and its siblings hold a real `SharedPreferences` — no
 * interface seam, no Robolectric (ADR-009) — so persistence itself is only
 * verifiable here, exactly as [com.softwaremine.dps.data.android.reminder.ReminderStore]'s
 * own instrumented coverage already establishes for Day 05. This class also
 * closes the same "drift" gap [AndroidToolsInstrumentedTest] closes for
 * Phase B: that [AiContainer] really registers the five new tools, not just
 * that a fake registered in a JVM test does.
 *
 * ## Isolation
 * Each test clears its `SharedPreferences` file first — this data survives
 * between test runs on a real device otherwise, and a stale record from a
 * previous run would make a count-based assertion fail for the wrong reason.
 */
@RunWith(AndroidJUnit4::class)
class ProductivityInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    @Before
    fun clearStores() {
        listOf("dps_tasks", "dps_worklogs", "dps_meetings", "dps_action_items").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // -----------------------------------------------------------------
    // Persistence — TEST8: survives being reloaded from a fresh instance,
    // the same proxy for "survives app restart" ReminderStore's own tests use
    // (a real process kill cannot be driven from an instrumented test).
    // -----------------------------------------------------------------

    @Test
    fun taskPersistsAcrossAFreshStoreInstance() {
        val id = AndroidTaskStore(context, logger).let { store ->
            val id = store.nextId()
            store.save(
                Task(
                    id = id,
                    title = "DBPMS documentation",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            id
        }

        // A brand-new instance — nothing shared but the underlying file.
        val reloaded = AndroidTaskStore(context, logger).find(id)

        assertNotNull("Task did not survive a fresh store instance", reloaded)
        assertEquals("DBPMS documentation", reloaded!!.title)
    }

    @Test
    fun taskIdsNeverRepeatWithinAStore() {
        val store = AndroidTaskStore(context, logger)
        val first = store.nextId()
        val second = store.nextId()

        assertTrue("nextId() returned the same id twice: $first", first != second)
    }

    @Test
    fun deletingATaskRemovesItPermanently() {
        val store = AndroidTaskStore(context, logger)
        val id = store.nextId()
        store.save(Task(id = id, title = "temp", createdAtMillis = 1L, updatedAtMillis = 1L))

        assertTrue(store.delete(id))
        assertEquals(null, store.find(id))
    }

    @Test
    fun workLogMeetingAndActionItemStoresAllPersist() {
        val workLogId = AndroidWorkLogStore(context, logger).let { store ->
            val id = store.nextId()
            store.save(
                com.softwaremine.dps.domain.productivity.WorkLog(
                    id = id,
                    dateMillis = 0L,
                    durationMinutes = 120,
                    activity = "DBPMS",
                    createdAtMillis = 1L,
                ),
            )
            id
        }
        assertNotNull(AndroidWorkLogStore(context, logger).find(workLogId))

        val meetingId = AndroidMeetingNoteStore(context, logger).let { store ->
            val id = store.nextId()
            store.save(
                com.softwaremine.dps.domain.productivity.MeetingNote(
                    id = id,
                    title = "DBPMS meeting",
                    dateMillis = 0L,
                    createdAtMillis = 1L,
                ),
            )
            id
        }
        assertNotNull(AndroidMeetingNoteStore(context, logger).find(meetingId))

        val actionItemId = AndroidActionItemStore(context, logger).let { store ->
            val id = store.nextId()
            store.save(
                com.softwaremine.dps.domain.productivity.ActionItem(
                    id = id,
                    title = "send agenda",
                    createdAtMillis = 1L,
                ),
            )
            id
        }
        assertNotNull(AndroidActionItemStore(context, logger).find(actionItemId))
    }

    // -----------------------------------------------------------------
    // Registration — the same drift check AndroidToolsInstrumentedTest runs
    // for Phase B, now for the Day 06 tools.
    // -----------------------------------------------------------------

    @Test
    fun productivityToolsAreRegisteredAsRealImplementations() {
        val registry = AiContainer(context).toolRegistry

        assertTrue(registry.find(ToolId.TASK) is AndroidTaskTool)
        assertTrue(registry.find(ToolId.WORK_LOG) is AndroidWorkLogTool)
        assertTrue(registry.find(ToolId.MEETING) is AndroidMeetingNoteTool)
        assertTrue(registry.find(ToolId.ACTION_ITEM) is AndroidActionItemTool)
        assertTrue(registry.find(ToolId.REPORT) is AndroidReportTool)
    }

    // -----------------------------------------------------------------
    // TEST10: no duplicate records from retrying the same operation twice.
    // -----------------------------------------------------------------

    @Test
    fun creatingATaskTwiceProducesTwoDistinctRecordsNeverOne() = runBlocking {
        val store = AndroidTaskStore(context, logger)
        val tool = AndroidTaskTool(store)

        tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to "DBPMS documentation")))
        tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to "DBPMS documentation")))

        // Two separate user requests naming the same title are two tasks, not
        // one silently merged or one silently dropped.
        assertEquals(2, store.all().size)
    }

    @Test
    fun completingAnAlreadyCompletedTaskStaysIdempotentRatherThanErroring() = runBlocking {
        val store = AndroidTaskStore(context, logger)
        val tool = AndroidTaskTool(store)

        val created = tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to "DBPMS docs")))
        val id = (created as ToolResult.Success).data.getValue("task_id")

        val firstComplete = tool.execute(ToolCall(ToolId.TASK, "complete_task", mapOf("id" to id)))
        val secondComplete = tool.execute(ToolCall(ToolId.TASK, "complete_task", mapOf("id" to id)))

        assertTrue(firstComplete is ToolResult.Success)
        assertTrue(secondComplete is ToolResult.Success)
        assertEquals(TaskStatus.COMPLETED, store.find(id.toInt())!!.status)
        // Still exactly one record — completing twice must not duplicate it.
        assertEquals(1, store.all().count { it.id == id.toInt() })
    }

    // -----------------------------------------------------------------
    // Complete-by-title (Day 06's addressing rule) against real storage.
    // -----------------------------------------------------------------

    @Test
    fun completingATaskByTitleFindsItWithoutAnId() = runBlocking {
        val store = AndroidTaskStore(context, logger)
        val tool = AndroidTaskTool(store)

        tool.execute(ToolCall(ToolId.TASK, "create_task", mapOf("title" to "DBPMS documentation")))
        val result = tool.execute(ToolCall(ToolId.TASK, "complete_task", mapOf("title" to "dbpms")))

        assertTrue("Expected a title match to succeed, got $result", result is ToolResult.Success)
        assertEquals(TaskStatus.COMPLETED, store.all().single().status)
    }

    // -----------------------------------------------------------------
    // Daily report reflects real stored data — TEST6, against real storage.
    // -----------------------------------------------------------------

    @Test
    fun dailyReportReflectsWhatWasActuallyStoredToday() = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val workLogStore = AndroidWorkLogStore(context, logger)
        val meetingStore = AndroidMeetingNoteStore(context, logger)
        val actionItemStore = AndroidActionItemStore(context, logger)

        val taskId = taskStore.nextId()
        taskStore.save(Task(id = taskId, title = "write tests", createdAtMillis = 1L, updatedAtMillis = 1L))

        val reportTool = AndroidReportTool(taskStore, workLogStore, meetingStore, actionItemStore)
        val result = reportTool.execute(ToolCall(ToolId.REPORT, "daily_report")) as ToolResult.Success

        assertTrue(result.summary.contains("write tests"))
    }

    @Test
    fun anEmptyDailyReportNeverFabricatesContent() = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val workLogStore = AndroidWorkLogStore(context, logger)
        val meetingStore = AndroidMeetingNoteStore(context, logger)
        val actionItemStore = AndroidActionItemStore(context, logger)

        val reportTool = AndroidReportTool(taskStore, workLogStore, meetingStore, actionItemStore)
        val result = reportTool.execute(ToolCall(ToolId.REPORT, "daily_report")) as ToolResult.Success

        assertEquals("Nothing recorded for today yet.", result.summary)
    }
}
