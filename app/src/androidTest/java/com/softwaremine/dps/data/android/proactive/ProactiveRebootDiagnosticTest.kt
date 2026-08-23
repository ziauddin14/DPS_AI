package com.softwaremine.dps.data.android.proactive

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THROWAWAY diagnostic for M4-A reboot behaviour, not permanent coverage —
 * mirrors [com.softwaremine.dps.data.android.reminder.RebootSurvivalDiagnosticTest]'s
 * own split-phase-around-a-real-`adb reboot` shape exactly.
 *
 * The brief for M4-A is explicit: do not add a custom `BOOT_COMPLETED`
 * receiver merely because the reminder subsystem has one — only if actual
 * testing proves WorkManager's own reboot-recovery insufficient. This is
 * that test.
 *
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveRebootDiagnosticTest#scheduleBeforeReboot \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb reboot
 * # wait for the device to finish booting
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveRebootDiagnosticTest#checkAfterReboot \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 *
 * adb shell am instrument -w -r \
 *   -e class com.softwaremine.dps.data.android.proactive.ProactiveRebootDiagnosticTest#cleanup \
 *   com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ProactiveRebootDiagnosticTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Run before reboot. Enqueues through the real production path ([ProactiveCheckWorker.schedule]). */
    @Test
    fun scheduleBeforeReboot() {
        ProactiveCheckWorker.schedule(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
            .get()
        println("PROACTIVE_REBOOT_DIAG scheduled infos=$infos")
    }

    /** Run after reboot, once the device has finished booting. */
    @Test
    fun checkAfterReboot() {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
            .get()
        val live = infos.filter { it.state != WorkInfo.State.CANCELLED }
        println("PROACTIVE_REBOOT_DIAG post-reboot infos=$infos live=${live.size}")
    }

    /** Cleanup pass — safe to run regardless of what checkAfterReboot found. */
    @Test
    fun cleanup() {
        WorkManager.getInstance(context).cancelUniqueWork(ProactiveCheckWorker.UNIQUE_WORK_NAME)
        ProactiveStateStore.create(context, com.softwaremine.dps.core.logging.AndroidDpsLogger()).clear()
    }
}
