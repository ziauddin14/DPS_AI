package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.data.android.memory.LongTermMemoryStore
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Logs one completed action to episodic memory (M6), automatically — no
 * judgment about "is this worth remembering" is needed, mirroring
 * [ConversationMemoryUpdater.remember]'s own "only on confirmed success"
 * trigger exactly: a failed or declined action changed nothing on the
 * device, so it leaves no episodic trace either.
 *
 * ## Why the summary is [ToolResult.Success.summary] itself, not re-derived
 * That string is already the deterministic, templated, human-readable
 * description of what happened — every tool already builds one for the
 * user-facing reply. Re-deriving a second summary here would be exactly the
 * duplicated business logic this codebase's own conventions rule out.
 *
 * ## Why [ToolId.MEMORY] is excluded
 * DPS remembering a fact is not itself an event worth logging as history —
 * it would be a confusing, self-referential entry in a log meant to
 * describe *other* completed actions. See
 * [com.softwaremine.dps.data.android.tool.AndroidMemoryTool]'s own doc.
 */
class EpisodicMemoryRecorder(
    private val store: LongTermMemoryStore,
) {

    suspend fun record(toolId: ToolId, result: ToolResult) {
        if (toolId == ToolId.MEMORY) return
        if (result !is ToolResult.Success) return

        store.recordEpisode(summary = result.summary, toolId = toolId.toolName)
    }
}
