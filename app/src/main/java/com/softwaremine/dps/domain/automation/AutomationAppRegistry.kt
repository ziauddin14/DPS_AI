package com.softwaremine.dps.domain.automation

/**
 * Resolves a spoken app name to a real Android package name (M9).
 *
 * ## Why a fixed table, never a guess
 * Package visibility (`AndroidManifest.xml`'s own `<queries>`) already
 * requires every target app to be explicitly declared — this mirrors that
 * same discipline at the application layer. A model-generated
 * package-name-shaped string is never trusted directly: launching the
 * wrong, unintended app is not a recoverable mistake the way an ordinary
 * failure is. See the M9 architectural decisions document's own "App
 * Targeting" decision for the full reasoning.
 *
 * ## Why Phase 1's table has exactly one entry
 * Phase 1 automates exactly one, first-party, deterministic test app —
 * this table is real and exercised end-to-end even though it is small,
 * so the *mechanism* (lookup, not enumeration) is genuinely proven, not
 * bypassed for convenience.
 *
 * ## Dependencies
 * None. Pure Kotlin.
 */
object AutomationAppRegistry {

    /**
     * Resolves [spokenName] to a package name, or `null` if it names no
     * known app. Case- and whitespace-insensitive. Never fuzzy — an exact,
     * normalized lookup only.
     */
    fun resolve(spokenName: String): String? = KNOWN_APPS[spokenName.trim().lowercase()]

    private val KNOWN_APPS = mapOf(
        "test app" to "com.softwaremine.dps.automationtarget",
    )
}
