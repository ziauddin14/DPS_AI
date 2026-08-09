package com.softwaremine.dps.domain.tool

/**
 * Executes tool calls. The only path from the AI layer to Android.
 *
 * ## Purpose
 * The choke point. Every Android action passes through here, which is what
 * makes the guarantees below enforceable at all — permission checking, timeouts
 * and failure containment applied in one place rather than hoped for in each of
 * ten tools.
 *
 * ```
 * LLM → Intent Parser → ToolRegistry → [ToolExecutor] → AndroidTool → Android
 * ```
 *
 * ## Guarantees to callers
 * 1. **Never throws.** Every outcome is a [ToolResult], including defects
 *    inside a tool. `CancellationException` is the one exception, and it
 *    propagates untouched so structured concurrency still works.
 * 2. **Permissions checked before dispatch.** A tool is never invoked without
 *    what it declared it needs.
 * 3. **Bounded time.** Every call has a deadline; a wedged tool cannot hang the
 *    assistant.
 * 4. **Unknown tools and operations decline cleanly** as
 *    [ToolResult.Unsupported] rather than crashing.
 *
 * ## Why permissions are checked here rather than in each tool
 * Ten tools checking their own permissions is ten chances to forget that
 * `POST_NOTIFICATIONS` does not exist below API 33, or to treat special access
 * as if the runtime dialog could grant it. Checking centrally means those rules
 * are written once, and a new tool inherits correct behaviour by declaring
 * [AndroidTool.requiredPermissions] and nothing else.
 *
 * ## Dependencies
 * Domain tool types only. Pure Kotlin.
 */
interface ToolExecutor {

    /**
     * Executes [call] and returns its outcome.
     *
     * @param timeoutMillis deadline for this call, overriding the default.
     */
    suspend fun execute(
        call: ToolCall,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): ToolResult

    companion object {
        /**
         * Default deadline for a tool call.
         *
         * Sized for platform operations — a calendar write, a contacts query —
         * which are fast or broken. It is deliberately unrelated to model
         * inference, which is slow by nature and bounded separately by the
         * generation stall watchdog.
         */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000L
    }
}
