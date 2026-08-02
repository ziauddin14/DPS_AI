package com.softwaremine.dps.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.conversation.ChatMessage
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus

/**
 * The chat surface.
 *
 * ## Purpose
 * The Day 02 interface: a place to type, a transcript, and honest status. It
 * exists to prove the pipeline end to end, not to be the shipping design.
 *
 * ## Scope
 * Deliberately plain. `user_journey.md` describes a voice-first product where
 * the primary interaction is speech and the visible surface is secondary; that
 * work is Day 05. Investing in visual polish now would mean building a screen
 * whose central affordance does not yet exist.
 *
 * What is *not* deferred is honest state. Model loading takes 5–8 seconds and
 * downloading takes minutes, so those states are shown properly rather than
 * hidden behind an indeterminate spinner — a product that appears frozen during
 * normal operation trains users to distrust it.
 *
 * ## Dependencies
 * [ChatViewModel], Compose Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Start the session when the surface appears, not at app start. This is
    // where the 5-8 second model load is paid, and paying it here protects the
    // < 7 second cold-start KPI (ADR-002).
    LaunchedEffect(Unit) { viewModel.startSession() }

    // Follow the stream. Keyed on content length as well as count so the view
    // tracks tokens arriving into the current message, not just new messages.
    val messageCount = uiState.conversation.visibleMessages.size
    val streamingLength = uiState.conversation.streamingMessage?.content?.length ?: 0
    LaunchedEffect(messageCount, streamingLength) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DPS", style = MaterialTheme.typography.titleMedium)
                        uiState.statusLabel?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::newConversation,
                        enabled = !uiState.isGenerating,
                    ) { Text("New") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
        ) {
            PreparationProgress(uiState.aiState)

            uiState.errorLabel?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = uiState.conversation.visibleMessages,
                    key = { message -> message.id },
                ) { message ->
                    MessageBubble(message)
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                canSend = uiState.canSend && draft.isNotBlank(),
                isGenerating = uiState.isGenerating,
                onSend = {
                    viewModel.sendMessage(draft)
                    draft = ""
                },
                onCancel = viewModel::cancelGeneration,
            )
        }
    }
}

/** Determinate progress for the two genuinely slow phases. */
@Composable
private fun PreparationProgress(aiState: AiState) {
    when (aiState) {
        is AiState.PreparingModel -> {
            val progress = when (val install = aiState.installState) {
                is com.softwaremine.dps.domain.model.ModelInstallState.Downloading ->
                    install.progress

                is com.softwaremine.dps.domain.model.ModelInstallState.Verifying ->
                    install.progress

                else -> null
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is AiState.LoadingModel,
        is AiState.CheckingModel,
        -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        else -> Unit
    }
}

/** One turn. User turns right-aligned, assistant left. */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val failed = message.status is MessageStatus.Failed

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                failed -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    // An assistant message that is streaming but has produced
                    // nothing yet still needs a visible anchor, or the interface
                    // looks frozen during time-to-first-token.
                    text = message.content.ifEmpty { if (message.isStreaming) "…" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (failed) {
                    Text(
                        text = "Response incomplete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Input field plus send/stop control. */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message DPS") },
            maxLines = 5,
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        )

        // Send becomes Stop during generation rather than sitting disabled
        // beside a separate control: on-device responses are slow enough that
        // cancelling is a routine action, not an edge case.
        if (isGenerating) {
            Button(onClick = onCancel) { Text("Stop") }
        } else {
            Button(onClick = onSend, enabled = canSend) { Text("Send") }
        }
    }
}
