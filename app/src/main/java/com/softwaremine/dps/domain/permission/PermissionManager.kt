package com.softwaremine.dps.domain.permission

/**
 * The single authority on permission state.
 *
 * ## Purpose
 * Development Rule 5 — one source of truth. Permission logic scattered across
 * tools is how an app ends up checking `READ_CALENDAR` three different ways,
 * two of which forget that `POST_NOTIFICATIONS` does not exist below API 33.
 * Every question about permissions goes through this interface.
 *
 * ## Responsibilities
 * - Report the state of a permission ([state], [states]).
 * - Answer whether a set of permissions is satisfied ([missing]).
 * - Request permissions, where the platform allows it ([request]).
 *
 * ## Explicit non-responsibilities
 * It does not decide *whether* a permission ought to be requested. That is a
 * product decision belonging to the layer that knows what the user is trying to
 * do, and Human Confirmation (`development_rule.md`, Rule 8) means it is
 * ultimately the user's decision anyway.
 *
 * It also never navigates to system settings on its own. Sending a user out of
 * the app is a visible action requiring their consent, so the manager reports
 * [PermissionState.REQUIRES_SETTINGS] and the caller asks.
 *
 * ## Dependencies
 * Domain permission types only. No Android — the implementation lives in the
 * Android layer.
 */
interface PermissionManager {

    /**
     * Current state of one permission.
     *
     * Must never throw. A query that cannot be answered returns
     * [PermissionState.UNKNOWN], which callers treat as not-granted.
     */
    fun state(permission: DpsPermission): PermissionState

    /** Current state of several permissions, evaluated together. */
    fun states(permissions: Set<DpsPermission>): Map<DpsPermission, PermissionState>

    /**
     * Permissions in [permissions] that are not currently usable.
     *
     * "Usable" includes [PermissionState.NOT_REQUIRED], so a permission absent
     * on this OS version is correctly treated as satisfied rather than missing.
     */
    fun missing(permissions: Set<DpsPermission>): Set<DpsPermission>

    /** `true` when every permission in [permissions] is usable. */
    fun allUsable(permissions: Set<DpsPermission>): Boolean = missing(permissions).isEmpty()

    /**
     * Requests [permissions], suspending until the user responds.
     *
     * Only [PermissionKind.RUNTIME] permissions can be requested this way.
     * [PermissionKind.SPECIAL_ACCESS] entries are returned as
     * [PermissionState.REQUIRES_SETTINGS] without a dialog, because the runtime
     * dialog genuinely cannot grant them — attempting it would appear to the
     * user as a request that silently did nothing.
     *
     * Requires a UI host to be attached ([PermissionRequestHost]); a request
     * made with no host resolves to current state rather than hanging.
     *
     * @return the state of every requested permission after the interaction.
     */
    suspend fun request(permissions: Set<DpsPermission>): Map<DpsPermission, PermissionState>
}

/**
 * Supplies the Activity-scoped machinery a permission dialog requires.
 *
 * ## Why this is separate from [PermissionManager]
 * Querying permission state needs only a `Context` and can happen anywhere.
 * *Requesting* needs a live Activity, because
 * `ActivityResultContracts.RequestMultiplePermissions` is registered against
 * one and `shouldShowRequestPermissionRationale` takes an `Activity`.
 *
 * Splitting them keeps [PermissionManager] usable from a background service or
 * the tool executor, where no Activity exists, while confining the
 * Activity-bound part to a small interface the UI implements and attaches for
 * as long as it is alive.
 *
 * ## Lifecycle
 * Implementations must detach on destroy. Holding an Activity reference past
 * its lifetime leaks the whole view hierarchy.
 */
interface PermissionRequestHost {

    /**
     * Launches the system dialog for [androidPermissions] and suspends until the
     * user responds.
     *
     * Takes raw Android permission strings rather than [DpsPermission] because
     * the mapping is the Android layer's concern and this interface is the
     * boundary at which the domain has already handed off.
     *
     * @return granted state keyed by the same strings.
     */
    suspend fun requestRuntimePermissions(
        androidPermissions: List<String>,
    ): Map<String, Boolean>

    /**
     * Whether the platform advises showing a rationale before asking again.
     *
     * Returns `false` both when the permission was never requested and when it
     * is permanently denied; the two are told apart by whether the app has
     * recorded asking before.
     */
    fun shouldShowRationale(androidPermission: String): Boolean
}
