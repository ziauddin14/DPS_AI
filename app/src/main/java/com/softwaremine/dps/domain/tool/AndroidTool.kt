package com.softwaremine.dps.domain.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import kotlinx.serialization.Serializable

/**
 * One Android capability, expressed as a tool.
 *
 * ## Purpose
 * The boundary the AI is never allowed to cross. `ai_architecture.md` states it
 * directly: *"The AI itself never edits data. It only calls tools."* The same
 * rule governs the platform — nothing in the AI layer touches `android.*`.
 *
 * The path is fixed:
 *
 * ```
 * LLM → Intent Parser → ToolRegistry → ToolExecutor → AndroidTool → Android Framework
 * ```
 *
 * Every arrow is one-directional and every stage is replaceable. A tool cannot
 * reach back into the AI, and the AI cannot reach past the executor.
 *
 * ## Why this interface is in `domain/`
 * It names no Android type. Implementations live in the Android layer and are
 * the only code importing `android.*`. That keeps the registry, the executor
 * and every future prompt-facing description unit-testable on the JVM, which
 * matters on this project specifically: the reference device is the only
 * hardware available and emulators are excluded by policy (ADR-009).
 *
 * ## Responsibilities of an implementation
 * - Declare identity, operations and required permissions — truthfully.
 * - Execute one operation and return a [ToolResult].
 * - **Never throw.** Failure is a return value.
 * - Never request permissions itself. Report [ToolResult.PermissionRequired]
 *   and let the layer that knows the user's intent decide.
 *
 * ## Scope note (Day 05 Phase 1)
 * Contract only. No calendar, notification, contacts, telephony or messaging
 * behaviour is implemented; those are later phases and are explicitly out of
 * scope. Registered tools currently answer [ToolResult.Unsupported], which is
 * their honest state rather than a placeholder.
 *
 * ## Dependencies
 * [DpsPermission], [ToolResult]. Pure Kotlin.
 */
interface AndroidTool {

    /** Stable identity. Used for lookup and for describing tools to the LLM. */
    val id: ToolId

    /**
     * Operations this tool implements.
     *
     * Must reflect what actually works. A tool declaring an operation it cannot
     * perform turns a clean [ToolResult.Unsupported] into a runtime surprise.
     */
    val operations: Set<String>

    /**
     * Permissions required before any operation can run.
     *
     * Checked by the executor *before* dispatch, so a tool never has to defend
     * itself against being called without them.
     */
    val requiredPermissions: Set<DpsPermission>

    /**
     * Lowest API level at which this tool functions, or `null` for any.
     *
     * Declared rather than discovered, so the executor can decline early with a
     * useful reason instead of letting the tool fail obscurely.
     */
    val minApiLevel: Int? get() = null

    /** Whether [operation] is implemented. */
    fun supports(operation: String): Boolean = operation in operations

    /**
     * Executes [call].
     *
     * ## Contract
     * - Must not throw. Every outcome is a [ToolResult].
     * - Must be cancellation-aware; a cancelled call returns
     *   [ToolResult.Cancelled] or lets `CancellationException` propagate
     *   untouched, never swallows it.
     * - The executor guarantees permissions were checked and the operation is
     *   supported before calling, so implementations need not re-check.
     */
    suspend fun execute(call: ToolCall): ToolResult
}

/**
 * Identity of a tool.
 *
 * A closed enum rather than free-form strings: the LLM will eventually choose
 * among these, and a typo in a model-generated tool name should fail at the
 * registry with a clear error rather than silently matching nothing.
 *
 * Every entry is **declared** here; none is implemented in Phase 1.
 */
@Serializable
enum class ToolId(val toolName: String) {
    CALENDAR("calendar"),
    NOTIFICATION("notification"),
    REMINDER("reminder"),
    CONTACTS("contacts"),
    PHONE("phone"),
    WHATSAPP("whatsapp"),
    GMAIL("gmail"),
    ALARM("alarm"),
    WORK_MANAGER("work_manager"),
    PERMISSION("permission"),

    // --- Day 06: productivity secretary ---
    TASK("task"),
    WORK_LOG("work_log"),
    MEETING("meeting"),
    ACTION_ITEM("action_item"),
    REPORT("report"),
    ;

    companion object {
        private val BY_NAME = entries.associateBy { it.toolName }

        /** Resolves a wire name, or `null` if unknown. */
        fun fromName(name: String): ToolId? = BY_NAME[name.lowercase().trim()]
    }
}

/**
 * A request to execute one operation on one tool.
 *
 * ## Why arguments are `Map<String, String>`
 * These originate from an LLM, which produces text. Typed argument objects
 * would require the model to emit a precise schema and would move parsing
 * failures into deserialization, where they surface as exceptions rather than
 * as a [ToolResult] the assistant can explain.
 *
 * Each tool validates and converts its own arguments, and reports bad input as
 * [ToolResult.Failure] — an expected outcome, since models produce malformed
 * arguments routinely.
 */
@Serializable
data class ToolCall(
    val toolId: ToolId,
    val operation: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    /** Argument or `null`. */
    fun argument(key: String): String? = arguments[key]

    /** Argument or [fallback]. */
    fun argumentOr(key: String, fallback: String): String = arguments[key] ?: fallback
}
