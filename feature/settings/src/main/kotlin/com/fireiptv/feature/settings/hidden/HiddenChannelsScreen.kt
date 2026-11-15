package com.fireiptv.feature.settings.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** Channels the user hid one by one. OK shows one again; search finds them all the same. */
@Composable
fun HiddenChannelsScreen(viewModel: HiddenChannelsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val showAll = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (!state.loading && !focused) {
            if (state.channels.isNotEmpty()) first.requestFocus()
            focused = true
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Hidden channels", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.channels.size} hidden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.channels.isNotEmpty()) {
                // A stray press shouldn't undo a long list of choices: ask for a second press.
                var armed by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        if (armed) viewModel.showAll()
                        armed = !armed
                    },
                    modifier = Modifier
                        .focusRequester(showAll)
                        .onFocusChanged { if (!it.isFocused) armed = false },
                ) {
                    Text(if (armed) "Press again to show all" else "Show all")
                }
            }
        }
        Text(
            if (state.channels.isEmpty()) {
                "No hidden channels. Long-press OK on a channel and choose Hide channel to take it out of every list."
            } else {
                "OK shows a channel in its lists again. Hidden channels are skipped when zapping; search still finds them."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .widthIn(max = 900.dp)
                .fillMaxSize()
                .focusRestorer(),
        ) {
            items(state.channels, key = { it.streamUrl }) { channel ->
                DenseListItem(
                    selected = false,
                    onClick = { viewModel.show(channel) },
                    headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            channel.groupTitle ?: "Not in the playlist now",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = { Text("Hidden") },
                    modifier = if (channel == state.channels.first()) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}
