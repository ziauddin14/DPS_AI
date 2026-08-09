package com.softwaremine.dps.domain.permission

import kotlinx.serialization.Serializable

/**
 * The current state of a permission.
 *
 * ## Purpose
 * A boolean cannot express what the UI and the AI both need to know. "Not
 * granted" covers at least four situations that demand completely different
 * responses, and collapsing them produces an assistant that either nags for a
 * permission it can never obtain or gives up on one the user would happily
 * grant.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
enum class PermissionState {

    /** Granted. The capability is available. */
    GRANTED,

    /**
     * Not granted, but the runtime dialog can still be shown.
     *
     * The correct response is to explain why and ask.
     */
    DENIED,

    /**
     * Refused to the point where the system will no longer show the dialog.
     *
     * ## How this is distinguished — and why it takes effort
     * `shouldShowRequestPermissionRationale()` returns `false` in **two**
     * different situations: the permission was never requested, and the
     * permission was permanently denied. The platform offers no API that tells
     * these apart, so the app must remember whether it has asked before.
     *
     * Getting this wrong is not cosmetic. Treating never-asked as permanently
     * denied means never requesting a permission the user would have granted;
     * the reverse means repeatedly launching a dialog the system silently
     * discards, so the user sees nothing happen at all.
     *
     * The only recovery is the app's settings screen.
     */
    PERMANENTLY_DENIED,

    /**
     * Not needed on this OS version, so the capability is available.
     *
     * `POST_NOTIFICATIONS` below API 33 is the case this exists for. Reporting
     * it as [DENIED] would block a feature the platform actually permits.
     */
    NOT_REQUIRED,

    /**
     * Special access that must be granted in system settings.
     *
     * Applies to [PermissionKind.SPECIAL_ACCESS]. The runtime dialog cannot
     * grant these, so the only correct action is to send the user to the
     * relevant settings screen — with their consent.
     */
    REQUIRES_SETTINGS,

    /**
     * State could not be determined.
     *
     * Present so a query failure is never silently reported as granted.
     * Treat as not-granted for any access decision.
     */
    UNKNOWN,
    ;

    /** `true` when the capability may be used right now. */
    val isUsable: Boolean get() = this == GRANTED || this == NOT_REQUIRED

    /** `true` when asking again could plausibly change the outcome. */
    val isRequestable: Boolean get() = this == DENIED

    /** `true` when only a trip to system settings can resolve this. */
    val needsSettings: Boolean
        get() = this == PERMANENTLY_DENIED || this == REQUIRES_SETTINGS
}
