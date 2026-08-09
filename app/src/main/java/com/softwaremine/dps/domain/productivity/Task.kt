package com.softwaremine.dps.domain.productivity

import kotlinx.serialization.Serializable

/**
 * A to-do item the user asked DPS to track.
 *
 * ## Fields
 * Deliberately the Day 06 brief's minimum, not a project-management schema:
 * a title, optional notes/priority/due date/category, and the timestamps
 * needed to report on it truthfully. [priority] and [category] are advisory
 * strings the user supplied — never inferred.
 *
 * ## Dependencies
 * kotlinx.serialization only, so it can be persisted directly by the Android
 * store without a separate DTO. Pure Kotlin.
 */
@Serializable
data class Task(
    val id: Int,
    val title: String,
    val notes: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority? = null,
    /** Epoch millis. Absent when the user never gave one — never guessed. */
    val dueAtMillis: Long? = null,
    val category: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long? = null,
)

@Serializable
enum class TaskStatus { PENDING, COMPLETED, CANCELLED }

@Serializable
enum class TaskPriority { LOW, NORMAL, HIGH }

/**
 * Persists [Task]s.
 *
 * A pure Kotlin interface so [com.softwaremine.dps.domain.productivity.report.ReportGenerator]
 * and any JVM test can depend on it without an Android [android.content.Context].
 * The Android-backed implementation lives in `data/android/productivity/`.
 */
interface TaskRepository {
    /** Every stored task, in no particular order — callers sort as needed. */
    fun all(): List<Task>

    fun find(id: Int): Task?

    /** Inserts or replaces a task by [Task.id]. */
    fun save(task: Task): Task

    /** Removes a task. Returns whether one was present. */
    fun delete(id: Int): Boolean

    /** Next free id. */
    fun nextId(): Int
}
