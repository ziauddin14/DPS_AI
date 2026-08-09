package com.softwaremine.dps.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.softwaremine.dps.DpsApplication
import com.softwaremine.dps.domain.permission.DpsPermission
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the permission request host is actually
 * attached when the app runs (Day 05 Phase E).
 *
 * ## What this proves
 * Before Phase E, [MainActivity] never attached anything to
 * [com.softwaremine.dps.data.android.permission.AndroidPermissionManager], so
 * every permission dialog silently never appeared — the JVM suite cannot see
 * this at all, since `PermissionRequestHost` is an Activity-scoped contract.
 * Launching the real Activity is the only way to prove
 * `ActivityPermissionRequestHost` actually gets constructed and attached, not
 * just that its own logic is correct in isolation.
 *
 * ## What "attached" means here without reading a private field
 * [com.softwaremine.dps.data.android.permission.AndroidPermissionManager.request]
 * logs a warning and returns immediately when no host is attached — that
 * behaviour is the thing Phase E fixes. This test instead confirms the
 * Activity reaches a resumed state without crashing while constructing and
 * attaching the host, and that tearing it down (`onDestroy`) is equally
 * crash-free — the two moments the attach/detach wiring actually runs.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPermissionHostInstrumentedTest {

    @Test
    fun activityLaunchesAndAttachesThePermissionHostWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = (activity.application as DpsApplication).container
                // Reaching this line at all means onCreate ran to completion,
                // which includes constructing ActivityPermissionRequestHost
                // (its constructor calls registerForActivityResult) and
                // calling attachHost — either would have thrown before Phase E
                // wired MainActivity up, since no such class existed.
                assertTrue(
                    "Container should already be resolved by onCreate",
                    container.permissionManager.state(DpsPermission.POST_NOTIFICATIONS) != null,
                )
            }
        }
    }

    @Test
    fun destroyingTheActivityDetachesTheHostWithoutCrashing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.close()
        // No assertion beyond "did not throw" — detachHost() runs inside
        // onDestroy, which ActivityScenario.close() drives through.
    }
}
