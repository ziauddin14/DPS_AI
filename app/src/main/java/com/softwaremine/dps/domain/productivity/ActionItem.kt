package com.softwaremine.dps.domain.productivity

import kotlinx.serialization.Serializable

/**
 * A follow-up the user needs to act on, optionally traced back to a meeting
 * or task.
 *
 * ## Why this is not just a [Task]
 * An action item usually starts life attached to context — a meeting, a
 * conversation — that a bare task does not carry. [relatedMeetingId] keeps
 * that provenance. Converting one into a [Task] (e.g. once it needs full
 * task tracking) is a deliberate, explicit operation, not an automatic one —
 * the Day 06 brief allows conversion "where appropriate", not by default.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
data class ActionItem(
    val id: Int,
    val title: String,
    val source: String? = null,
    val relatedMeetingId: Int? = null,
    val relatedTaskId: Int? = null,
    val dueAtMillis: Long? = null,
    val status: ActionItemStatus = ActionItemStatus.OPEN,
    val createdAtMillis: Long,
)

@Serializable
enum class ActionItemStatus { OPEN, DONE }

interface ActionItemRepository {
    fun all(): List<ActionItem>
    fun find(id: Int): ActionItem?
    fun save(item: ActionItem): ActionItem
    fun delete(id: Int): Boolean
    fun nextId(): Int
}
