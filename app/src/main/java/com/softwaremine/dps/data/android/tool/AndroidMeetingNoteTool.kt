package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.productivity.MeetingNote
import com.softwaremine.dps.domain.productivity.MeetingNoteRepository
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Creates and lists meeting notes (Day 06).
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_meeting` | `title` or `notes` (one required), `notes`, `participants` (comma-separated), `date` | `meeting_id` |
 * | `list_meetings` | `date`, `person` (both optional filters) | matching notes |
 *
 * ## Why free-form notes carry the content, not a parsed "decision" field
 * "Aaj Hassan bhai ke saath DBPMS meeting hui. Unhon ne kaha Friday tak
 * prototype complete karna hai" states a decision inside ordinary prose. A
 * 1.5B on-device classifier extracting that reliably into a separate
 * structured field would routinely be wrong, and the Day 06 brief forbids
 * inventing a decision or deadline. `notes` therefore keeps what the user
 * said, verbatim — see [MeetingNote]'s own doc.
 *
 * ## Permissions
 * None.
 *
 * ## Dependencies
 * [MeetingNoteRepository], `java.time`. No direct Android imports.
 */
class AndroidMeetingNoteTool(
    private val repository: MeetingNoteRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.MEETING

    override val operations: Set<String> = setOf(OP_CREATE, OP_LIST)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE -> create(call)
        OP_LIST -> list(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun create(call: ToolCall): ToolResult {
        val notes = call.argument(ARG_NOTES)?.trim().takeUnless { it.isNullOrEmpty() }
        val title = call.argument(ARG_TITLE)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: notes?.take(TITLE_FALLBACK_LENGTH)
            ?: return ToolResult.Failure(
                reason = "I need at least a title or some notes to save a meeting.",
                retryable = false,
            )

        val date = call.argument(ARG_DATE)?.let(::parseIsoDate) ?: LocalDate.now(zone)
        val participants = call.argument(ARG_PARTICIPANTS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val id = repository.nextId()
        repository.save(
            MeetingNote(
                id = id,
                title = title,
                dateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                participants = participants,
                notes = notes,
                createdAtMillis = now(),
            ),
        )

        return ToolResult.Success(
            summary = "Meeting note \"$title\" saved.",
            data = mapOf("meeting_id" to id.toString()),
        )
    }

    private fun list(call: ToolCall): ToolResult {
        val dateFilter = call.argument(ARG_DATE)?.let(::parseIsoDate)
        val personFilter = call.argument(ARG_PERSON)?.trim()?.lowercase()

        var results = repository.all()
        if (dateFilter != null) {
            val start = dateFilter.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = dateFilter.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            results = results.filter { it.dateMillis in start until end }
        }
        if (!personFilter.isNullOrEmpty()) {
            results = results.filter { meeting -> meeting.participants.any { it.lowercase().contains(personFilter) } }
        }

        if (results.isEmpty()) return ToolResult.Success(summary = "No matching meetings found.")

        val data = buildMap {
            put("count", results.size.toString())
            results.forEachIndexed { index, meeting ->
                put("meeting_${index}_id", meeting.id.toString())
                put("meeting_${index}_title", meeting.title)
                meeting.notes?.let { put("meeting_${index}_notes", it) }
            }
        }

        val summary = results.joinToString("\n") { meeting ->
            val notesPart = meeting.notes?.let { " — $it" }.orEmpty()
            "${meeting.title}$notesPart"
        }

        return ToolResult.Success(summary = summary, data = data)
    }

    private fun parseIsoDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

    private companion object {
        const val OP_CREATE = "create_meeting"
        const val OP_LIST = "list_meetings"

        const val ARG_TITLE = "title"
        const val ARG_NOTES = "notes"
        const val ARG_PARTICIPANTS = "participants"
        const val ARG_DATE = "date"
        const val ARG_PERSON = "person"

        const val TITLE_FALLBACK_LENGTH = 60
    }
}
