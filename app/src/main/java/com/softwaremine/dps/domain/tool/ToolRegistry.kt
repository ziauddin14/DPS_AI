package com.softwaremine.dps.domain.tool

/**
 * The catalogue of available tools.
 *
 * ## Purpose
 * One place that knows what DPS can do. `development_rule.md` Rule 5 makes the
 * Tool Registry the single source of truth for tools on the web platform; this
 * is the Android counterpart, and the same rule applies — a capability that is
 * not registered here does not exist.
 *
 * ## Responsibilities
 * - Hold registered tools and resolve them by [ToolId].
 * - Report what is registered.
 *
 * ## Explicit non-responsibilities
 * It does not execute anything, check permissions, or decide which tool suits a
 * request. Lookup only. Execution is [ToolExecutor]; selection will be the
 * intent parser's job in a later phase.
 *
 * ## Future extension
 * The registry is where tool descriptions for the LLM will come from — the
 * model can only choose among tools it has been told about, and that listing
 * must derive from what is actually registered rather than from a
 * hand-maintained prompt that drifts out of step with the code.
 *
 * ## Dependencies
 * [AndroidTool], [ToolId]. Pure Kotlin.
 */
interface ToolRegistry {

    /**
     * Registers [tool].
     *
     * Registering a second tool under an existing [ToolId] is a programming
     * error, not a runtime condition. Implementations reject it loudly rather
     * than silently replacing — a silently shadowed tool is close to
     * undiagnosable.
     */
    fun register(tool: AndroidTool)

    /** Resolves [id], or `null` when nothing is registered under it. */
    fun find(id: ToolId): AndroidTool?

    /** Resolves a wire name as produced by an LLM, or `null`. */
    fun findByName(toolName: String): AndroidTool?

    /** Every registered tool, in registration order. */
    fun all(): List<AndroidTool>

    /** Ids of every registered tool. */
    fun registeredIds(): Set<ToolId>

    /** Whether a tool is registered under [id]. */
    operator fun contains(id: ToolId): Boolean = find(id) != null
}
