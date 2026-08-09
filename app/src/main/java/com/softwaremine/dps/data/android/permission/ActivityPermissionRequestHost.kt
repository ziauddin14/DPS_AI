package com.softwaremine.dps.data.android.permission

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.permission.PermissionRequestHost
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The real [PermissionRequestHost] — the piece that was missing entirely
 * before Day 05 Phase E, which is why no permission dialog has ever appeared
 * in the running app despite [AndroidPermissionManager] being able to check
 * and orchestrate requests since Phase A.
 *
 * ## APIs used — verified against official documentation before use
 * | API | Source | Purpose |
 * |---|---|---|
 * | `ComponentActivity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` | `androidx.activity:activity` | launches the system multi-permission dialog |
 * | `ActivityCompat.shouldShowRequestPermissionRationale(Activity, String)` | `androidx.core:core` | feeds [AndroidPermissionManager]'s never-asked-vs-permanently-denied logic |
 *
 * developer.android.com/training/permissions/requesting confirms the launcher
 * must be registered before the Activity reaches `STARTED` — this class is
 * therefore constructed, and its launcher registered, synchronously inside
 * [com.softwaremine.dps.ui.MainActivity.onCreate], never lazily on first use.
 * developer.android.com/reference/androidx/activity/result/ActivityResultCaller
 * confirms a helper class may call `registerForActivityResult` on the
 * `ComponentActivity` it holds, provided that registration still happens
 * before `STARTED` — which constructing it in `onCreate()` satisfies.
 *
 * ## Bridging a callback API to `suspend`
 * `ActivityResultCallback` is a plain lambda, not `suspend`-aware, so
 * [requestRuntimePermissions] wraps the one in-flight request in a
 * [CancellableContinuation]. Only one request is ever in flight in
 * practice — [AndroidPermissionManager.request] is called sequentially from a
 * single conversation turn — but a stray concurrent call is still resumed
 * (with an empty, all-denied-shaped result) rather than left to hang forever.
 *
 * ## Lifecycle
 * Constructed and attached in `MainActivity.onCreate`, detached in
 * `onDestroy` via `AndroidPermissionManager.detachHost()` — this class itself
 * holds the `ComponentActivity` for exactly that long and no longer.
 */
class ActivityPermissionRequestHost(
    private val activity: ComponentActivity,
    private val logger: DpsLogger,
) : PermissionRequestHost {

    private var pending: CancellableContinuation<Map<String, Boolean>>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        pending?.let { continuation ->
            pending = null
            if (continuation.isActive) continuation.resume(results)
        }
    }

    override suspend fun requestRuntimePermissions(
        androidPermissions: List<String>,
    ): Map<String, Boolean> {
        if (androidPermissions.isEmpty()) return emptyMap()

        pending?.let { stale ->
            logger.w(TAG, "A new permission request arrived while one was still in flight.")
            pending = null
            if (stale.isActive) stale.resume(emptyMap())
        }

        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            launcher.launch(androidPermissions.toTypedArray())
        }
    }

    override fun shouldShowRationale(androidPermission: String): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission)

    private companion object {
        const val TAG = "PermissionRequestHost"
    }
}
