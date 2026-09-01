package com.softwaremine.dps.domain.memory

/**
 * One durable record of something DPS actually did (M6) — "created task
 * 'Submit report' at 4:02pm on Tuesday."
 *
 * ## Why this exists, distinct from [ConversationMemory]
 * [ConversationMemory] keeps only the single most recent item of each of
 * seven kinds, overwritten the instant a newer one occurs. [EpisodicMemoryEntry]
 * is the history [ConversationMemory] deliberately never tried to be — every
 * completed action, kept (subject to retention — see
 * [com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryDao]'s
 * own pruning doc), so a later question about something from days ago has
 * somewhere to be answered from.
 *
 * ## Why logging this needs no model involvement
 * Nothing here requires judgment about "is this worth remembering" — an
 * entry is written automatically whenever an action actually succeeds, the
 * exact same trigger
 * [com.softwaremine.dps.ai.memory.ConversationMemoryUpdater.remember] already
 * uses (`ToolResult.Success` only — a failed or declined action changed
 * nothing on the device, so it leaves no episodic trace either). See
 * [com.softwaremine.dps.ai.memory.EpisodicMemoryRecorder].
 *
 * ## Why [summary] is a short templated string, not raw model output
 * Mirrors [com.softwaremine.dps.ai.intent.ToolResponseGenerator]'s own
 * "templated, not model-authored" reasoning — a deterministic, reviewable
 * sentence the recorder itself builds from the completed [DpsIntent][com.softwaremine.dps.domain.intent.DpsIntent]/
 * `ToolResult`, not free text an LLM could get wrong or drift in wording
 * across identical actions.
 *
 * ## Dependencies
 * None. Pure Kotlin — see [SemanticFact]'s own doc for why the Room-annotated
 * mirror of this shape lives in `data.android`, not here.
 */
data class EpisodicMemoryEntry(
    /** Stable identity, assigned once at creation. Never reused. */
    val id: Long,

    val timestampMillis: Long,

    /** A short, deterministic description of what happened — e.g. "Created task \"Submit report\"." */
    val summary: String,

    /** The tool that performed the action, by its wire name (e.g. "task") — `null` only if ever logged for something outside the tool layer. */
    val toolId: String?,
)
