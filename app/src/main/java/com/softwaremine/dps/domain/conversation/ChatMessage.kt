package com.softwaremine.dps.domain.conversation

import com.softwaremine.dps.core.error.DpsError

/**
 * One turn in a conversation.
 *
 * ## Purpose
 * The atomic unit the conversation pipeline reads and writes. Deliberately
 * minimal: it carries what is needed to render a turn and to rebuild a prompt,
 * and nothing else.
 *
 * ## What is intentionally absent
 * There is no `toolCalls`, no `entities`, no `intent`, no memory reference.
 * The brief scopes Day 02 to the conversation pipeline only — no memory, no
 * secretary logic, no tool calling. Adding speculative fields now would mean
 * carrying unused state through every layer and guessing at shapes that Day 03
 * will define properly with real requirements in hand.
 *
 * ## Future extensions
 * Day 03 adds an optional tool-call payload; Day 04+ adds a memory reference.
 * Both are additive to this class and require no change to the pipeline.
 *
 * ## Dependencies
 * [DpsError]. Pure Kotlin.
 */
data class ChatMessage(

    /** Stable identifier. Used by Compose for keyed recomposition. */
    val id: String,

    /** Who produced this turn. */
    val role: MessageRole,

    /**
     * Message text.
     *
     * While streaming, this grows token by token and [status] is
     * [MessageStatus.Streaming].
     */
    val content: String,

    /** Creation time, epoch millis. */
    val timestampEpochMillis: Long,

    /** Delivery state. See [MessageStatus]. */
    val status: MessageStatus = MessageStatus.Complete,
) {
    /** `true` if this message is still being produced. */
    val isStreaming: Boolean get() = status is MessageStatus.Streaming

    /** `true` if this message should be included when building a prompt. */
    val isPromptEligible: Boolean
        get() = status is MessageStatus.Complete && content.isNotBlank()
}

/**
 * The author of a turn.
 *
 * Maps onto the three roles every supported chat template understands. Note
 * that Mistral has no distinct system role — its template folds the system
 * prompt into the first user turn. That asymmetry is handled in the template
 * layer, not here.
 */
enum class MessageRole {
    /** Persona and behavioural contract. Never rendered in the UI. */
    SYSTEM,

    /** Input from the person using DPS. */
    USER,

    /** Output from the model. */
    ASSISTANT,
}

/**
 * Delivery state of a message.
 *
 * ## Why this exists rather than a boolean
 * A streaming, on-device assistant has genuinely partial messages: text is
 * visible and growing while the model is still working, and generation can fail
 * *after* useful text has already been shown. A boolean cannot express "this
 * message is real, incomplete, and will never finish" — which is exactly the
 * state the user needs to see when a generation fails mid-answer.
 */
sealed interface MessageStatus {

    /** Accepted, not yet sent to the runtime. */
    data object Pending : MessageStatus

    /** Being generated. [ChatMessage.content] holds what has arrived so far. */
    data object Streaming : MessageStatus

    /** Finished normally. Only these messages are eligible for prompt rebuilding. */
    data object Complete : MessageStatus

    /**
     * Generation failed. Any [ChatMessage.content] already produced is retained
     * and remains valid — the user keeps the partial answer.
     */
    data class Failed(val error: DpsError) : MessageStatus
}
