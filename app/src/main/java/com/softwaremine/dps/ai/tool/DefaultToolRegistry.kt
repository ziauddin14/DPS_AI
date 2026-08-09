package com.softwaremine.dps.ai.tool

import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [ToolRegistry].
 *
 * ## Purpose
 * Holds registered tools and resolves them. Nothing more — lookup is the whole
 * job, and keeping it that way is what lets the executor own execution policy
 * without the two fighting over responsibilities.
 *
 * ## Concurrency
 * Backed by [ConcurrentHashMap]. Registration happens once at startup from the
 * composition root, but lookups arrive from the inference thread, the UI thread
 * and eventually background workers. A plain map would be a data race for no
 * benefit.
 *
 * Insertion order is preserved separately, because the order tools are
 * described to the LLM should be stable across runs — a prompt that reorders
 * itself between launches makes model behaviour irreproducible and prompt
 * caching useless.
 *
 * ## Dependencies
 * Domain tool types and [DpsLogger]. No Android.
 */
class DefaultToolRegistry(
    private val logger: DpsLogger,
) : ToolRegistry {

    private val tools = ConcurrentHashMap<ToolId, AndroidTool>()

    /** Registration order, kept for stable prompt output. */
    private val order = mutableListOf<ToolId>()
    private val orderLock = Any()

    override fun register(tool: AndroidTool) {
        // A duplicate registration is a wiring bug, not a runtime condition.
        // Replacing silently would leave a shadowed tool that is almost
        // impossible to diagnose from behaviour alone, so it fails loudly at
        // startup where the stack trace still points at the cause.
        val existing = tools.putIfAbsent(tool.id, tool)
        check(existing == null) {
            "Tool ${tool.id} is already registered by ${existing?.javaClass?.simpleName}; " +
                "refusing to replace it with ${tool.javaClass.simpleName}"
        }

        synchronized(orderLock) { order.add(tool.id) }

        logger.d(
            TAG,
            "Registered ${tool.id.toolName}: ${tool.operations.size} operations, " +
                "${tool.requiredPermissions.size} permissions",
        )
    }

    override fun find(id: ToolId): AndroidTool? = tools[id]

    override fun findByName(toolName: String): AndroidTool? =
        ToolId.fromName(toolName)?.let { tools[it] }

    override fun all(): List<AndroidTool> =
        synchronized(orderLock) { order.toList() }.mapNotNull { tools[it] }

    override fun registeredIds(): Set<ToolId> = tools.keys.toSet()

    private companion object {
        const val TAG = "ToolRegistry"
    }
}
