package com.softwaremine.dps.domain.permission

import kotlinx.serialization.Serializable

/**
 * A permission DPS may need, expressed in domain terms.
 *
 * ## Purpose
 * `domain/` must never import `android.*` (ADR-005), yet permissions are an
 * inherently Android concept. This enum is the seam: it names the *capability*
 * being requested, and the mapping to `android.Manifest.permission` constants
 * lives in the Android layer, in exactly one file.
 *
 * That is not ceremony. It keeps the tool layer, the executor and every future
 * AI-facing description testable on the JVM, and it means a permission's Android
 * string appears once rather than being scattered across every tool that needs
 * it.
 *
 * ## Why [kind] exists — verified against official documentation
 * Android does not treat all permissions alike, and the difference is not
 * cosmetic. `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are documented as
 * **special** permissions: they carry normal protection level, are declared in
 * the manifest, and **cannot be granted through the runtime permission
 * dialog**. Requesting one the way a dangerous permission is requested simply
 * does nothing.
 *
 * A permission model with a single "request" path would therefore be silently
 * broken for alarms — which is precisely the capability a secretary product
 * depends on. See [PermissionKind].
 *
 * ## Why [minApiLevel] exists
 * `POST_NOTIFICATIONS` was introduced in API 33 and does not exist below it;
 * notifications simply work without it. Treating its absence as "denied" on an
 * Android 12 device would block a feature that is in fact permitted, so the
 * model distinguishes *not required* from *denied* ([PermissionState]).
 *
 * ## Scope note (Day 05 Phase 1)
 * These are declarations only. No tool implements calendar, notification,
 * contacts or telephony behaviour yet.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
enum class DpsPermission(
    val kind: PermissionKind,
    /** Lowest API level at which this permission exists, or `null` if always. */
    val minApiLevel: Int? = null,
) {

    // --- Calendar (dangerous / runtime) ---
    READ_CALENDAR(PermissionKind.RUNTIME),
    WRITE_CALENDAR(PermissionKind.RUNTIME),

    // --- Contacts (dangerous / runtime) ---
    READ_CONTACTS(PermissionKind.RUNTIME),

    /**
     * Notifications. Introduced in API 33; below that the platform has no such
     * permission and notifications are posted freely.
     */
    POST_NOTIFICATIONS(PermissionKind.RUNTIME, minApiLevel = 33),

    /**
     * Exact alarms. **Special access, not a runtime permission.**
     *
     * Granted through a system settings screen reached by
     * `AlarmManager.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31+), and its
     * current state is read with `AlarmManager.canScheduleExactAlarms()`.
     * `checkSelfPermission` is not the right question to ask about it.
     */
    SCHEDULE_EXACT_ALARM(PermissionKind.SPECIAL_ACCESS, minApiLevel = 31),

    // --- Telephony (dangerous / runtime) ---
    CALL_PHONE(PermissionKind.RUNTIME),
    READ_PHONE_STATE(PermissionKind.RUNTIME),

    /**
     * Microphone. Day 07. Backs voice input
     * ([com.softwaremine.dps.data.android.voice.AndroidSpeechRecognizer]).
     * `RECORD_AUDIO` is a dangerous/runtime permission at every API level this
     * app supports — no [minApiLevel] gate is needed, unlike `POST_NOTIFICATIONS`.
     */
    RECORD_AUDIO(PermissionKind.RUNTIME),
    ;

    /** `true` when this permission exists on the given API level. */
    fun existsOn(apiLevel: Int): Boolean = minApiLevel == null || apiLevel >= minApiLevel
}

/**
 * How a permission is obtained — the two paths are not interchangeable.
 *
 * Verified against `android.Manifest.permission` documentation.
 */
@Serializable
enum class PermissionKind {

    /**
     * A dangerous permission, granted through the standard runtime dialog.
     * `ContextCompat.checkSelfPermission` reports it; an
     * `ActivityResultContracts.RequestMultiplePermissions` launcher requests it.
     */
    RUNTIME,

    /**
     * Special access, granted only through a dedicated system settings screen.
     *
     * The runtime dialog has no effect on these. Each has its own query API
     * (for example `AlarmManager.canScheduleExactAlarms()`) and its own settings
     * Intent, so they cannot share the runtime path.
     */
    SPECIAL_ACCESS,
}
