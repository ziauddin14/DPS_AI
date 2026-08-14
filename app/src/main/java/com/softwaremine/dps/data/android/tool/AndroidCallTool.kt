package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.intent.DialIntentBuilder
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
 * Opens the system dialer with a verified number pre-filled. **Never dials.**
 *
 * ## The confirmation flow is the feature
 * ```
 * AI: "Call Bilal Developer (+92 300 1234567)?"
 *   ↓  resolve the contact (or trust an explicit phone number the user gave)
 *   ↓  build the ACTION_DIAL Intent
 *   ↓  the system dialer opens with the number in the input field
 *   ↓  THE USER presses call
 * ```
 *
 * Same discipline as [PrepareWhatsAppMessageTool] and [PrepareEmailTool], and
 * the same reasoning: a call placed on someone's behalf cannot be recalled.
 * There is no code path here that places a call — see [DialIntentBuilder]'s
 * doc for why `ACTION_DIAL`, never `ACTION_CALL`, is the only capability
 * this class has, and why no `CALL_PHONE` permission is declared.
 *
 * ## Where the number actually comes from
 * In the normal flow, [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]
 * has already resolved a spoken name to a real [com.softwaremine.dps.domain.contact.Contact]
 * before this tool ever runs, so `phone` arrives here already grounded — see
 * that class's `applyResolvedContactData`/`applyResolvedContact` docs for why
 * that overwrite is unconditional for a call, unlike WhatsApp/email. This
 * tool never re-trusts a model-supplied phone number over a resolved
 * contact's own; [resolveContact] exists only as the same second line of
 * defence [PrepareWhatsAppMessageTool] has, for a call reaching this tool
 * with a name but no phone already attached.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `place_call` | `contact` or `phone` | Dialer opened, awaiting the user |
 *
 * ## Permission
 * [DpsPermission.READ_CONTACTS], required only to resolve a name — the same
 * set [PrepareWhatsAppMessageTool]/[PrepareEmailTool] declare. `ACTION_DIAL`
 * itself needs no runtime permission; that is exactly what distinguishes it
 * from `ACTION_CALL`'s `CALL_PHONE`, which this class deliberately never
 * requests.
 *
 * ## Dependencies
 * [ContactRepository], [ContactResolver], [DialIntentBuilder], [IntentLauncher].
 */
class AndroidCallTool(
    private val repository: ContactRepository,
    private val resolver: ContactResolver,
    private val launcher: IntentLauncher,
) : AndroidTool {

    override val id: ToolId = ToolId.PHONE

    override val operations: Set<String> = setOf(OP_PLACE_CALL)

    override val requiredPermissions: Set<DpsPermission> = setOf(DpsPermission.READ_CONTACTS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_PLACE_CALL -> placeCall(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun placeCall(call: ToolCall): ToolResult {
        val explicitPhone = call.argument(ARG_PHONE)?.trim().orEmpty()
        val contactName = call.argument(ARG_CONTACT)?.trim().orEmpty()

        if (explicitPhone.isEmpty() && contactName.isEmpty()) {
            return ToolResult.Failure(
                reason = "Provide either 'contact' or 'phone' to place a call.",
                retryable = false,
            )
        }

        val (phoneNumber, recipientLabel) = if (explicitPhone.isNotEmpty()) {
            explicitPhone to explicitPhone
        } else {
            when (val resolved = resolveContact(contactName)) {
                is Resolution.Failed -> return resolved.result
                is Resolution.Resolved -> resolved.phone to resolved.name
            }
        }

        val digitCount = phoneNumber.count { it.isDigit() }
        if (digitCount < MIN_PHONE_DIGITS) {
            return ToolResult.Failure(
                reason = "\"$phoneNumber\" is not a usable phone number.",
                retryable = false,
            )
        }

        val intent = DialIntentBuilder.build(phoneNumber)

        // Checked before launching so a device with no dialer at all gets a
        // clear answer rather than an ActivityNotFoundException.
        if (!launcher.canHandle(intent)) {
            return ToolResult.Unsupported(reason = "No dialer app is available on this device.")
        }

        return when (val outcome = launcher.launch(intent)) {
            is IntentLauncher.LaunchOutcome.Launched -> ToolResult.Success(
                // Wording is unambiguous about who calls. An assistant that
                // said "calling now" here would be lying.
                summary = "The dialer is open with $recipientLabel's number. Press call to connect.",
                data = mapOf(
                    "recipient" to recipientLabel,
                    "phone" to phoneNumber,
                    "opened_package" to (outcome.targetPackage ?: "unknown"),
                    // Explicit so no downstream consumer can mistake this for
                    // a call that was actually placed.
                    "called" to "false",
                    "awaiting_user_confirmation" to "true",
                ),
            )

            IntentLauncher.LaunchOutcome.NoHandler -> ToolResult.Unsupported(
                reason = "No dialer app is available on this device.",
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
                // Never guessed. Calling the wrong person is not recoverable,
                // so the assistant must ask.
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
        const val OP_PLACE_CALL = "place_call"

        const val ARG_CONTACT = "contact"
        const val ARG_PHONE = "phone"

        /** Shortest plausible international subscriber number. */
        const val MIN_PHONE_DIGITS = 7
    }
}
