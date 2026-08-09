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
     * The Android permission string for [permission].
     *
     * Total by construction — [DpsPermission] is a closed enum, so adding a case
     * without a mapping fails to compile rather than at runtime.
     */
    fun androidName(permission: DpsPermission): String = when (permission) {
        DpsPermission.READ_CALENDAR -> Manifest.permission.READ_CALENDAR
        DpsPermission.WRITE_CALENDAR -> Manifest.permission.WRITE_CALENDAR
        DpsPermission.READ_CONTACTS -> Manifest.permission.READ_CONTACTS
        DpsPermission.POST_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        DpsPermission.SCHEDULE_EXACT_ALARM -> Manifest.permission.SCHEDULE_EXACT_ALARM
        DpsPermission.CALL_PHONE -> Manifest.permission.CALL_PHONE
        DpsPermission.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        DpsPermission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO
    }

    /** Reverse lookup, for interpreting permission-result callbacks. */
    fun fromAndroidName(androidName: String): DpsPermission? =
        DpsPermission.entries.firstOrNull { androidName(it) == androidName }
}
