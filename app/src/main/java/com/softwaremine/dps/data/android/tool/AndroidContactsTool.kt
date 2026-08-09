package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.contact.ContactMatch
import com.softwaremine.dps.domain.contact.ContactRepository
import com.softwaremine.dps.domain.contact.ContactResolver
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Finds people in the device's address book.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `find_contact` | `name` or `phone` (one required) | matched contact, or candidates |
 * | `list_contacts` | `query` (required), `limit` | matching contacts |
 *
 * ## Ambiguity is an outcome, not a problem to hide
 * When several people match, the candidates are returned rather than one being
 * chosen. This tool exists mainly to feed the WhatsApp and Gmail flows, and
 * quietly picking the first "Ali" of three means a private message reaches the
 * wrong person — irreversibly. Returning candidates forces the assistant to ask.
 *
 * ## Permission
 * Declares [DpsPermission.READ_CONTACTS]; the executor checks it before
 * dispatch, so this class contains no permission logic of its own.
 *
 * ## Dependencies
 * [ContactRepository], [ContactResolver]. No Android imports — provider access
 * lives in the repository.
 */
class AndroidContactsTool(
    private val repository: ContactRepository,
    private val resolver: ContactResolver,
) : AndroidTool {

    override val id: ToolId = ToolId.CONTACTS

    override val operations: Set<String> = setOf(OP_FIND, OP_LIST)

    override val requiredPermissions: Set<DpsPermission> = setOf(DpsPermission.READ_CONTACTS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_FIND -> find(call)
        OP_LIST -> list(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun find(call: ToolCall): ToolResult {
        val name = call.argument(ARG_NAME)?.trim().orEmpty()
        val phone = call.argument(ARG_PHONE)?.trim().orEmpty()

        if (name.isEmpty() && phone.isEmpty()) {
            return ToolResult.Failure(
                reason = "Provide either 'name' or 'phone' to look someone up.",
                retryable = false,
            )
        }

        // Phone is the stronger signal when both are supplied: a number
        // identifies exactly one person, a name may not.
        val match = if (phone.isNotEmpty()) {
            when (val outcome = repository.searchByPhone(phone)) {
                is ContactRepository.Outcome.Found ->
                    resolver.resolveByPhone(phone, outcome.contacts)
                ContactRepository.Outcome.NoProvider -> return noProvider()
                is ContactRepository.Outcome.Failed -> return failed(outcome.reason)
            }
        } else {
            when (val outcome = repository.searchByName(name)) {
                is ContactRepository.Outcome.Found ->
                    resolver.resolveByName(name, outcome.contacts)
                ContactRepository.Outcome.NoProvider -> return noProvider()
                is ContactRepository.Outcome.Failed -> return failed(outcome.reason)
            }
        }

        val searched = phone.ifEmpty { name }
        return match.toToolResult(searched)
    }

    private suspend fun list(call: ToolCall): ToolResult {
        val query = when (val parsed = ToolArguments.required(call, ARG_QUERY)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }
        val limit = ToolArguments.int(call, ARG_LIMIT, ContactRepository.DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        return when (val outcome = repository.searchByName(query, limit)) {
            is ContactRepository.Outcome.Found -> {
                if (outcome.contacts.isEmpty()) {
                    ToolResult.Failure(
                        reason = "No contacts match \"$query\".",
                        retryable = false,
                    )
                } else {
                    ToolResult.Success(
                        summary = "Found ${outcome.contacts.size} contact" +
                            "${if (outcome.contacts.size == 1) "" else "s"} matching \"$query\".",
                        data = flatten(outcome.contacts),
                    )
                }
            }
            ContactRepository.Outcome.NoProvider -> noProvider()
            is ContactRepository.Outcome.Failed -> failed(outcome.reason)
        }
    }

    private fun ContactMatch.toToolResult(query: String): ToolResult = when (this) {
        is ContactMatch.Single -> ToolResult.Success(
            summary = "Found ${contact.displayName}.",
            data = buildMap {
                put("contact_id", contact.id)
                put("name", contact.displayName)
                put("match_strategy", strategy.name)
                contact.primaryPhone?.let { put("phone", it) }
                contact.primaryEmail?.let { put("email", it) }
                put("phone_count", contact.phoneNumbers.size.toString())
            },
        )

        is ContactMatch.Ambiguous -> ToolResult.Success(
            // Success, not Failure: the lookup worked and produced a real
            // answer. "Several people match" is information the assistant acts
            // on by asking, not a failure to recover from.
            summary = "${candidates.size} contacts match \"$query\". Which one?",
            data = flatten(candidates) + ("ambiguous" to "true"),
        )

        ContactMatch.None -> ToolResult.Failure(
            reason = "No contact matches \"$query\".",
            retryable = false,
        )
    }

    /**
     * Flattens contacts into the string map [ToolResult] payloads require.
     *
     * Payloads are `Map<String, String>` because they must survive being
     * rendered into a prompt, so nested structure is indexed instead.
     */
    private fun flatten(contacts: List<Contact>): Map<String, String> = buildMap {
        put("count", contacts.size.toString())
        contacts.forEachIndexed { index, contact ->
            put("contact_${index}_id", contact.id)
            put("contact_${index}_name", contact.displayName)
            contact.primaryPhone?.let { put("contact_${index}_phone", it) }
            contact.primaryEmail?.let { put("contact_${index}_email", it) }
        }
    }

    private fun noProvider() = ToolResult.Unsupported(
        reason = "This device has no contacts available.",
    )

    private fun failed(reason: String) = ToolResult.Failure(reason = reason, retryable = true)

    private companion object {
        const val OP_FIND = "find_contact"
        const val OP_LIST = "list_contacts"

        const val ARG_NAME = "name"
        const val ARG_PHONE = "phone"
        const val ARG_QUERY = "query"
        const val ARG_LIMIT = "limit"

        /** Beyond this, asking for a more specific name beats listing more people. */
        const val MAX_LIMIT = 25
    }
}
