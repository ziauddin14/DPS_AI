package com.softwaremine.dps.data.android.reminder

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.data.android.tool.AndroidReminderTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THROWAWAY diagnostic for the Reminder Reboot Survival investigation, not
 * permanent coverage. Split into schedule/check-only steps run as separate
 * `am instrument` invocations either side of a real `adb reboot`.
 */
@RunWith(AndroidJUnit4::class)
class RebootSurvivalDiagnosticTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    /** Run before reboot. Schedules through the real production path. */
    @Test
    fun scheduleBeforeReboot(): Unit = runBlocking {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val tool = AndroidReminderTool(scheduler, store)

        val triggerAt = System.currentTimeMillis() + TRIGGER_DELAY_MILLIS
        val created = tool.execute(
            ToolCall(
                ToolId.REMINDER,
                "create_reminder",
                mapOf("title" to TITLE, "time" to triggerAt.toString()),
            ),
        )
        assertTrue("Expected Success, got $created", created is ToolResult.Success)
        val reminderId = (created as ToolResult.Success).data["reminder_id"]!!.toInt()

        val stored = store.find(reminderId)
        assertNotNull("Reminder must be persisted before reboot", stored)

        // Verification-only, not a production fix: force this process's
        // pending SharedPreferences writes to reach disk before it exits.
        // The investigation found store.put()'s .apply() does not reliably
        // flush across a process boundary from a short-lived instrumentation
        // run — a real app process normally stays alive long enough for
        // Android's own QueuedWork hooks to do this. Not touching
        // ReminderStore.kt; this only makes THIS manual verification
        // deterministic rather than dependent on that separate, unfixed issue.
        context.getSharedPreferences("dps_reminders", Context.MODE_PRIVATE).edit().commit()

        println("REBOOT_DIAG scheduled id=$reminderId triggerAt=$triggerAt now=${System.currentTimeMillis()} stored=$stored exact=${scheduler.canScheduleExact()}")
    }

    /** Run after reboot (and, if applicable, after unlocking), once past the trigger time. */
    @Test
    fun checkAfterReboot() {
        val store = ReminderStore(context, logger)
        val all = store.all()
        println("REBOOT_DIAG post-reboot store contents: $all")

        val nm = context.getSystemService(NotificationManager::class.java)
        val active = nm.activeNotifications.map { it.id to it.notification.extras.getString("android.title") }
        println("REBOOT_DIAG post-reboot active notifications: $active")
    }

    /** Cleanup pass — safe to run regardless of what checkAfterReboot found. */
    @Test
    fun cleanup() {
        val scheduler = ReminderScheduler(context, logger, AndroidPermissionManager(context, logger))
        val store = ReminderStore(context, logger)
        val nm = context.getSystemService(NotificationManager::class.java)

        val leftover = store.all().filter { it.title == TITLE }
        println("REBOOT_DIAG cleanup: removing ${leftover.size} test reminder(s): ${leftover.map { it.id }}")
        leftover.forEach { reminder ->
            nm.cancel(reminder.id)
            scheduler.cancel(reminder.id)
            store.remove(reminder.id)
        }

        // Belt-and-suspenders: the store-based lookup above depends on the
        // record having reached disk, which the investigation found is not
        // reliable across a process boundary. Directly cancel every id this
        // diagnostic could plausibly have used, store-independent.
        (KNOWN_TEST_IDS).forEach { id ->
            nm.cancel(id)
            scheduler.cancel(id)
            store.remove(id)
        }
    }

    private companion object {
        const val TITLE = "Reboot survival diagnostic"

        // Long enough to comfortably outlast a real `adb reboot` cycle
        // (observed ~30-90s on this device) plus time to gather pre-reboot
        // evidence before issuing the reboot command.
        const val TRIGGER_DELAY_MILLIS = 4 * 60 * 1000L

        // Every id nextId() could have returned across this investigation's
        // runs, given the observed (but unflushed-to-disk) counter value of
        // 1005 going in. Generous range costs nothing and guarantees nothing
        // is missed regardless of how many times nextId() was actually called.
        val KNOWN_TEST_IDS = 1000..1020
    }
}
