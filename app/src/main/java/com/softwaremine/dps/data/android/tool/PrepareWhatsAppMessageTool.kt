package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.data.android.intent.WhatsAppIntentBuilder
import com.softwaremine.dps.domain.contact.ContactMatch
import com.softwaremine.dps.domain.contact.ContactRepository
import com.softwaremine.dps.domain.contact.ContactResolver
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Opens WhatsApp with a message pre-filled. **Never sends.**
 *
 * ## The confirmation flow is the feature
 * ```
 * AI: "Send a reminder to Abdul?"
 *   ↓  resolve the contact
 *   ↓  build the conversation Intent
 *   ↓  WhatsApp opens with the message in the input field
 *   ↓  THE USER presses send
 * ```
 *
 * There is no code path that sends a message, and that is deliberate rather
 * than a limitation of the approach. `development_rule.md` Rule 8 and
 * `PRD.md`'s Human Confirmation principle both require it, and the reasoning is
 * plain: a message sent on someone's behalf cannot be recalled. An assistant
 * that is 99% accurate about recipients is still one that will eventually send
 * a private message to the wrong person.
 *
 * Handing the final tap to the user makes that impossible **structurally** —
 * not by policy, not by a check that could be removed, but because the
 * capability to send does not exist in this codebase.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `prepare_message` | `contact` or `phone`, `message` | WhatsApp opened, awaiting the user |
 * | `send_message` | *(alias — still only prepares)* | as above |
 *
 * `send_message` is accepted because a model asked to message someone will
 * naturally emit it. Rejecting it would make the assistant look broken; instead
 * it maps to the same preparation, and the returned summary states explicitly
 * that the user must press send. The tool's honesty lives in what it does and
 * in what it reports, not in refusing a word.
 *
 * ## Permission
 * [DpsPermission.READ_CONTACTS], required only to resolve a name. A call
 * supplying `phone` directly still declares it — the executor gates per tool,
 * not per argument — which is the conservative direction: DPS asks for contacts
 * access before it can read contacts, never after.
 *
 * ## Dependencies
 * [ContactRepository], [ContactResolver], [WhatsAppIntentBuilder],
 * [IntentLauncher].
 */
class PrepareWhatsAppMessageTool(
    private val repository: ContactRepository,
    private val resolver: ContactResolver,
    private val launcher: IntentLauncher,
) : AndroidTool {

    override val id: ToolId = ToolId.WHATSAPP

    override val operations: Set<String> = setOf(OP_PREPARE, OP_SEND_ALIAS)

    override val requiredPermissions: Set<DpsPermission> = setOf(DpsPermission.READ_CONTACTS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_PREPARE, OP_SEND_ALIAS -> prepare(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun prepare(call: ToolCall): ToolResult {
        val message = when (val parsed = ToolArguments.required(call, ARG_MESSAGE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val explicitPhone = call.argument(ARG_PHONE)?.trim().orEmpty()
        val contactName = call.argument(ARG_CONTACT)?.trim().orEmpty()

        if (explicitPhone.isEmpty() && contactName.isEmpty()) {
            return ToolResult.Failure(
                reason = "Provide either 'contact' or 'phone' to message someone.",
                retryable = false,
            )
        }

        // Resolve to a number and a name to show the user.
        val (phoneNumber, recipientLabel) = if (explicitPhone.isNotEmpty()) {
            explicitPhone to explicitPhone
        } else {
            when (val resolved = resolveContact(contactName)) {
                is Resolution.Failed -> return resolved.result
                is Resolution.Resolved -> resolved.phone to resolved.name
            }
        }

        val digits = WhatsAppIntentBuilder.normaliseForWhatsApp(phoneNumber)
        if (digits.length < MIN_PHONE_DIGITS) {
            return ToolResult.Failure(
                reason = "\"$phoneNumber\" is not a usable phone number.",
                retryable = false,
            )
        }

        val intent = WhatsAppIntentBuilder.buildConversationIntent(digits, message)

        // Checked before launching so a device without WhatsApp gets a clear
        // answer rather than an ActivityNotFoundException.
        if (!launcher.canHandle(intent)) {
            return ToolResult.Unsupported(
                reason = "WhatsApp is not installed on this device.",
            )
        }

        return when (val outcome = launcher.launch(intent)) {
            is IntentLauncher.LaunchOutcome.Launched -> ToolResult.Success(
                // Wording is unambiguous about who sends. An assistant that
                // said "message sent" here would be lying.
                summary = "WhatsApp is open with your message to $recipientLabel. " +
                    "Press send in WhatsApp to deliver it.",
                data = mapOf(
                    "recipient" to recipientLabel,
                    "phone" to digits,
                    "message" to message,
                    "opened_package" to (outcome.targetPackage ?: WhatsAppIntentBuilder.PACKAGE_NAME),
                    // Explicit so no downstream consumer can mistake this for a
                    // completed send.
                    "sent" to "false",
                    "awaiting_user_confirmation" to "true",
                ),
            )

            IntentLauncher.LaunchOutcome.NoHandler -> ToolResult.Unsupported(
                reason = "WhatsApp is not installed on this device.",
            )

            is IntentLauncher.LaunchOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )
        }
    }

    /** Narrow result type so contact resolution reads linearly at the call site. */
    private sealed interface Resolution {
        data class Resolved(val phone: String, val name: String) : Resolution
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
                val phone = match.contact.primaryPhone
                    ?: return Resolution.Failed(
                        ToolResult.Failure(
                            reason = "${match.contact.displayName} has no phone number saved.",
                            retryable = false,
                        ),
                    )
                Resolution.Resolved(phone, match.contact.displayName)
            }

            is ContactMatch.Ambiguous -> Resolution.Failed(
                // Never guessed. Sending a private message to the wrong person
                // is not recoverable, so the assistant must ask.
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
        const val OP_PREPARE = "prepare_message"
        const val OP_SEND_ALIAS = "send_message"

        const val ARG_CONTACT = "contact"
        const val ARG_PHONE = "phone"
        const val ARG_MESSAGE = "message"

        /** Shortest plausible international subscriber number. */
        const val MIN_PHONE_DIGITS = 7
    }
}
