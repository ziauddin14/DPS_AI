package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * A tool that is declared but not yet implemented.
 *
 * ## Purpose
 * Day 05 Phase 1 builds the foundation and explicitly implements no Android
 * behaviour. This type represents that state honestly: the capability is
 * registered, its permissions and operations are declared, and calling it
 * returns [ToolResult.Unsupported] with a truthful reason.
 *
 * ## Why this is not a placeholder
 * The distinction matters. A placeholder pretends to work — it returns a fake
 * success, or a hardcoded value, and the failure surfaces somewhere far away.
 * This returns the correct answer to the question actually being asked: *can
 * you do this?* — no, and here is why.
 *
 * That answer is also what a caller will receive in production on a device
 * where a capability genuinely is unavailable, so this is not throwaway code
 * exercising a throwaway path. The executor's handling of
 * [ToolResult.Unsupported] is validated by real use from the first commit.
 *
 * ## Why one class rather than ten
 * Ten near-identical files declaring nothing but constants would be ten places
 * to update when the contract changes, and nine of them would be forgotten. The
 * *declarations* differ; the behaviour does not. When a tool gains real
 * behaviour it graduates to its own class and this registration is replaced —
 * the registry rejects duplicate ids, so a half-finished migration fails at
 * startup rather than silently shadowing the real implementation.
 *
 * ## Dependencies
 * Domain tool types only.
 */
class UnimplementedTool(
    override val id: ToolId,
    override val operations: Set<String>,
    override val requiredPermissions: Set<DpsPermission> = emptySet(),
    override val minApiLevel: Int? = null,
    /** Phase in which this tool is scheduled to gain behaviour. */
    private val plannedPhase: String,
) : AndroidTool {

    override suspend fun execute(call: ToolCall): ToolResult = ToolResult.Unsupported(
        reason = "'${id.toolName}' is declared but not yet implemented ($plannedPhase).",
        minApiLevel = minApiLevel,
    )

    override fun toString(): String =
        "UnimplementedTool(${id.toolName}, ops=${operations.size}, planned=$plannedPhase)"
}
