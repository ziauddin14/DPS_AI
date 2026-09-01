package com.softwaremine.dps.domain.memory

/**
 * A durable fact DPS was explicitly told to remember (M6) — "Bilal mera
 * developer hai, yaad rakhna."
 *
 * ## Why this is a separate concern from [ConversationMemory]
 * [ConversationMemory] answers "what did we just discuss" — nine single
 * slots, each replaced the instant a newer one of the same kind occurs, by
 * design (see that class's own doc). [SemanticFact] answers a different
 * question entirely: "what do I know about the user's life that should
 * still be true next week, regardless of what's been discussed since."
 * Nothing about [ConversationMemory]'s existing shape or behavior changes
 * for M6 — this is an addition alongside it, not a replacement.
 *
 * ## Why [subject]/[factText] are free text, not a structured schema
 * A richer, typed representation (a relationship graph, a triple store)
 * was considered and rejected as exactly the kind of speculative
 * "knowledge base" M6's own approved plan explicitly declines to build —
 * nothing in M6's acceptance criteria needs more than "a fact, about a
 * named subject, stated once." [subject] is deliberately not resolved
 * against [com.softwaremine.dps.domain.contact.Contact] — a fact's subject
 * ("the project", "meetings") is not always a person.
 *
 * ## Why conflicting facts are not auto-merged
 * A later statement about the same [subject] does not overwrite or merge
 * with an earlier one — both simply exist. Automatically detecting "this is
 * a correction, not a new fact" would require either model judgment (a new
 * source of non-determinism the M6 plan explicitly declines to introduce
 * for this purpose) or a brittle phrase-heuristic with a real risk of
 * silently discarding a still-valid fact on a false match. Correcting a
 * fact is therefore two existing operations — forget the outdated one,
 * state the new one — never a third, automatic mechanism.
 *
 * ## Deletion is real, not a status flag
 * There is no "inactive"/soft-delete concept here. Forgetting a fact
 * removes it outright, mirroring [PersistentMemoryStore][com.softwaremine.dps.data.android.memory.PersistentMemoryStore]'s
 * own `clear()` — a user asking DPS to forget something should mean it is
 * actually gone, not merely hidden.
 *
 * ## Never touched by [SecretaryOrchestrator.reset][com.softwaremine.dps.ai.secretary.SecretaryOrchestrator.reset]
 * `reset()`'s scope is deliberately unchanged by M6 — it still only forgets
 * the current conversation ([ConversationMemory] + the M5-B recovery
 * record). Conflating "start a new chat" with "erase everything DPS has
 * ever been told to remember" would be a surprising, destructive scope
 * change to an already-trusted, low-friction command. Forgetting a
 * [SemanticFact] has its own explicit, separate path — see
 * [com.softwaremine.dps.data.android.tool.AndroidMemoryTool]'s `forget_fact`
 * operation.
 *
 * ## Privacy
 * [factText] must never carry a password, PIN, OTP, card number, bank/IBAN
 * account number, or national ID number, regardless of what the user said —
 * enforced at the persistence boundary itself
 * ([com.softwaremine.dps.data.android.memory.MemoryPrivacyGuard]), not
 * merely trusted to whatever asked to store it. A fact whose [subject] is a
 * third party (e.g. "Bilal"), not the user, is a genuinely new category of
 * data this codebase has not stored before — it stays device-only, exactly
 * like everything else DPS remembers; nothing here is ever transmitted.
 *
 * ## Dependencies
 * None. Pure Kotlin — no Android, no Room annotations. The Room-annotated
 * mirror of this shape is [com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity];
 * mapping between the two is [com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity.toDomain]/`.toEntity()`,
 * the same data-layer/domain-layer split already established by
 * [com.softwaremine.dps.data.android.calendar.CalendarWriter.EventOccurrence]
 * versus [com.softwaremine.dps.domain.proactive.UpcomingEventOccurrence].
 */
data class SemanticFact(
    /** Stable identity, assigned once at creation. Never reused. */
    val id: Long,

    /** Who or what this fact is about, as the user said it — e.g. "Bilal." Free text, never resolved to a Contact. */
    val subject: String,

    /** The fact itself, as a short standalone sentence fragment — e.g. "is my developer." */
    val factText: String,

    /** The user's original sentence, kept for transparency ("what exactly did I tell you") — not re-parsed for anything. */
    val sourceUtterance: String?,

    val createdAtMillis: Long,

    /** Equal to [createdAtMillis] until a future milestone adds a real update path; M6 never updates a fact in place — see this class's own doc on conflicting facts. */
    val updatedAtMillis: Long,
)
