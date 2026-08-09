package com.softwaremine.dps.data.android.permission

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionKind
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionRequestHost
import com.softwaremine.dps.domain.permission.PermissionState

/**
 * The Android [PermissionManager].
 *
 * ## Purpose
 * The only class in DPS that asks Android about permissions. Every other layer
 * consults [PermissionManager] and sees domain types.
 *
 * ## APIs used — all verified against official documentation before use
 * | API | Source | Purpose |
 * |---|---|---|
 * | `ContextCompat.checkSelfPermission(Context, String)` | `androidx.core:core` | grant state; returns `PackageManager.PERMISSION_GRANTED` / `_DENIED` |
 * | `ActivityCompat.shouldShowRequestPermissionRationale(Activity, String)` | `androidx.core:core` | via [PermissionRequestHost] |
 * | `AlarmManager.canScheduleExactAlarms()` | API 31+ | special-access state for exact alarms |
 *
 * ## The never-asked versus permanently-denied problem
 * `shouldShowRequestPermissionRationale()` returns `false` in two unrelated
 * situations: the permission has never been requested, and the user has denied
 * it firmly enough that the system will no longer show the dialog. Android
 * provides no API distinguishing them.
 *
 * The difference matters in both directions. Treat never-asked as permanently
 * denied and DPS will never request a permission the user would have granted.
 * Treat permanently-denied as askable and DPS will launch a dialog the system
 * discards, so the user taps and nothing happens — repeatedly.
 *
 * Resolved by recording, in [SharedPreferences], whether each permission has
 * ever been requested. Combined with the rationale flag:
 *
 * | Asked before | Rationale | State |
 * |---|---|---|
 * | no | – | [PermissionState.DENIED] (never asked; ask normally) |
 * | yes | `true` | [PermissionState.DENIED] (denied once; explain, then ask) |
 * | yes | `false` | [PermissionState.PERMANENTLY_DENIED] (settings only) |
 *
 * ## Special access is not a runtime permission
 * `SCHEDULE_EXACT_ALARM` carries normal protection and is granted through a
 * settings screen. `checkSelfPermission` is the wrong question; the state comes
 * from `AlarmManager.canScheduleExactAlarms()`, and no dialog can grant it.
 * Handled as [PermissionKind.SPECIAL_ACCESS] rather than being forced through
 * the runtime path, where it would appear to fail for no reason.
 *
 * ## Lifecycle
 * Holds only the application [Context]. The Activity-scoped part lives behind
 * [PermissionRequestHost], attached and detached by the UI, so this class never
 * outlives or leaks an Activity.
 *
 * ## Dependencies
 * `androidx.core`, `android.app.AlarmManager`, [DpsLogger].
 */
class AndroidPermissionManager(
    private val context: Context,
    private val logger: DpsLogger,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : PermissionManager {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var host: PermissionRequestHost? = null

    /**
     * Attaches the Activity-scoped request host.
     *
     * The UI must call [detachHost] on destroy; retaining an Activity beyond
     * its lifetime leaks the entire view hierarchy.
     */
    fun attachHost(host: PermissionRequestHost) {
        this.host = host
        logger.d(TAG, "Permission request host attached.")
    }

    /** Detaches the host. Safe to call when none is attached. */
    fun detachHost() {
        host = null
        logger.d(TAG, "Permission request host detached.")
    }

    override fun state(permission: DpsPermission): PermissionState = try {
        when {
            // Absent on this OS version, so the capability is unrestricted.
            // Reporting DENIED here would block a feature the platform allows.
            !permission.existsOn(apiLevel) -> PermissionState.NOT_REQUIRED

            permission.kind == PermissionKind.SPECIAL_ACCESS ->
                specialAccessState(permission)

            else -> runtimeState(permission)
        }
    } catch (throwable: Throwable) {
        // Never propagate. An unanswerable query is UNKNOWN, which callers
        // treat as not-granted — failing closed rather than open.
        logger.w(TAG, "Could not determine state of $permission", throwable)
        PermissionState.UNKNOWN
    }

    override fun states(permissions: Set<DpsPermission>): Map<DpsPermission, PermissionState> =
        permissions.associateWith { state(it) }

    override fun missing(permissions: Set<DpsPermission>): Set<DpsPermission> =
        permissions.filterNot { state(it).isUsable }.toSet()

    override suspend fun request(
        permissions: Set<DpsPermission>,
    ): Map<DpsPermission, PermissionState> {
        if (permissions.isEmpty()) return emptyMap()

        val currentHost = host
        if (currentHost == null) {
            // No Activity attached — a background caller, or the UI is gone.
            // Returning current state is honest and terminates; suspending
            // forever waiting for a host that may never arrive is not.
            logger.w(TAG, "request() with no host attached; returning current state.")
            return states(permissions)
        }

        // Special access cannot be granted by the runtime dialog. Launching one
        // would do nothing visible, so it is reported for the caller to route
        // to settings with the user's consent.
        val (special, runtime) = permissions.partition {
            it.kind == PermissionKind.SPECIAL_ACCESS
        }

        val requestable = runtime.filter { it.existsOn(apiLevel) && !state(it).isUsable }

        if (requestable.isNotEmpty()) {
            val androidNames = requestable.map { AndroidPermissionMapping.androidName(it) }
            // Recorded before launching, not after: if the process dies while
            // the dialog is up, the request still happened, and forgetting that
            // would misclassify a permanent denial as never-asked forever after.
            markRequested(requestable)

            logger.i(TAG, "Requesting ${androidNames.size} runtime permission(s).")
            runCatching { currentHost.requestRuntimePermissions(androidNames) }
                .onFailure { logger.e(TAG, "Permission request failed", it) }
        }

        return buildMap {
            special.forEach { put(it, state(it)) }
            runtime.forEach { put(it, state(it)) }
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun runtimeState(permission: DpsPermission): PermissionState {
        val androidName = AndroidPermissionMapping.androidName(permission)
        val granted = ContextCompat.checkSelfPermission(context, androidName) ==
            PackageManager.PERMISSION_GRANTED

        if (granted) return PermissionState.GRANTED

        val askedBefore = hasBeenRequested(permission)
        if (!askedBefore) {
            // Never asked. The dialog will appear normally.
            return PermissionState.DENIED
        }

        val currentHost = host
            // Without an Activity the rationale flag cannot be read. Reporting
            // DENIED is the safer error: it permits another attempt, whereas a
            // wrong PERMANENTLY_DENIED would strand the permission for good.
            ?: return PermissionState.DENIED

        return if (currentHost.shouldShowRationale(androidName)) {
            PermissionState.DENIED
        } else {
            PermissionState.PERMANENTLY_DENIED
        }
    }

    /**
     * State of a special-access permission.
     *
     * Only `SCHEDULE_EXACT_ALARM` exists today. Each special permission has its
     * own query API, so this is a `when` rather than a shared code path — there
     * is no general "check special access" call to generalise over.
     */
    private fun specialAccessState(permission: DpsPermission): PermissionState =
        when (permission) {
            DpsPermission.SCHEDULE_EXACT_ALARM -> {
                val alarmManager = context.getSystemService<AlarmManager>()
                when {
                    alarmManager == null -> PermissionState.UNKNOWN
                    // canScheduleExactAlarms() was added in API 31. Below that
                    // exact alarms need no special grant.
                    apiLevel < ANDROID_12 -> PermissionState.NOT_REQUIRED
                    alarmManager.canScheduleExactAlarms() -> PermissionState.GRANTED
                    else -> PermissionState.REQUIRES_SETTINGS
                }
            }

            else -> PermissionState.UNKNOWN
        }

    private fun hasBeenRequested(permission: DpsPermission): Boolean =
        prefs.getBoolean(requestedKey(permission), false)

    private fun markRequested(permissions: List<DpsPermission>) {
        prefs.edit().apply {
            permissions.forEach { putBoolean(requestedKey(it), true) }
        }.apply()
    }

    private fun requestedKey(permission: DpsPermission): String = "requested_${permission.name}"

    private companion object {
        const val TAG = "PermissionManager"
        const val PREFS_NAME = "dps_permissions"
        const val ANDROID_12 = 31
    }
}
