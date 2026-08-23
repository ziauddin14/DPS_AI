package com.softwaremine.dps.data.android.proactive

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * M4-A real Android process-death validation, mirroring M3-D's
 * `ProcessDeathPersistenceInstrumentedTest` exactly: two `@Test` methods run
 * as two separate `am instrument` invocations with a genuine
 * `adb shell am force-stop` between them — never a same-process trick.
 *
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveProcessDeathInstrumentedTest#phase1CreateOverdueTaskRunCheckAndScheduleBeforeProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am force-stop com.softwaremine.dps
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveProcessDeathInstrumentedTest#phase2VerifyMarkerSurvivedAndNoDuplicateNotificationAfterProcessDeath \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * As with M3-D, `am force-stop` genuinely terminates the OS process while
 * leaving on-disk `SharedPreferences` untouched. Running both methods in one
 * combined invocation still compiles and passes but proves nothing about a
 * real process boundary — see M3-D's own completion doc for the same caveat.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveProcessDeathInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    // -----------------------------------------------------------------
    // Phase 1 — run first, against a fresh process
    // -----------------------------------------------------------------

    @Test
    fun phase1CreateOverdueTaskRunCheckAndScheduleBeforeProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-D process-death overdue check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)

        val notificationId = 2_000_000 + overdue.id
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "The real notification must be posted before the process dies",
            notificationId in nm.activeNotifications.map { it.id },
        )

        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, ZoneId.systemDefault())
        val marker = ProactiveRuleEvaluator.markerKey(overdue.id, dateKey)
        assertTrue(marker in stateStore.load().notifiedMarkers)

        // Also register the periodic work, so phase 2 can confirm it survived
        // the kill without this process having to do anything more.
        ProactiveCheckWorker.schedule(context)

        // M2-D/M3-D's own finding, reused here: SharedPreferences.Editor.apply()
        // is asynchronous, and a process that exits immediately after the
        // last apply() call can lose the write before it reaches disk.
        delay(1500)
    }

    // -----------------------------------------------------------------
    // Phase 2 — run second, after `adb shell am force-stop` between the two
    // -----------------------------------------------------------------

    @Test
    fun phase2VerifyMarkerSurvivedAndNoDuplicateNotificationAfterProcessDeath(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)

        // 1) The marker phase1 persisted must still be on disk — a fresh
        // ProactiveStateStore, in a genuinely new process, reading it back.
        val now = System.currentTimeMillis()
        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, ZoneId.systemDefault())
        val restoredState = stateStore.load()
        val overdueTask = taskStore.all().singleOrNull { it.title == "M4-D process-death overdue check" }
        assertNotNull("The overdue task itself must also have survived (TaskStore is unrelated to this test but must not have lost it)", overdueTask)
        val marker = ProactiveRuleEvaluator.markerKey(overdueTask!!.id, dateKey)
        assertTrue(
            "The notification marker must survive real process death",
            marker in restoredState.notifiedMarkers,
        )

        val notificationId = 2_000_000 + overdueTask.id

        try {
            // 2) Running the check again, in this fresh process, must not
            // re-post the notification — the restored marker must still work.
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)

            val nm = context.getSystemService(NotificationManager::class.java)
            assertFalse(
                "A restored marker must still suppress a duplicate notification after process death",
                notificationId in nm.activeNotifications.map { it.id },
            )

            // 3) The periodic work phase1 scheduled must also have survived
            // the kill, still registered exactly once.
            val workManager = WorkManager.getInstance(context)
            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            assertEquals(
                "Exactly one unique periodic work must still be registered after process death, got $infos",
                1,
                infos.count { it.state != WorkInfo.State.CANCELLED },
            )
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            taskStore.delete(overdueTask.id)
            stateStore.clear()
            WorkManager.getInstance(context).cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }
}
