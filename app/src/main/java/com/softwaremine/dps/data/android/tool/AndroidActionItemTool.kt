package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.productivity.ActionItem
import com.softwaremine.dps.domain.productivity.ActionItemRepository
import com.softwaremine.dps.domain.productivity.ActionItemStatus
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Creates, lists and completes follow-ups (Day 06).
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_action_item` | `title` (required), `due` | `action_item_id` |
 * | `list_action_items` | – | open follow-ups |
 * | `complete_action_item` | `id` or `title` (address) | `action_item_id` |
 *
 * Addressing by title mirrors [AndroidTaskTool] exactly — see its class doc.
 *
 * ## Permissions
 * None.
 *
 * ## Dependencies
 * [ActionItemRepository], [ToolArguments]. No direct Android imports.
 */
class AndroidActionItemTool(
    private val repository: ActionItemRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.ACTION_ITEM

    override val operations: Set<String> = setOf(OP_CREATE, OP_LIST, OP_COMPLETE)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE -> create(call)
        OP_LIST -> list()
        OP_COMPLETE -> complete(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun create(call: ToolCall): ToolResult {
        val title = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val id = repository.nextId()
        repository.save(
            ActionItem(
                id = id,
                title = title,
                dueAtMillis = call.argument(ARG_DUE)?.toLongOrNull(),
                createdAtMillis = now(),
            ),
        )

        return ToolResult.Success(
            summary = "Added \"$title\" to your follow-ups.",
            data = mapOf("action_item_id" to id.toString()),
        )
    }

    private fun list(): ToolResult {
        val open = repository.all().filter { it.status == ActionItemStatus.OPEN }
        if (open.isEmpty()) return ToolResult.Success(summary = "No open follow-ups.")

        val data = buildMap {
            put("count", open.size.toString())
            open.forEachIndexed { index, item ->
                put("item_${index}_id", item.id.toString())
                put("item_${index}_title", item.title)
            }
        }

        return ToolResult.Success(
            summary = "You have ${open.size} open follow-up${if (open.size == 1) "" else "s"}: " +
                open.joinToString("; ") { it.title } + ".",
            data = data,
        )
    }

    private fun complete(call: ToolCall): ToolResult {
        val existing = resolveTarget(call) ?: return notFound(call)
        repository.save(existing.copy(status = ActionItemStatus.DONE))
        return ToolResult.Success(
            summary = "Marked \"${existing.title}\" as done.",
            data = mapOf("action_item_id" to existing.id.toString()),
        )
    }

    private fun resolveTarget(call: ToolCall): ActionItem? {
        call.argument(ARG_ID)?.trim()?.toIntOrNull()?.let { id -> return repository.find(id) }

        val titleQuery = call.argument(ARG_TITLE)?.trim()?.lowercase()
        if (titleQuery.isNullOrEmpty()) return null

        return repository.all().filter { it.title.lowercase().contains(titleQuery) }.singleOrNull()
    }

    private fun notFound(call: ToolCall): ToolResult {
        val titleQuery = call.argument(ARG_TITLE)?.trim()?.lowercase()
        val matches = if (titleQuery.isNullOrEmpty()) {
            emptyList()
        } else {
            repository.all().filter { it.title.lowercase().contains(titleQuery) }
        }

        return if (matches.size > 1) {
            ToolResult.Failure(
                reason = "Several follow-ups match: ${matches.joinToString(", ") { it.title }}. Which one?",
                retryable = false,
            )
        } else {
            ToolResult.Failure(reason = "I couldn't find that follow-up.", retryable = false)
        }
    }

    private companion object {
        const val OP_CREATE = "create_action_item"
        const val OP_LIST = "list_action_items"
        const val OP_COMPLETE = "complete_action_item"

        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_DUE = "due"
    }
}
