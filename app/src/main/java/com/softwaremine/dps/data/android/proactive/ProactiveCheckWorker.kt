package com.softwaremine.dps.data.android.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.notification.DpsNotificationChannel
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
import com.softwaremine.dps.data.android.productivity.AndroidTaskStore
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator
import com.softwaremine.dps.domain.proactive.UpcomingEventOccurrence
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Periodic, deterministic overdue-task and upcoming-event check (M4-A, M4-B).
 *
 * ## The whole pipeline, and no more
 * ```
 * GATE (PersistentPreferenceStore.load().proactiveAssistantEnabled — M4-C)
 *   → READ (TaskRepository.all(), CalendarWriter.findUpcomingInstances(), ProactiveStateStore.load())
 *   → EVALUATE (ProactiveRuleEvaluator.overdueTasks/upcomingEvents, both pure)
 *   → NOTIFY (NotificationPresenter.post, existing infrastructure)
 *   → RECORD (ProactiveStateStore.save, only for what was actually posted)
 * ```
 *
 * ## The M4-C gate
 * The very first thing [runCheck] does is load [com.softwaremine.dps.domain.preferences.UserPreferences]
 * and check [com.softwaremine.dps.domain.preferences.UserPreferences.proactiveAssistantEnabled].
 * When `false`, it returns [Result.success] immediately — before
 * [ProactiveStateStore.load], before any task or calendar read, before any
 * notification. This is deliberately the *only* change M4-C makes to this
 * class: the M4-A/M4-B pipeline below it is untouched, byte-for-byte, when
 * the preference is `true` (the default — see [com.softwaremine.dps.domain.preferences.UserPreferences]'s
 * own doc for why `true` is required). The periodic `WorkManager` schedule
 * itself is unaffected by this preference either way — see [schedule]'s own
 * doc for why enable/disable is a check inside the worker, not a
 * schedule/cancel call at the preference-write site (there isn't one yet).
 * M4-B (upcoming calendar events) is additive to M4-A (overdue tasks): the
 * two checks run independently within the same [runCheck] call, share only
 * the same [ProactiveStateStore] (via a marker-prefix convention, `task:`
 * vs `event:` — see [ProactiveRuleEvaluator.markerKey]/[ProactiveRuleEvaluator.eventOccurrenceMarkerKey])
 * and the same notification channel, and neither's eligibility rule reads
 * the other's data. Nothing about M4-A's own `overdueTasks` rule, ordering,
 * or marker format changed for M4-B.
 *
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

        /** Keeps upcoming-event notification ids clear of the overdue-task range ([NOTIFICATION_ID_OFFSET]) and every existing store's id range (M4-B). */
        private const val EVENT_NOTIFICATION_ID_OFFSET = 3_000_000

        /** Mirrors [com.softwaremine.dps.data.android.tool.AndroidCalendarTool]'s own `MAX_LISTED_EVENTS` bound, applied to this new read path instead (M4-B). */
        private const val MAX_UPCOMING_EVENTS = 20

        /**
         * How far ahead an occurrence must start to count as "upcoming" (M4-B).
         *
         * 60 minutes — comfortably wider than [CHECK_INTERVAL_MINUTES]'s own
         * ~30-minute cadence, which is itself inexact under Doze/App Standby.
         * A window barely wider than the gap between checks risks a
         * genuinely missed event; this one gives an occurrence at least one,
         * usually two, check cycles' worth of opportunity to be seen before
         * it starts. [ProactiveRuleEvaluator.upcomingEvents]'s own marker
         * check already prevents the same occurrence being re-notified on
         * that second cycle.
         */
        private val EVENT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(60)

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
         *
         * Unconditional regardless of [com.softwaremine.dps.domain.preferences.UserPreferences.proactiveAssistantEnabled]
         * (M4-C): this call only registers *when* the check runs, never
         * *whether* it does anything — that decision is [runCheck]'s own
         * first step. Registration and the preference are deliberately kept
         * as two independent facts rather than one derived from the other,
         * so there is exactly one source of truth for "is proactive
         * notification enabled" (the preference) and none of the
         * process-death/race risk of keeping `WorkManager`'s own scheduled
         * state in sync with it.
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
         * Never persists a marker for a task or event occurrence whose
         * notification was not actually handed to the system — see
         * [NotificationPresenter.post]'s own `Boolean` return and this
         * function's own ordering below: `save` happens once, after every
         * `post` call for both checks, with only the successfully-posted
         * markers folded in.
         *
         * The overdue-task check (M4-A) and the upcoming-event check (M4-B)
         * run independently in sequence within this one function — a
         * failure reading or evaluating one does not need to be prevented
         * from affecting the other by anything special, since a calendar
         * read failure already degrades to an empty occurrence list (see
         * below) rather than throwing.
         */
        internal suspend fun runCheck(
            context: Context,
            nowMillis: Long,
            logger: DpsLogger,
            taskRepository: TaskRepository = AndroidTaskStore(context, logger),
            stateStore: ProactiveStateStore = ProactiveStateStore.create(context, logger),
            presenter: NotificationPresenter = NotificationPresenter(context, logger),
            calendarWriter: CalendarWriter = CalendarWriter(context, logger),
            preferenceStore: PersistentPreferenceStore = PersistentPreferenceStore.create(context, logger),
        ): Result = runCatching {
            // M4-C gate: the smallest possible amount of work when disabled —
            // one preference read, then return. No task query, no calendar
            // query, no notification, no marker write, no ProactiveStateStore
            // read even. See this class's own "The M4-C gate" doc above.
            if (!preferenceStore.load().proactiveAssistantEnabled) {
                logger.d(TAG, "Proactive assistant disabled by user preference; skipping check")
                return@runCatching Result.success()
            }

            val state = stateStore.load()
            val dateKey = ProactiveRuleEvaluator.dateKeyFor(nowMillis, ZONE)

            val cutoffDateKey = ProactiveRuleEvaluator.dateKeyFor(
                nowMillis - TimeUnit.DAYS.toMillis(MARKER_RETENTION_DAYS),
                ZONE,
            )
            var markers = ProactiveRuleEvaluator.pruneMarkersOlderThan(state.notifiedMarkers, cutoffDateKey)

            // --- M4-A: overdue tasks (unchanged rule, unchanged marker format) ---
            val alreadyNotifiedTaskIds = markers
                .mapNotNull { ProactiveRuleEvaluator.taskIdIfMarkerMatches(it, dateKey) }
                .toSet()

            val eligibleTasks = ProactiveRuleEvaluator.overdueTasks(
                tasks = taskRepository.all(),
                nowMillis = nowMillis,
                alreadyNotifiedTaskIds = alreadyNotifiedTaskIds,
            )

            eligibleTasks.forEach { task ->
                if (postOverdueNotification(presenter, task)) {
                    markers = markers + ProactiveRuleEvaluator.markerKey(task.id, dateKey)
                    logger.i(TAG, "Posted overdue notification for task id=${task.id}")
                } else {
                    logger.w(TAG, "Could not post overdue notification for task id=${task.id}; not marking as notified")
                }
            }

            // --- M4-B: upcoming timed calendar occurrences ---
            // A missing/denied READ_CALENDAR permission, or no provider at
            // all, degrades to an empty occurrence list here rather than
            // throwing — the overdue-task check above must still have run
            // (it already has, by this point) and must not be retried
            // merely because calendar access is unavailable.
            val occurrences = when (
                val outcome = calendarWriter.findUpcomingInstances(nowMillis, nowMillis + EVENT_WINDOW_MILLIS, MAX_UPCOMING_EVENTS)
            ) {
                is CalendarWriter.InstanceQueryOutcome.Found -> outcome.occurrences.map { it.toDomain() }
                CalendarWriter.InstanceQueryOutcome.NoProvider -> {
                    logger.d(TAG, "No calendar provider; skipping the upcoming-event check")
                    emptyList()
                }
                is CalendarWriter.InstanceQueryOutcome.Failed -> {
                    logger.w(TAG, "Could not read upcoming calendar occurrences: ${outcome.reason}")
                    emptyList()
                }
            }

            val eligibleEvents = ProactiveRuleEvaluator.upcomingEvents(
                occurrences = occurrences,
                nowMillis = nowMillis,
                windowMillis = EVENT_WINDOW_MILLIS,
                alreadyNotifiedMarkers = markers,
                zone = ZONE,
            )

            eligibleEvents.forEach { occurrence ->
                if (postUpcomingEventNotification(presenter, occurrence)) {
                    markers = markers + ProactiveRuleEvaluator.eventOccurrenceMarkerKey(occurrence.sourceEventId, occurrence.beginMillis, ZONE)
                    logger.i(TAG, "Posted upcoming-event notification for event id=${occurrence.sourceEventId} begin=${occurrence.beginMillis}")
                } else {
                    logger.w(TAG, "Could not post upcoming-event notification for event id=${occurrence.sourceEventId}; not marking as notified")
                }
            }

            stateStore.save(state.copy(lastCheckedAtMillis = nowMillis, notifiedMarkers = markers))
            Result.success()
        }.getOrElse {
            logger.e(TAG, "Proactive check failed", it)
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

        /** Field-for-field mapping into the pure domain type — see [UpcomingEventOccurrence]'s own doc for why this crossing exists at all. */
        private fun CalendarWriter.EventOccurrence.toDomain(): UpcomingEventOccurrence =
            UpcomingEventOccurrence(
                sourceEventId = sourceEventId,
                beginMillis = beginMillis,
                endMillis = endMillis,
                title = title,
                allDay = allDay,
            )

        /**
         * Mirrors [postOverdueNotification]'s own reasoning exactly:
         * [DpsNotificationChannel.ASSISTANT], the existing shared
         * [NotificationPresenter], its existing default tap behaviour
         * (opens the app, nothing more). Deterministic string construction
         * only — no LLM involvement, and no wording that promises a
         * reminder was created or any action taken on the user's behalf.
         */
        private fun postUpcomingEventNotification(presenter: NotificationPresenter, occurrence: UpcomingEventOccurrence): Boolean =
            presenter.post(
                id = eventNotificationId(occurrence),
                channel = DpsNotificationChannel.ASSISTANT,
                title = "Upcoming event",
                body = "\"${occurrence.title}\" starts soon.",
            )

        /**
         * A deterministic per-occurrence notification id: the same
         * occurrence always maps to the same id (stable, though not
         * load-bearing for duplicate prevention — that is
         * [ProactiveRuleEvaluator.eventOccurrenceMarkerKey]'s job), while two
         * different occurrences reliably get different ids, so several
         * upcoming events eligible in the same run each show their own
         * notification instead of one overwriting another. Offset by
         * [EVENT_NOTIFICATION_ID_OFFSET] to stay clear of the overdue-task
         * notification range.
         */
        private fun eventNotificationId(occurrence: UpcomingEventOccurrence): Int {
            val hash = java.util.Objects.hash(occurrence.sourceEventId, occurrence.beginMillis)
            return EVENT_NOTIFICATION_ID_OFFSET + (hash and 0x00FFFFFF)
        }
    }
}
