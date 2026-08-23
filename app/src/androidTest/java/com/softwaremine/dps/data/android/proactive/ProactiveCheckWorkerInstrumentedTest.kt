package com.softwaremine.dps.data.android.proactive

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Real-device verification of [ProactiveCheckWorker]'s pipeline and
 * scheduling uniqueness (M4-A).
 *
 * ## Why [ProactiveCheckWorker.runCheck] is called directly, not through WorkManager
 * `PeriodicWorkRequest`'s minimum interval is 15 minutes — waiting for a real
 * periodic trigger to fire naturally is impractical inside a test and would
 * not add any correctness signal `runCheck` itself doesn't already provide.
 * What genuinely needs proving on-device is the pipeline's real behaviour
 * against real `AndroidTaskStore`/`ProactiveStateStore`/`NotificationPresenter`
 * state, and that scheduling never registers the periodic work twice — both
 * covered here without needing the `androidx.work:work-testing` artifact
 * this project does not otherwise depend on.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveCheckWorkerInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun activeNotificationIds(): Set<Int> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.map { it.id }.toSet()
    }

    // -----------------------------------------------------------------
    // Pipeline: READ → EVALUATE → NOTIFY → RECORD
    // -----------------------------------------------------------------

    @Test
    fun anOverdueTaskIsDetectedNotifiedAndMarked(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A overdue check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )

        try {
            val notificationId = 2_000_000 + overdue.id

            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)

            assertTrue("Expected Result.success(), got $result", result is androidx.work.ListenableWorker.Result.Success)
            assertTrue(
                "The real notification must actually be posted, got ${activeNotificationIds()}",
                notificationId in activeNotificationIds(),
            )

            val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, java.time.ZoneId.systemDefault())
            val expectedMarker = ProactiveRuleEvaluator.markerKey(overdue.id, dateKey)
            assertTrue(
                "The marker must be persisted only after the notification was posted",
                expectedMarker in stateStore.load().notifiedMarkers,
            )
            assertEquals(now, stateStore.load().lastCheckedAtMillis)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(2_000_000 + overdue.id)
            taskStore.delete(overdue.id)
            stateStore.clear()
        }
    }

    @Test
    fun runningTheCheckTwiceForTheSameOverdueTaskDoesNotDuplicateTheNotification(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        val overdue = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A duplicate-prevention check",
                status = TaskStatus.PENDING,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now - TimeUnit.HOURS.toMillis(2),
                updatedAtMillis = now - TimeUnit.HOURS.toMillis(2),
            ),
        )
        val notificationId = 2_000_000 + overdue.id

        try {
            ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            // The notification is cancelled deliberately between runs — the
            // property under test is that run #2 does not re-post it because
            // the task is already marked notified, not that the system
            // happens to still be showing run #1's notification.

            val secondRunResult = ProactiveCheckWorker.runCheck(
                context,
                now + TimeUnit.MINUTES.toMillis(30),
                logger,
                taskStore,
                stateStore,
            )

            assertTrue(secondRunResult is androidx.work.ListenableWorker.Result.Success)
            assertFalse(
                "A second run within the same notification cycle must not re-post the notification",
                notificationId in activeNotificationIds(),
            )

            val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, java.time.ZoneId.systemDefault())
            val markerCount = stateStore.load().notifiedMarkers.count {
                ProactiveRuleEvaluator.taskIdIfMarkerMatches(it, dateKey) == overdue.id
            }
            assertEquals("Exactly one marker must exist for this task on this date, not duplicated", 1, markerCount)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            taskStore.delete(overdue.id)
            stateStore.clear()
        }
    }

    @Test
    fun noEligibleTasksMeansNoNotificationAndNoMarker(): Unit = runBlocking {
        val taskStore = AndroidTaskStore(context, logger)
        val stateStore = ProactiveStateStore.create(context, logger)
        stateStore.clear()

        val now = System.currentTimeMillis()
        // A future task and a completed overdue task — neither eligible.
        val future = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A future task, not overdue",
                status = TaskStatus.PENDING,
                dueAtMillis = now + TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        val completed = taskStore.save(
            Task(
                id = taskStore.nextId(),
                title = "M4-A completed overdue task",
                status = TaskStatus.COMPLETED,
                dueAtMillis = now - TimeUnit.HOURS.toMillis(1),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        try {
            val beforeIds = activeNotificationIds()

            val result = ProactiveCheckWorker.runCheck(context, now, logger, taskStore, stateStore)

            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertEquals(
                "Neither an ineligible future task nor a completed one may post a notification",
                beforeIds,
                activeNotificationIds(),
            )
            assertTrue(stateStore.load().notifiedMarkers.isEmpty())
            assertEquals(now, stateStore.load().lastCheckedAtMillis)
        } finally {
            taskStore.delete(future.id)
            taskStore.delete(completed.id)
            stateStore.clear()
        }
    }

    // -----------------------------------------------------------------
    // Scheduling uniqueness
    // -----------------------------------------------------------------

    /**
     * Proves [ProactiveCheckWorker.schedule]'s `ExistingPeriodicWorkPolicy.KEEP`
     * choice actually prevents a duplicate registration — exactly the
     * behaviour [com.softwaremine.dps.DpsApplication.onCreate] relies on
     * running once per process start without ever accumulating a second
     * periodic worker.
     */
    @Test
    fun schedulingTwiceRegistersOnlyOneUniquePeriodicWork(): Unit = runBlocking {
        val workManager = WorkManager.getInstance(context)

        try {
            ProactiveCheckWorker.schedule(context)
            ProactiveCheckWorker.schedule(context)

            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            val nonCancelled = infos.filter { it.state != WorkInfo.State.CANCELLED }

            assertEquals(
                "Calling schedule() twice must register exactly one unique periodic work, got $infos",
                1,
                nonCancelled.size,
            )
        } finally {
            workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }

    @Test
    fun schedulingWithKeepPolicyPreservesAnAlreadyEnqueuedWorksIdentity(): Unit = runBlocking {
        val workManager = WorkManager.getInstance(context)

        try {
            // Enqueue directly with a distinguishable request first, mirroring
            // what a prior app launch would already have registered.
            val original = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(30, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(
                ProactiveCheckWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                original,
            ).result.get()

            ProactiveCheckWorker.schedule(context) // simulates a second app start

            val infos = workManager.getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME).get()
            assertEquals(1, infos.count { it.state != WorkInfo.State.CANCELLED })
            assertNotNull(
                "The originally enqueued work's id must survive a later schedule() call under KEEP",
                infos.firstOrNull { it.id == original.id },
            )
        } finally {
            workManager.cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        }
    }
}
