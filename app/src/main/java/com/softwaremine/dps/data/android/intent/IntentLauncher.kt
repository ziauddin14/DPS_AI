package com.softwaremine.dps.data.android.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.softwaremine.dps.core.logging.DpsLogger

/**
 * Resolves and launches Intents aimed at other apps.
 *
 * ## Purpose
 * Shared by the WhatsApp and Gmail tools. Both need the same three steps —
 * check something can handle it, add the right flag, launch and survive
 * failure — and duplicating that would mean two places to forget
 * `FLAG_ACTIVITY_NEW_TASK` or to skip the resolve check.
 *
 * ## Package visibility — the part most easily missed
 * From **API 30**, `resolveActivity` and `queryIntentActivities` return
 * *filtered* results. An app targeting 30+ that has not declared a `<queries>`
 * element sees almost nothing, so a perfectly installed WhatsApp resolves to
 * null and DPS reports it as missing.
 *
 * With `targetSdk = 35` this applies to every DPS build. The manifest therefore
 * declares both the specific packages and the intent signatures used here; the
 * code below is only correct in combination with those declarations.
 *
 * ## Why launching is separate from building
 * [WhatsAppIntentBuilder] and [EmailIntentBuilder] construct and nothing else,
 * so they can be verified on the JVM. Launching needs a `Context` and is
 * therefore here, where it is the only Android-touching step.
 *
 * ## Background activity starts
 * Android 10+ restricts starting activities from the background. DPS launches
 * these while the user is actively conversing, so the app is foreground — but
 * the restriction is silent when it applies, and [LaunchOutcome.Failed] is
 * returned rather than assuming success.
 *
 * ## Dependencies
 * `android.content`. Android layer only.
 */
class IntentLauncher(
    private val context: Context,
    private val logger: DpsLogger,
) {

    /** Result of attempting to launch. */
    sealed interface LaunchOutcome {
        /** An app accepted the Intent. */
        data class Launched(val targetPackage: String?) : LaunchOutcome

        /** Nothing on the device can handle it. */
        data object NoHandler : LaunchOutcome

        /** A handler existed but the launch failed. */
        data class Failed(val reason: String) : LaunchOutcome
    }

    /**
     * Whether any installed app can handle [intent].
     *
     * Uses `queryIntentActivities` rather than `resolveActivity`: the latter
     * can return the system's "resolver activity" placeholder when several apps
     * match, which is a valid answer to "who handles this" but a confusing one
     * to test against. A non-empty list is unambiguous.
     */
    fun canHandle(intent: Intent): Boolean = resolvedPackages(intent).isNotEmpty()

    /** Packages able to handle [intent]. Empty when none, or when filtered out. */
    fun resolvedPackages(intent: Intent): List<String> = try {
        context.packageManager
            .queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
    } catch (throwable: Throwable) {
        logger.w(TAG, "queryIntentActivities failed", throwable)
        emptyList()
    }

    /** Whether [packageName] is installed and visible to this app. */
    fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (notFound: Throwable) {
        // Also thrown when the package exists but is filtered by package
        // visibility, which is why the manifest declares the packages we care
        // about explicitly.
        false
    }

    /**
     * Launches [intent], checking first that something can handle it.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is added because tools run outside an Activity
     * context; starting an activity from the application context without it
     * throws.
     */
    fun launch(intent: Intent): LaunchOutcome {
        val handlers = resolvedPackages(intent)
        if (handlers.isEmpty()) {
            logger.i(TAG, "No handler for ${intent.action} ${intent.data}")
            return LaunchOutcome.NoHandler
        }

        return try {
            context.startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            logger.i(TAG, "Launched ${intent.action} -> ${handlers.first()}")
            LaunchOutcome.Launched(handlers.firstOrNull())
        } catch (notFound: ActivityNotFoundException) {
            // Possible despite the check: the app can be uninstalled between
            // resolving and launching.
            logger.w(TAG, "Handler disappeared before launch", notFound)
            LaunchOutcome.NoHandler
        } catch (throwable: Throwable) {
            logger.e(TAG, "Launch failed", throwable)
            LaunchOutcome.Failed(throwable.message ?: "Could not open the app.")
        }
    }

    private companion object {
        const val TAG = "IntentLauncher"
    }
}
