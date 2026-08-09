package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.intent.EmailIntentBuilder
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.domain.contact.ContactMatch
import com.softwaremine.dps.domain.contact.ContactRepository
import com.softwaremine.dps.domain.contact.ContactResolver
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Opens an email composer with the message pre-filled. **Never sends.**
 *
 * ## Same philosophy as WhatsApp
 * ```
 * AI: "Email the report to Abdul?"
 *   ↓  resolve the recipient
 *   ↓  build a mailto: composer Intent
 *   ↓  the email app opens, pre-filled
 *   ↓  THE USER presses send
 * ```
 *
 * No code path sends an email. Email is arguably the less forgiving of the two:
 * a WhatsApp message to the wrong person is embarrassing, an email to the wrong
 * address can leak a document to an organisation. The final action stays with
 * the user.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `compose_email` | `to` or `contact`, `subject`, `body` | composer opened |
 * | `send_email` | *(alias — still only composes)* | as above |
 *
 * ## Recipient resolution
 * `to` accepts a literal address. `contact` resolves a name through the shared
 * [ContactResolver], and ambiguity is reported rather than guessed — the same
 * rule as WhatsApp, for the same reason.
 *
 * ## Permission
 * [DpsPermission.READ_CONTACTS], needed to resolve a name. No email permission
 * exists or is required: `ACTION_SENDTO` hands off to an app the user already
 * trusts with their mail, which is precisely why it is preferable to any
 * approach involving credentials.
 *
 * ## Dependencies
 * [ContactRepository], [ContactResolver], [EmailIntentBuilder], [IntentLauncher].
 */
class PrepareEmailTool(
    private val repository: ContactRepository,
    private val resolver: ContactResolver,
    private val launcher: IntentLauncher,
) : AndroidTool {

    override val id: ToolId = ToolId.GMAIL

    override val operations: Set<String> = setOf(OP_COMPOSE, OP_SEND_ALIAS)

    override val requiredPermissions: Set<DpsPermission> = setOf(DpsPermission.READ_CONTACTS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_COMPOSE, OP_SEND_ALIAS -> compose(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun compose(call: ToolCall): ToolResult {
        val explicitTo = call.argument(ARG_TO)?.trim().orEmpty()
        val contactName = call.argument(ARG_CONTACT)?.trim().orEmpty()

        if (explicitTo.isEmpty() && contactName.isEmpty()) {
            return ToolResult.Failure(
                reason = "Provide either 'to' or 'contact' to address the email.",
                retryable = false,
            )
        }

        val (address, recipientLabel) = if (explicitTo.isNotEmpty()) {
            if (!EmailIntentBuilder.isPlausibleAddress(explicitTo)) {
                // Catches a model passing a name where an address belongs,
                // which would otherwise open a composer addressed to nothing.
                return ToolResult.Failure(
                    reason = "\"$explicitTo\" is not a valid email address.",
                    retryable = false,
                )
            }
            explicitTo to explicitTo
        } else {
            when (val resolved = resolveContact(contactName)) {
                is Resolution.Failed -> return resolved.result
                is Resolution.Resolved -> resolved.address to resolved.name
            }
        }

        val subject = call.argument(ARG_SUBJECT)
        val body = call.argument(ARG_BODY)

        val intent = EmailIntentBuilder.buildComposeIntent(
            recipients = listOf(address),
            subject = subject,
            body = body,
        )

        if (!launcher.canHandle(intent)) {
            return ToolResult.Unsupported(
                reason = "No email app is set up on this device.",
            )
        }

        return when (val outcome = launcher.launch(intent)) {
            is IntentLauncher.LaunchOutcome.Launched -> ToolResult.Success(
                summary = "Your email to $recipientLabel is ready. " +
                    "Press send in your email app to deliver it.",
                data = buildMap {
                    put("recipient", recipientLabel)
                    put("to", address)
                    subject?.takeIf { it.isNotBlank() }?.let { put("subject", it) }
                    put("opened_package", outcome.targetPackage ?: "email app")
                    put("sent", "false")
                    put("awaiting_user_confirmation", "true")
                },
            )

            IntentLauncher.LaunchOutcome.NoHandler -> ToolResult.Unsupported(
                reason = "No email app is set up on this device.",
            )

            is IntentLauncher.LaunchOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )
        }
    }

    private sealed interface Resolution {
        data class Resolved(val address: String, val name: String) : Resolution
        data class Failed(val result: ToolResult) : Resolution
    }

    private suspend fun resolveContact(name: String): Resolution {
        val outcome = repository.searchByName(name)
        val contacts = when (outcome) {
            is ContactRepository.Outcome.Found -> outcome.contacts
            ContactRepository.Outcome.NoProvider -> return Resolution.Failed(
                ToolResult.Unsupported("This device has no contacts available."),
            )
            is ContactRepository.Outcome.Failed -> return Resolution.Failed(
                ToolResult.Failure(outcome.reason, retryable = true),
            )
        }

        return when (val match = resolver.resolveByName(name, contacts)) {
            is ContactMatch.Single -> {
                val address = match.contact.primaryEmail
                    ?: return Resolution.Failed(
                        ToolResult.Failure(
                            reason = "${match.contact.displayName} has no email address saved.",
                            retryable = false,
                        ),
                    )
                Resolution.Resolved(address, match.contact.displayName)
            }

            is ContactMatch.Ambiguous -> Resolution.Failed(
                ToolResult.Failure(
                    reason = "Several contacts match \"$name\": " +
                        match.candidates.joinToString(", ") { it.displayName } +
                        ". Which one?",
                    retryable = false,
                ),
            )

            ContactMatch.None -> Resolution.Failed(
                ToolResult.Failure(reason = "No contact named \"$name\".", retryable = false),
            )
        }
    }

    private companion object {
        const val OP_COMPOSE = "compose_email"
        const val OP_SEND_ALIAS = "send_email"

        const val ARG_TO = "to"
        const val ARG_CONTACT = "contact"
        const val ARG_SUBJECT = "subject"
        const val ARG_BODY = "body"
    }
}
