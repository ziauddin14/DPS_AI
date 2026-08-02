package com.softwaremine.dps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.softwaremine.dps.DpsApplication
import com.softwaremine.dps.ui.chat.ChatScreen
import com.softwaremine.dps.ui.chat.ChatViewModel
import com.softwaremine.dps.ui.theme.DpsTheme

/**
 * The single Activity.
 *
 * ## Purpose
 * Hosts the Compose hierarchy and resolves the [ChatViewModel] from the
 * application's object graph.
 *
 * ## Why a single Activity
 * Navigation will live entirely in Compose. A single Activity keeps process and
 * window state in one place, which matters more than usual here: the AI's model
 * is process-scoped and expensive, and multiple Activities would create several
 * plausible-looking places to attach its lifecycle. Exactly one is correct, so
 * there should be exactly one candidate.
 *
 * ## Dependencies
 * [DpsApplication] for the graph, [ChatScreen], [DpsTheme].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as DpsApplication).container

        val viewModel = ViewModelProvider(
            owner = this,
            factory = ChatViewModel.Factory(container.sessionManager),
        )[ChatViewModel::class.java]

        setContent {
            DpsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}
