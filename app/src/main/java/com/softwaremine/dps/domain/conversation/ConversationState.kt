package com.softwaremine.dps.domain.conversation

/**
 * The complete state of one conversation.
 *
 * ## Purpose
 * A single immutable snapshot describing everything the UI needs to render the
 * chat surface. Exactly one component may produce new instances of it
 * ([com.softwaremine.dps.ai.conversation.ConversationManager]); everything else
 * observes it through a `StateFlow`.
 *
 * ## Why immutable, and why single-writer
 * Token streaming updates state tens of times per second while the user may
 * simultaneously send input, cancel, or reset. With shared mutable state, that
 * is a race with no natural place to look when it misbehaves. With one writer
 * and immutable snapshots, every state transition has exactly one origin, which
 * is the property that makes the pipeline debuggable rather than merely
 * functional.
 *
 * It also gives Compose correct recomposition semantics for free.
 *
 * ## Responsibilities
 * Describe a conversation. It holds no behaviour and performs no mutation.
 *
 * ## Future extensions
 * Persistence via Room, and a `memoryContext` reference once the memory layer
 * exists. Both are additive.
 *
 * ## Dependencies
 * [ChatMessage]. Pure Kotlin.
 */
data class ConversationState(

    /** Stable conversation identifier. */
    val id: String,

    /**
     * Turns in chronological order.
     *
     * Includes the [MessageRole.SYSTEM] message when one is present; the UI
     * filters it out for display rather than the model omitting it, so that
     * prompt construction always sees the true conversation.
     */
    val messages: List<ChatMessage> = emptyList(),

    /**
     * `true` while a response is being generated.
     *
     * Distinct from any individual message's [MessageStatus] because the UI
     * needs to disable input during generation, which is a property of the
     * conversation rather than of one message.
     */
    val isGenerating: Boolean = false,

    /** Creation time, epoch millis. */
    val createdAtEpochMillis: Long = 0L,

    /** Last modification time, epoch millis. */
    val updatedAtEpochMillis: Long = 0L,
) {

    /** Turns excluding the system prompt — what the UI renders. */
    val visibleMessages: List<ChatMessage>
        get() = messages.filter { it.role != MessageRole.SYSTEM }

    /** `true` when there is nothing for the user to see yet. */
    val isEmpty: Boolean get() = visibleMessages.isEmpty()

    /** The most recent turn, or `null`. */
    val lastMessage: ChatMessage? get() = messages.lastOrNull()

    /** The message currently streaming, if any. */
    val streamingMessage: ChatMessage?
        get() = messages.lastOrNull { it.isStreaming }

    companion object {
        /** An empty conversation. Used as the initial `StateFlow` value. */
        fun empty(id: String): ConversationState = ConversationState(id = id)
    }
}
