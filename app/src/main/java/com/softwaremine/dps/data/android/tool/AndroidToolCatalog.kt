package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolRegistry

/**
 * Declares every Android capability DPS will expose, and registers them.
 *
 * ## Purpose
 * The manifest of the tool layer. Reading this file top to bottom answers
 * "what will DPS be able to do, and what does each thing cost the user in
 * permissions?" — a question worth being able to answer from one screen.
 *
 * ## Status — Day 05 Phase 1
 * **Every tool here is declared, none is implemented.** Each resolves to
 * [UnimplementedTool] and returns [com.softwaremine.dps.domain.tool.ToolResult.Unsupported].
 *
 * That is the phase boundary the brief draws, and it is respected literally: no
 * calendar insertion, no notifications, no contacts lookup, no messaging.
 *
 * ## Why declare operations and permissions now
 * Two reasons, both practical rather than tidy.
 *
 * First, the permission surface becomes visible and reviewable *before* any
 * code depends on it. A product whose central promise is privacy should not
 * discover its own permission footprint one feature at a time.
 *
 * Second, the executor's permission gate, API-level gate and unsupported-operation
 * handling are exercised against real declarations from the first commit, so
 * those paths are tested behaviour rather than untested branches waiting for a
 * feature to arrive.
 *
 * ## Manifest note
 * These permissions are **not** declared in `AndroidManifest.xml` yet, and that
 * is deliberate. The Day 02 manifest records the rule: a permission is
 * requested only when the feature needing it exists. Until then
 * [com.softwaremine.dps.domain.permission.PermissionManager] will correctly
 * report them unusable, because an undeclared permission can never be granted —
 * which is the accurate answer while the feature does not exist.
 *
 * ## Dependencies
 * Domain tool and permission types, [UnimplementedTool].
 */
object AndroidToolCatalog {

    /**
     * Every declared tool.
     *
     * Operation names are the vocabulary the LLM will eventually be given, so
     * they are verbs a model would plausibly produce, not internal method names.
     */
    fun declaredTools(implemented: List<AndroidTool> = emptyList()): List<AndroidTool> {
        val implementedIds = implemented.map { it.id }.toSet()
        // Real implementations win; the declarations below fill the remainder.
        // Filtering rather than overwriting keeps the registry's duplicate
        // rejection meaningful — a tool is registered exactly once.
        return implemented + stillUnimplemented().filterNot { it.id in implementedIds }
    }

    /** Tools that remain declarations only. */
    private fun stillUnimplemented(): List<AndroidTool> = listOf(

        UnimplementedTool(
            id = ToolId.CONTACTS,
            operations = setOf("find_contact", "list_contacts"),
            requiredPermissions = setOf(DpsPermission.READ_CONTACTS),
            plannedPhase = "Phase 3",
        ),

        UnimplementedTool(
            id = ToolId.PHONE,
            operations = setOf("place_call"),
            requiredPermissions = setOf(DpsPermission.CALL_PHONE),
            plannedPhase = "Phase 4",
        ),

        UnimplementedTool(
            id = ToolId.ALARM,
            operations = setOf("set_alarm", "cancel_alarm"),
            // Special access: granted in system settings, never by the runtime
            // dialog. See PermissionKind.SPECIAL_ACCESS.
            requiredPermissions = setOf(DpsPermission.SCHEDULE_EXACT_ALARM),
            minApiLevel = 31,
            plannedPhase = "Phase 3",
        ),

        UnimplementedTool(
            id = ToolId.WORK_MANAGER,
            operations = setOf("schedule_work", "cancel_work", "query_work"),
            // WorkManager needs no permission; it is an in-process scheduler.
            requiredPermissions = emptySet(),
            plannedPhase = "Phase 3",
        ),

        UnimplementedTool(
            id = ToolId.PERMISSION,
            operations = setOf("check_permission", "request_permission", "open_settings"),
            // Asking about permissions requires none.
            requiredPermissions = emptySet(),
            plannedPhase = "Phase 2",
        ),

        // --- External applications ---
        //
        // These integrate through Intents rather than permissions, so their
        // requiredPermissions are legitimately empty. Their real constraint is
        // whether the target app is installed, which is a runtime question a
        // future implementation answers with ToolResult.Unsupported.
        //
        // Both are also subject to Human Confirmation (development_rule.md
        // Rule 8): nothing is ever sent without the user confirming first.

        UnimplementedTool(
            id = ToolId.WHATSAPP,
            operations = setOf("send_message"),
            requiredPermissions = emptySet(),
            plannedPhase = "Phase 5",
        ),

        UnimplementedTool(
            id = ToolId.GMAIL,
            operations = setOf("compose_email", "send_email"),
            requiredPermissions = emptySet(),
            plannedPhase = "Phase 5",
        ),
    )

    /**
     * Registers every declared tool into [registry].
     *
     * Called once from the composition root. The registry rejects duplicate
     * ids, so calling this twice fails loudly at startup rather than producing
     * a shadowed tool.
     */
    fun registerAll(registry: ToolRegistry, implemented: List<AndroidTool> = emptyList()) {
        declaredTools(implemented).forEach(registry::register)
    }

    /** Union of every permission any declared tool requires. */
    fun allDeclaredPermissions(): Set<DpsPermission> =
        declaredTools().flatMap { it.requiredPermissions }.toSet()
}
