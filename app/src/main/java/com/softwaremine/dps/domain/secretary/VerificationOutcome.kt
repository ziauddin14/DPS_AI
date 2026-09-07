package com.softwaremine.dps.domain.secretary

/**
 * The result of independently observing and comparing a tool's claimed
 * outcome against the real state it was supposed to produce (M7).
 *
 * ## Why this is not [com.softwaremine.dps.domain.tool.ToolResult]
 * [com.softwaremine.dps.domain.tool.ToolResult.Success] means "the tool's
 * `execute()` returned this" — a claim about the tool call itself, decided
 * entirely by the tool at the moment it returns. This type means something
 * strictly later and separate: "a fresh read of the record that call claims
 * to have produced was compared against what was actually requested."
 * Overloading `ToolResult` with this second meaning would make every
 * existing caller of `ToolResult.isSuccess` — recorded memory, episodic
 * history, multi-step continuation before M7 — silently start meaning
 * something new; keeping the two types separate means none of that
 * pre-existing code needs to know M7 exists at all unless it explicitly
 * asks.
 *
 * ## Why exactly four cases, and no severity/ordering between them
 * [Mismatch], [NotFound] and [ObservationFailed] all mean the same thing to
 * every consumer of this type: the action could not be confirmed, so stop
 * rather than assume. They are kept distinct only because they say
 * different, individually honest things to the user (see
 * [com.softwaremine.dps.ai.intent.ToolResponseGenerator]'s own M7 handling)
 * — not because the orchestration layer ever treats one as "worse" than
 * another. There is deliberately no fifth case for "verification not
 * attempted" — that is `null` at the call site (see
 * [com.softwaremine.dps.data.android.secretary.ExecutionVerifier]'s own
 * doc), never a member of this closed set, because "not attempted" and
 * "attempted and inconclusive" ([ObservationFailed]) are genuinely
 * different facts.
 *
 * ## Never persisted
 * Only [PendingVerification] — the *intent* to verify, captured before the
 * read — survives process death. This type is the transient result of
 * actually running that verification, produced and consumed within one
 * method call; see [PendingVerification]'s own doc for why persisting the
 * request is necessary but persisting the answer is not.
 */
sealed interface VerificationOutcome {

    /** Observed state matched every expected value that was checked. */
    data object Verified : VerificationOutcome

    /**
     * The record was found, but at least one checked value differs from
     * what was requested.
     *
     * @param expected what the intent asked for, keyed by field name.
     * @param observed what was actually read back, same keys as [expected].
     *   Both maps use the same keys deliberately, so a caller can render a
     *   side-by-side diff without knowing anything about which fields this
     *   particular verification checked.
     */
    data class Mismatch(
        val expected: Map<String, String>,
        val observed: Map<String, String>,
    ) : VerificationOutcome

    /** No record exists at the identity the tool itself reported creating. */
    data object NotFound : VerificationOutcome

    /**
     * The observation attempt itself failed — an exception from the
     * repository/provider, not an absent record. Distinct from [NotFound]:
     * the record may well exist and be correct; DPS simply could not check.
     *
     * @param reason diagnostic only, mirroring [com.softwaremine.dps.domain.tool.ToolResult.Error.cause] —
     *   never shown to the user verbatim (AI Rules 1 and 2).
     */
    data class ObservationFailed(val reason: String) : VerificationOutcome
}
