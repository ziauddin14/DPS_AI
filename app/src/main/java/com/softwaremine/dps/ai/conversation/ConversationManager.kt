package com.softwaremine.dps.ai.conversation

import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.domain.conversation.ChatMessage
import com.softwaremine.dps.domain.conversation.ConversationState
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Owns conversation state. The single writer.
 *
 * ## Purpose
 * The "Conversation Manager" stage of the Phase G pipeline, and the only
 * component permitted to produce a new [ConversationState]. Everything else —
 * the session manager, the ViewModel, the UI — observes [state] and never
 * mutates it.
 *
 * ## Why a single writer, enforced by design
 * During generation this state changes tens of times per second while the user
 * may simultaneously send input, press stop, or reset the session. Those are
 * genuinely concurrent events arriving from different coroutines.
 *
 * With several components able to mutate shared state, that is a data race
 * whose symptoms — a dropped token, a message stuck in `Streaming`, a reset
 * that half-applies — are intermittent and nearly impossible to attribute. With
 * one writer and immutable snapshots, every transition has exactly one origin,
 * so a wrong state has exactly one place to look.
 *
 * All mutations go through [MutableStateFlow.update], which applies its
 * transform atomically, so no external locking is required.
 *
 * ## Responsibilities
 * - Hold the current [ConversationState].
 * - Append user turns and assistant turns.
 * - Stream tokens into the in-flight assistant turn.
 * - Mark turns complete or failed.
 * - Reset the conversation.
 *
 * ## Explicit non-responsibilities
 * No prompt building, no runtime access, no persistence, no memory. It is a
 * state container with a well-defined transition vocabulary — nothing more.
 *
 * ## Future extensions
 * Persistence via Room, and multi-conversation support. Both are additive:
 * persistence observes [state]; multi-conversation makes the single flow a map
 * keyed by conversation id.
 *
 * ## Dependencies
 * `domain` conversation types and coroutines. No Android framework, so this is
 * fully JVM-unit-testable.
 */
class ConversationManager(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _state = MutableStateFlow(
        ConversationState.empty(newId()).copy(
            createdAtEpochMillis = nowMillis(),
            updatedAtEpochMillis = nowMillis(),
        ),
    )

    /** The current conversation. The UI's single source of truth. */
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    /** Convenience accessor for callers that need a synchronous snapshot. */
    val current: ConversationState get() = _state.value

    /**
     * Appends a user turn.
     *
     * @return the created message, whose id the caller may retain.
     */
    fun appendUserMessage(content: String): ChatMessage {
        val message = ChatMessage(
            id = newId(),
            role = MessageRole.USER,
            content = content,
            timestampEpochMillis = nowMillis(),
            status = MessageStatus.Complete,
        )
        _state.update { state ->
            state.copy(
                messages = state.messages + message,
                updatedAtEpochMillis = nowMillis(),
            )
        }
        return message
    }

    /**
     * Opens an empty assistant turn in [MessageStatus.Streaming] and marks the
     * conversation as generating.
     *
     * Created empty and *before* the first token so the UI can immediately show
     * a typing indicator anchored to a real message. On-device generation has
     * meaningful time-to-first-token; without this the interface appears frozen
     * for a second or more after the user hits send.
     *
     * @return the placeholder message; pass its id to [appendToken].
     */
    fun beginAssistantMessage(): ChatMessage {
        val message = ChatMessage(
            id = newId(),
            role = MessageRole.ASSISTANT,
            content = "",
            timestampEpochMillis = nowMillis(),
            status = MessageStatus.Streaming,
        )
        _state.update { state ->
            state.copy(
                messages = state.messages + message,
                isGenerating = true,
                updatedAtEpochMillis = nowMillis(),
            )
        }
        return message
    }

    /**
     * Replaces the streaming message's content with [text].
     *
     * Takes the full accumulated text rather than a delta. The caller
     * ([com.softwaremine.dps.ai.session.AiSessionManager]) already accumulates
     * raw tokens in order to run them through
     * [com.softwaremine.dps.ai.parser.ResponseParser], which must see the whole
     * string to withhold partial control tokens. Passing deltas here would mean
     * accumulating in two places, and two accumulators of the same stream will
     * eventually disagree.
     */
    fun appendToken(messageId: String, text: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId) message.copy(content = text) else message
                },
                updatedAtEpochMillis = nowMillis(),
            )
        }
    }

    /** Marks the assistant turn complete with its final [text]. */
    fun completeAssistantMessage(messageId: String, text: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(content = text, status = MessageStatus.Complete)
                    } else {
                        message
                    }
                },
                isGenerating = false,
                updatedAtEpochMillis = nowMillis(),
            )
        }
    }

    /**
     * Marks the assistant turn as failed, preserving whatever text arrived.
     *
     * Partial content is deliberately kept. When generation dies at token 200
     * of 300, the user has already read most of an answer; deleting it in front
     * of them is worse than showing it marked incomplete.
     */
    fun failAssistantMessage(messageId: String, error: DpsError) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(status = MessageStatus.Failed(error))
                    } else {
                        message
                    }
                },
                isGenerating = false,
                updatedAtEpochMillis = nowMillis(),
            )
        }
    }

    /**
     * Removes a message. Used to discard an empty placeholder when generation
     * fails before producing any token — an empty failed bubble is noise.
     */
    fun removeMessage(messageId: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == messageId },
                updatedAtEpochMillis = nowMillis(),
            )
        }
    }

    /** Clears the generating flag without altering messages. */
    fun clearGenerating() {
        _state.update { state -> state.copy(isGenerating = false) }
    }

    /**
     * Discards the conversation and starts a fresh one with a new id.
     *
     * Note this clears *conversation* state only. Releasing the model is
     * [com.softwaremine.dps.domain.ai.AiEngine.unloadModel], and the two are
     * intentionally separate: resetting the conversation while keeping the
     * model resident is the common case, and reloading a model the user is
     * about to use again would cost them 5–8 seconds for nothing.
     */
    fun reset() {
        val now = nowMillis()
        _state.value = ConversationState.empty(newId()).copy(
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }
}
