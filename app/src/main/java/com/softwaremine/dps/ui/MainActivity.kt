package com.softwaremine.dps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.softwaremine.dps.DpsApplication
import com.softwaremine.dps.data.android.permission.ActivityPermissionRequestHost
import com.softwaremine.dps.ui.chat.ChatScreen
import com.softwaremine.dps.ui.chat.ChatViewModel
import com.softwaremine.dps.ui.theme.DpsTheme
import kotlinx.coroutines.launch

/**
 * The single Activity.
 *
 * ## Purpose
 * Hosts the Compose hierarchy and resolves the [ChatViewModel] from the
 * application's object graph.
 *
 * ## Why a single Activity
 * Navigation will live entirely in Compose. A single Activity keeps process and
 * window state in one place, which matters more than usual here: the AI's model
 * is process-scoped and expensive, and multiple Activities would create several
 * plausible-looking places to attach its lifecycle. Exactly one is correct, so
 * there should be exactly one candidate.
 *
 * ## Dependencies
 * [DpsApplication] for the graph, [ChatScreen], [DpsTheme].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as DpsApplication).container

        // TEMPORARY M9 real-device diagnostic — run once via
        // `adb shell am start -n .../.ui.MainActivity -e m9_debug_action run_automation`,
        // observed via logcat, then removed. am instrument's own target
        // process was found (real-device evidence) to never receive the
        // accessibility service bind, so this drives AndroidAutomationEngine
        // through a genuine, normally-launched process instead.
        if (intent?.getStringExtra("m9_debug_action") == "run_automation") {
            lifecycleScope.launch {
                val tag = "M9Debug"
                val engine = container.automationEngine
                val pkg = "com.softwaremine.dps.automationtarget"
                val descriptor = com.softwaremine.dps.domain.automation.ElementDescriptor(
                    resourceId = "$pkg:id/automation_target_button",
                )
                android.util.Log.i(tag, "openApp -> ${engine.openApp(pkg)}")
                android.util.Log.i(tag, "findElement -> ${engine.findElement(descriptor)}")
                android.util.Log.i(tag, "observeAndVerify(before tap) -> ${engine.observeAndVerify(descriptor, "Tapped")}")
                android.util.Log.i(tag, "tap -> ${engine.tap(descriptor)}")
                android.util.Log.i(tag, "observeAndVerify(after tap) -> ${engine.observeAndVerify(descriptor, "Tapped")}")
                android.util.Log.i(tag, "findElement(missing) -> ${engine.findElement(com.softwaremine.dps.domain.automation.ElementDescriptor(resourceId = "$pkg:id/does_not_exist"))}")
            }
        }

        // Must be constructed here, before onStart(): registerForActivityResult
        // requires it (developer.android.com/training/permissions/requesting).
        // Without this, AndroidPermissionManager.request() has no host attached
        // and every permission dialog silently never appears (Day 05 Phase E).
        val permissionHost = ActivityPermissionRequestHost(this, container.logger)
        container.permissionManager.attachHost(permissionHost)

        val viewModel = ViewModelProvider(
            owner = this,
            factory = ChatViewModel.Factory(container.sessionManager, container.voiceModeController),
        )[ChatViewModel::class.java]

        setContent {
            DpsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        val container = (application as DpsApplication).container
        container.permissionManager.detachHost()
        super.onDestroy()
    }
}
