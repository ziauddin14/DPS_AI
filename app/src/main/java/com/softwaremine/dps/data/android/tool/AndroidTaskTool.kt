package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskPriority
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.productivity.TaskStatus
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Creates, lists, updates, completes and cancels tasks (Day 06).
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_task` | `title` (required), `notes`, `priority`, `due` | `task_id` |
 * | `list_tasks` | – | pending tasks |
 * | `update_task` | `id` or `title` (address), `title` (new), `notes`, `priority`, `due` | `task_id` |
 * | `complete_task` | `id` or `title` (address) | `task_id` |
 * | `cancel_task` | `id` or `title` (address) | `task_id` |
 *
 * ## Addressing by title
 * `id` is preferred when present — a resolved conversation-memory reference.
 * Failing that, `title` is matched case-insensitively as a substring against
 * stored task titles, so "DBPMS wala task complete kar do" works without the
 * user ever knowing a numeric id. Zero or several matches both fail
 * honestly rather than guessing — the same rule
 * [com.softwaremine.dps.domain.contact.ContactResolver] applies to an
 * ambiguous contact name.
 *
 * ## Permissions
 * None — this is local storage the app already has private access to.
 *
 * ## Dependencies
 * [TaskRepository], [ToolArguments]. No direct Android imports.
 */
class AndroidTaskTool(
    private val repository: TaskRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.TASK

    override val operations: Set<String> = setOf(OP_CREATE, OP_LIST, OP_UPDATE, OP_COMPLETE, OP_CANCEL)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE -> create(call)
        OP_LIST -> list()
        OP_UPDATE -> update(call)
        OP_COMPLETE -> complete(call)
        OP_CANCEL -> cancel(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun create(call: ToolCall): ToolResult {
        val title = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val id = repository.nextId()
        val nowMillis = now()

        repository.save(
            Task(
                id = id,
                title = title,
                notes = call.argument(ARG_NOTES),
                priority = parsePriority(call.argument(ARG_PRIORITY)),
                dueAtMillis = call.argument(ARG_DUE)?.toLongOrNull(),
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            ),
        )

        return ToolResult.Success(
            summary = "Task \"$title\" added.",
            data = mapOf("task_id" to id.toString()),
        )
    }

    private fun list(): ToolResult {
        val pending = repository.all().filter { it.status == TaskStatus.PENDING }
        if (pending.isEmpty()) return ToolResult.Success(summary = "You have no pending tasks.")

        val data = buildMap {
            put("count", pending.size.toString())
            pending.forEachIndexed { index, task ->
                put("task_${index}_id", task.id.toString())
                put("task_${index}_title", task.title)
            }
        }
        return ToolResult.Success(
            summary = "You have ${pending.size} pending task${if (pending.size == 1) "" else "s"}: " +
                pending.joinToString("; ") { it.title } + ".",
            data = data,
        )
    }

    private fun update(call: ToolCall): ToolResult {
        val existing = resolveTarget(call) ?: return notFound(call)

        val newTitle = call.argument(ARG_TITLE) ?: existing.title
        repository.save(
            existing.copy(
                title = newTitle,
                notes = call.argument(ARG_NOTES) ?: existing.notes,
                priority = call.argument(ARG_PRIORITY)?.let(::parsePriority) ?: existing.priority,
                dueAtMillis = call.argument(ARG_DUE)?.toLongOrNull() ?: existing.dueAtMillis,
                updatedAtMillis = now(),
            ),
        )

        return ToolResult.Success(
            summary = "Task \"$newTitle\" updated.",
            data = mapOf("task_id" to existing.id.toString()),
        )
    }

    private fun complete(call: ToolCall): ToolResult {
        val existing = resolveTarget(call) ?: return notFound(call)
        val nowMillis = now()
        repository.save(
            existing.copy(status = TaskStatus.COMPLETED, completedAtMillis = nowMillis, updatedAtMillis = nowMillis),
        )
        return ToolResult.Success(
            summary = "Marked \"${existing.title}\" as done.",
            data = mapOf("task_id" to existing.id.toString()),
        )
    }

    private fun cancel(call: ToolCall): ToolResult {
        val existing = resolveTarget(call) ?: return notFound(call)
        repository.delete(existing.id)
        return ToolResult.Success(
            summary = "Deleted \"${existing.title}\".",
            data = mapOf("task_id" to existing.id.toString()),
        )
    }

    /** Resolves which task [call] addresses. See the class doc for the ambiguity rule. */
    private fun resolveTarget(call: ToolCall): Task? {
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
                reason = "Several tasks match: ${matches.joinToString(", ") { it.title }}. Which one?",
                retryable = false,
            )
        } else {
            ToolResult.Failure(reason = "I couldn't find that task.", retryable = false)
        }
    }

    private fun parsePriority(raw: String?): TaskPriority? = when (raw?.trim()?.lowercase()) {
        "high", "urgent", "important" -> TaskPriority.HIGH
        "low" -> TaskPriority.LOW
        "normal", "medium" -> TaskPriority.NORMAL
        else -> null
    }

    private companion object {
        const val OP_CREATE = "create_task"
        const val OP_LIST = "list_tasks"
        const val OP_UPDATE = "update_task"
        const val OP_COMPLETE = "complete_task"
        const val OP_CANCEL = "cancel_task"

        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_NOTES = "notes"
        const val ARG_PRIORITY = "priority"
        const val ARG_DUE = "due"
    }
}
