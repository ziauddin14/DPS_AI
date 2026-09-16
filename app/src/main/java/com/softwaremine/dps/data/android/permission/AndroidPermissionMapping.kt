package com.softwaremine.dps.data.android.permission

import android.Manifest
import com.softwaremine.dps.domain.permission.DpsPermission

/**
 * The one place where a [DpsPermission] becomes an Android permission string.
 *
 * ## Purpose
 * Keeps `android.Manifest.permission` out of every other file. `domain/` cannot
 * import it at all, and duplicating these constants across tools is how an app
 * ends up with two spellings of the same permission and a bug that only appears
 * on one code path.
 *
 * ## Verification
 * Every constant below was checked against the official
 * `android.Manifest.permission` reference, including protection level and API
 * availability:
 *
 * | Permission | Protection | Notes |
 * |---|---|---|
 * | `READ_CALENDAR` / `WRITE_CALENDAR` | dangerous | runtime |
 * | `READ_CONTACTS` | dangerous | runtime |
 * | `POST_NOTIFICATIONS` | dangerous | **API 33+ only** |
 * | `SCHEDULE_EXACT_ALARM` | **special** | API 31+, settings-granted |
 * | `CALL_PHONE` / `READ_PHONE_STATE` | dangerous | runtime |
 * | `RECORD_AUDIO` | dangerous | runtime (Day 07) |
 *
 * `Manifest.permission.POST_NOTIFICATIONS` is a compile-time constant, so
 * referencing it is safe on any device; what varies is whether the *system*
 * recognises it, which is why [DpsPermission.minApiLevel] gates the check
 * rather than the reference itself.
 */
internal object AndroidPermissionMapping {

    /**
     * The Android permission string for [permission], or `null` when none
     * exists.
     *
     * Total by construction — [DpsPermission] is a closed enum, so adding a case
     * without a mapping fails to compile rather than at runtime.
     *
     * ## Why this returns `String?` (M9)
     * Every permission before [DpsPermission.AUTOMATION_ACCESSIBILITY] has a
     * real `android.permission.*` constant, even the special-access
     * [DpsPermission.SCHEDULE_EXACT_ALARM] — it is never actually passed to
     * `checkSelfPermission`/`requestPermissions` (see
     * [com.softwaremine.dps.data.android.permission.AndroidPermissionManager]'s
     * own `specialAccessState()`/`request()`, neither of which calls this
     * function for a [com.softwaremine.dps.domain.permission.PermissionKind.SPECIAL_ACCESS]
     * entry), but the string itself genuinely exists. Accessibility-service
     * enablement has no equivalent string at all — it is not modeled as a
     * held Android permission, only as membership in
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — so `null` here is
     * the honest answer, not a placeholder. Every existing caller already
     * only reaches this function for `RUNTIME`-kind permissions, so `null`
     * is never actually produced for any permission this codebase requests
     * through the runtime dialog path.
     */
    fun androidName(permission: DpsPermission): String? = when (permission) {
        DpsPermission.READ_CALENDAR -> Manifest.permission.READ_CALENDAR
        DpsPermission.WRITE_CALENDAR -> Manifest.permission.WRITE_CALENDAR
        DpsPermission.READ_CONTACTS -> Manifest.permission.READ_CONTACTS
        DpsPermission.POST_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        DpsPermission.SCHEDULE_EXACT_ALARM -> Manifest.permission.SCHEDULE_EXACT_ALARM
        DpsPermission.CALL_PHONE -> Manifest.permission.CALL_PHONE
        DpsPermission.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        DpsPermission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO
        DpsPermission.AUTOMATION_ACCESSIBILITY -> null
    }

    /** Reverse lookup, for interpreting permission-result callbacks. */
    fun fromAndroidName(androidName: String): DpsPermission? =
        DpsPermission.entries.firstOrNull { androidName(it) == androidName }
}
