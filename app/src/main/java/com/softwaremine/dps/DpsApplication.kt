package com.softwaremine.dps

import android.app.Application
import android.content.ComponentCallbacks2
import com.softwaremine.dps.di.AiContainer
import kotlinx.coroutines.launch

/**
 * Application entry point and owner of the object graph.
 *
 * ## Purpose
 * Holds the [AiContainer] for the process lifetime and translates Android
 * lifecycle signals into AI subsystem actions.
 *
 * ## Why the model is not loaded here
 * [onCreate] runs on the main thread before the first frame. Loading a model
 * takes 5–8 seconds; doing it here would mean the app appears frozen on every
 * launch, blowing the < 7 second cold-start KPI outright and punishing users who
 * only opened DPS to glance at something.
 *
 * The model loads when
 * [com.softwaremine.dps.ai.session.AiSessionManager.startSession] is called —
 * that is, when the user actually engages the AI (ADR-002, lazy load).
 *
 * ## Memory pressure — the part that matters most on this hardware
 * A loaded model holds ~2 GB of *native* memory, which is invisible to the JVM
 * garbage collector. The GC will happily report a healthy heap while the process
 * is on the brink of being killed, so nothing in the Java memory model will save
 * us here. [onTrimMemory] is the only signal available, and responding to it is
 * what stands between a backgrounded DPS and being terminated with the user's
 * conversation lost.
 *
 * Releasing is cheap to recover from: the session becomes
 * [com.softwaremine.dps.ai.session.SessionState.Suspended], conversation state
 * survives, and resuming costs one model load.
 *
 * ## Dependencies
 * [AiContainer].
 */
class DpsApplication : Application() {

    /** The object graph. Constructed lazily on first access. */
    val container: AiContainer by lazy { AiContainer(applicationContext) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // TRIM_MEMORY_BACKGROUND is the threshold at which this process enters
        // the LRU kill list. Everything above it is progressively closer to
        // termination, so all of them release.
        //
        // Lower levels (RUNNING_MODERATE and friends) arrive while the app is
        // still foreground and interactive; unloading there would drop the model
        // out from under a user who is mid-conversation, trading a 2 GB saving
        // for an 8 second stall they did not ask for.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            container.logger.i(TAG, "Memory trim (level=$level); releasing AI memory.")
            container.applicationScope.launch {
                container.sessionManager.releaseMemory()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.logger.w(TAG, "System reported low memory; releasing AI memory.")
        container.applicationScope.launch {
            container.sessionManager.releaseMemory()
        }
    }

    private companion object {
        const val TAG = "DpsApplication"
    }
}
