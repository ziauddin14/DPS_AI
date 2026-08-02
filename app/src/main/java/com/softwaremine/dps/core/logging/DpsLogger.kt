package com.softwaremine.dps.core.logging

import android.util.Log
import com.softwaremine.dps.BuildConfig

/**
 * Application-wide logging seam.
 *
 * ## Purpose
 * Logging is an injected capability rather than a static call so that (a) the
 * AI layer stays JVM-unit-testable with no Android framework present, and
 * (b) there is exactly one place that governs what may be written to logcat.
 *
 * ## Privacy obligation — the reason this interface exists at all
 * Development Rule 7 and the product's central promise are that user data never
 * leaves the device. Logcat is *off-device* for practical purposes: it is
 * readable by `adb`, captured by bug-report tooling, and routinely pasted into
 * issue trackers.
 *
 * Therefore: **conversation content, prompts, and generated text must never be
 * logged.** Log the shape of things — token counts, durations, model ids, state
 * transitions — never the content itself. [redact] exists to make the safe
 * choice the easy one.
 *
 * ## Responsibilities
 * - Provide levelled logging behind an interface.
 * - Disable debug/verbose output entirely in release builds.
 *
 * ## Future extensions
 * A file-backed implementation for on-device diagnostics the user can review
 * and delete. It must apply the same redaction rules.
 *
 * ## Dependencies
 * `android.util.Log` and generated `BuildConfig`.
 */
interface DpsLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Logcat-backed logger.
 *
 * Debug and info are compiled out of release builds via [BuildConfig.DEBUG];
 * warnings and errors are retained because they are needed to diagnose field
 * failures, and by convention carry no user content.
 */
class AndroidDpsLogger : DpsLogger {

    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(prefixed(tag), message)
    }

    override fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(prefixed(tag), message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(prefixed(tag), message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(prefixed(tag), message, throwable)
    }

    /** Logcat truncates tags beyond 23 characters on older API levels. */
    private fun prefixed(tag: String): String = "DPS/$tag".take(23)
}

/**
 * Renders user-originated text as a non-identifying summary.
 *
 * Use this anywhere the temptation is to log a prompt or a response. Produces
 * e.g. `<len=142>` — enough to diagnose truncation and boundary bugs, useless
 * to anyone reading a captured log.
 */
fun redact(content: String): String = "<len=${content.length}>"
