package com.softwaremine.dps.ai.tool

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolExecutor
import com.softwaremine.dps.domain.tool.ToolRegistry
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The production [ToolExecutor].
 *
 * ## Purpose
 * The single gate between the AI layer and Android. Every guarantee the tool
 * contract makes is enforced here, once, rather than depended upon in each
 * tool.
 *
 * ## Execution pipeline
 * ```
 * resolve tool        → Unsupported when unregistered
 * check API level     → Unsupported with minApiLevel when too old
 * check operation     → Unsupported when not implemented
 * check permissions   → PermissionRequired when missing
 * execute with timeout→ Timeout when exceeded
 * catch Throwable     → Error   (defect, never propagated)
 * ```
 *
 * Each stage declines cleanly, so an AI that asks for something impossible gets
 * an explanation instead of a crash.
 *
 * ## Why nothing throws
 * A tool failure reaching the AI layer as an exception has no structured way to
 * be explained to the user. The assistant's only honest response would be that
 * something broke, which is exactly the experience this product exists to avoid.
 * Every outcome is therefore a value.
 *
 * The single exception is [kotlinx.coroutines.CancellationException], which is
 * translated to [ToolResult.Cancelled] when it is *our* timeout, and otherwise
 * rethrown — swallowing a genuine cancellation would break structured
 * concurrency and leave the caller's scope wedged.
 *
 * ## Dependencies
 * [ToolRegistry], [PermissionManager], [DispatcherProvider], [DpsLogger] and an
 * injected API level. Deliberately **not** `android.os.Build` — reading it here
 * would put an Android import in the AI layer and make every test in this class
 * require a device.
 */
class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val permissionManager: PermissionManager,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    /** Device API level, supplied by the composition root from `Build.VERSION.SDK_INT`. */
    private val apiLevel: Int,
) : ToolExecutor {

    override suspend fun execute(
        call: ToolCall,
        timeoutMillis: Long,
    ): ToolResult {
        val tool = registry.find(call.toolId)
            ?: return ToolResult.Unsupported(
                reason = "No tool is registered for '${call.toolId.toolName}'.",
            ).also { logger.w(TAG, "Unregistered tool requested: ${call.toolId}") }

        // API level before operation: a tool that cannot run on this OS should
        // say so rather than report its operations as unavailable, which would
        // send the caller looking for the wrong problem.
        tool.minApiLevel?.let { required ->
            if (apiLevel < required) {
                return ToolResult.Unsupported(
                    reason = "'${call.toolId.toolName}' requires a newer Android version.",
                    minApiLevel = required,
                )
            }
        }

        if (!tool.supports(call.operation)) {
            return ToolResult.Unsupported(
                reason = "'${call.toolId.toolName}' does not support '${call.operation}'.",
            ).also {
                logger.w(TAG, "Unsupported operation ${call.toolId}.${call.operation}")
            }
        }

        val missing = permissionManager.missing(tool.requiredPermissions)
        if (missing.isNotEmpty()) {
            // Reported, never requested here. A permission dialog is a
            // user-visible interruption, and choosing when to show one needs
            // knowledge of what the user is trying to do — which this layer
            // does not have. Human Confirmation (Rule 8) applies.
            logger.i(TAG, "Permissions missing for ${call.toolId}: $missing")
            return ToolResult.PermissionRequired(
                permissions = missing.toList(),
                rationale = buildRationale(call, missing.map { it.name }),
            )
        }

        return runGuarded(call, timeoutMillis) { tool.execute(call) }
    }

    /**
     * Runs [block] under a deadline, converting every failure mode to a
     * [ToolResult].
     */
    private suspend fun runGuarded(
        call: ToolCall,
        timeoutMillis: Long,
        block: suspend () -> ToolResult,
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        return try {
            // Platform work is IO-bound — content providers, intents, system
            // services. Deliberately not the inference dispatcher, which is a
            // single thread reserved for blocking native model work; a tool
            // call queued behind a generation would appear to hang.
            withContext(dispatchers.io) {
                withTimeout(timeoutMillis) { block() }
            }
        } catch (timeout: TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startedAt
            logger.w(TAG, "Timeout after ${elapsed}ms: ${call.toolId}.${call.operation}")
            ToolResult.Timeout(elapsedMillis = elapsed, limitMillis = timeoutMillis)
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            // Not ours. Propagate so the caller's scope unwinds correctly.
            throw cancellation
        } catch (throwable: Throwable) {
            // A tool that throws has violated its contract. Contained here so a
            // single defective tool cannot take down the assistant.
            logger.e(TAG, "Tool ${call.toolId}.${call.operation} threw", throwable)
            ToolResult.Error(
                message = "The tool failed unexpectedly.",
                cause = "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
    }

    /**
     * Builds the user-facing reason for a permission request.
     *
     * Names the capability, never the tool or the Android permission string —
     * AI Rules 1 and 2 forbid exposing internal architecture, and
     * "android.permission.WRITE_CALENDAR" tells a user nothing they can act on.
     */
    private fun buildRationale(call: ToolCall, missing: List<String>): String =
        "DPS needs your permission to ${describe(call)} " +
            "(${missing.size} permission${if (missing.size == 1) "" else "s"} required)."

    private fun describe(call: ToolCall): String = when (call.toolId) {
        com.softwaremine.dps.domain.tool.ToolId.CALENDAR -> "access your calendar"
        com.softwaremine.dps.domain.tool.ToolId.NOTIFICATION -> "send you notifications"
        com.softwaremine.dps.domain.tool.ToolId.REMINDER -> "set reminders"
        com.softwaremine.dps.domain.tool.ToolId.CONTACTS -> "look up your contacts"
        com.softwaremine.dps.domain.tool.ToolId.PHONE -> "place calls"
        com.softwaremine.dps.domain.tool.ToolId.ALARM -> "schedule alarms"
        else -> "complete that"
    }

    private companion object {
        const val TAG = "ToolExecutor"
    }
}

/** `true` when every state in this map permits use. */
internal fun Map<*, PermissionState>.allUsable(): Boolean = values.all { it.isUsable }
