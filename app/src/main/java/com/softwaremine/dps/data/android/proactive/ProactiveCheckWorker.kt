package com.softwaremine.dps.data.android.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.notification.DpsNotificationChannel
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Periodic, deterministic overdue-task check (M4-A).
 *
 * ## The whole pipeline, and no more
 * ```
 * READ (TaskRepository.all(), ProactiveStateStore.load())
 *   → EVALUATE (ProactiveRuleEvaluator.overdueTasks, pure)
 *   → NOTIFY (NotificationPresenter.post, existing infrastructure)
 *   → RECORD (ProactiveStateStore.save, only for what was actually posted)
 * ```
 * Nothing here calls [com.softwaremine.dps.ai.intent.ToolOrchestrator],
 * [com.softwaremine.dps.data.android.tool.tool executors][com.softwaremine.dps.domain.tool.ToolExecutor],
 * `SecretaryOrchestrator.handle`, `AiSessionManager`, or anything that loads
 * the GGUF model. This class has no dependency, direct or transitive, on the
 * AI subsystem at all — it reads two Android-local stores and posts a
 * notification, exactly like [com.softwaremine.dps.data.android.reminder.ReminderReceiver]
 * already does for a fired alarm.
 *
 * ## Why this needs no `AiContainer` wiring
 * `CoroutineWorker` is instantiated by WorkManager's default `WorkerFactory`
 * via reflection (a bare `(Context, WorkerParameters)` constructor) — there
 * is nowhere to inject into, the same situation
 * [ReminderReceiver][com.softwaremine.dps.data.android.reminder.ReminderReceiver]'s
 * own doc already documents for `BroadcastReceiver`. A custom
 * `Configuration.Provider`/`WorkerFactory` would exist solely to route two
 * cheap, stateless Android-local stores and a presenter through
 * `AiContainer` — more moving parts to reach exactly the same objects this
 * class already constructs directly, cheaply and statelessly.
 *
 * ## Why `runCheck` is a separate, `internal` function
 * `doWork()` itself is a thin adapter to WorkManager's `Result` type; the
 * actual pipeline is one plain suspend function so it can be exercised
 * directly by an instrumented test against real on-device state, without
 * needing the `androidx.work:work-testing` artifact this project does not
 * otherwise depend on.
 */
class ProactiveCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        runCheck(applicationContext, System.currentTimeMillis(), AndroidDpsLogger())

    companion object {
        private const val TAG = "ProactiveCheckWorker"

        /** Keeps overdue-task notification ids clear of every existing id range (`ReminderStore`/`AndroidTaskStore` both cap at 1_000_000). */
        private const val NOTIFICATION_ID_OFFSET = 2_000_000

        /** Markers older than this are dropped on every run, so [ProactiveState] stays bounded rather than growing forever. */
        private const val MARKER_RETENTION_DAYS = 3L

        private val ZONE: ZoneId = ZoneId.systemDefault()

        /** Identifies the one periodic check across every enqueue attempt — see [schedule]. */
        const val UNIQUE_WORK_NAME = "com.softwaremine.dps.proactive.OVERDUE_TASK_CHECK"

        /**
         * The periodic interval.
         *
         * 30 minutes — comfortably above `PeriodicWorkRequest`'s enforced
         * 15-minute minimum, and far finer than an overdue-task check needs:
         * a task becoming overdue has no second-level precision requirement,
         * unlike [com.softwaremine.dps.data.android.reminder.ReminderScheduler]'s
         * exact, user-facing alarms, which this deliberately does not touch.
         */
        private const val CHECK_INTERVAL_MINUTES = 30L

        /**
         * Enqueues the periodic check, once. Safe to call on every app start.
         *
         * [ExistingPeriodicWorkPolicy.KEEP] rather than `REPLACE`: if
         * [UNIQUE_WORK_NAME] is already registered — which it will be on
         * every app start after the first — the existing schedule (and its
         * phase) is left exactly as it is. `REPLACE` would silently reset
         * the periodic timer on every single app launch, which is both
         * pointless work and the thing that actually risks duplicate-feeling
         * behaviour, not a defence against it.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(
                CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * The full READ → EVALUATE → NOTIFY → RECORD pipeline, against real
         * or fake collaborators depending on the caller — production
         * ([doWork]) always passes the real, `Context`-backed ones;
         * instrumented tests call this directly against the real device
         * without needing WorkManager to actually schedule anything.
         *
         * Never persists a marker for a task whose notification was not
         * actually handed to the system — see
         * [NotificationPresenter.post]'s own `Boolean` return and this
         * function's own ordering below: `save` happens once, after every
         * `post` call, with only the successfully-posted ids folded in.
         */
        internal suspend fun runCheck(
            context: Context,
            nowMillis: Long,
            logger: DpsLogger,
            taskRepository: TaskRepository = AndroidTaskStore(context, logger),
            stateStore: ProactiveStateStore = ProactiveStateStore.create(context, logger),
            presenter: NotificationPresenter = NotificationPresenter(context, logger),
        ): Result = runCatching {
            val state = stateStore.load()
            val dateKey = ProactiveRuleEvaluator.dateKeyFor(nowMillis, ZONE)
            val alreadyNotifiedIds = state.notifiedMarkers
                .mapNotNull { ProactiveRuleEvaluator.taskIdIfMarkerMatches(it, dateKey) }
                .toSet()

            val eligible = ProactiveRuleEvaluator.overdueTasks(
                tasks = taskRepository.all(),
                nowMillis = nowMillis,
                alreadyNotifiedTaskIds = alreadyNotifiedIds,
            )

            val cutoffDateKey = ProactiveRuleEvaluator.dateKeyFor(
                nowMillis - TimeUnit.DAYS.toMillis(MARKER_RETENTION_DAYS),
                ZONE,
            )
            var markers = ProactiveRuleEvaluator.pruneMarkersOlderThan(state.notifiedMarkers, cutoffDateKey)

            eligible.forEach { task ->
                if (postOverdueNotification(presenter, task)) {
                    markers = markers + ProactiveRuleEvaluator.markerKey(task.id, dateKey)
                    logger.i(TAG, "Posted overdue notification for task id=${task.id}")
                } else {
                    logger.w(TAG, "Could not post overdue notification for task id=${task.id}; not marking as notified")
                }
            }

            stateStore.save(state.copy(lastCheckedAtMillis = nowMillis, notifiedMarkers = markers))
            Result.success()
        }.getOrElse {
            logger.e(TAG, "Proactive overdue check failed", it)
            Result.retry()
        }

        /**
         * Reuses [NotificationPresenter] — the one class every notification
         * DPS shows already goes through — rather than a second framework.
         * [DpsNotificationChannel.ASSISTANT], not `.REMINDERS`: that
         * channel's own description is "reminders you asked DPS to set,"
         * which this notification is not — DPS decided to send it, the user
         * never asked for this specific one. Tap behaviour is
         * [NotificationPresenter]'s existing default: opens the app via
         * `getLaunchIntentForPackage`, nothing more — no auto-complete,
         * no auto-edit, no tool call.
         */
        private fun postOverdueNotification(presenter: NotificationPresenter, task: Task): Boolean =
            presenter.post(
                id = NOTIFICATION_ID_OFFSET + task.id,
                channel = DpsNotificationChannel.ASSISTANT,
                title = "Task overdue",
                body = "\"${task.title}\" is overdue.",
            )
    }
}
