package com.fireiptv.feature.settings.groups

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
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** Show or hide groups. Hidden groups leave the home screen and "All channels"; search still finds them. */
@Composable
fun GroupsScreen(viewModel: GroupsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstGroup = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(state.groups.isNotEmpty()) {
        if (state.groups.isNotEmpty() && !focused) {
            firstGroup.requestFocus()
            focused = true
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
    ) {
        val hidden = state.groups.count { it.hidden }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Groups", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.groups.size - hidden} of ${state.groups.size} shown",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // A stray press shouldn't hide 400 groups: these ask for a second press, like Delete.
            ConfirmButton("Show all", "Press again to show all") { viewModel.setAllHidden(false) }
            ConfirmButton("Hide all", "Press again to hide all") { viewModel.setAllHidden(true) }
        }
        Text(
            "OK shows or hides a group. Hidden groups leave the home screen and All channels; search still finds them.",
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
            items(state.groups, key = { it.id }) { group ->
                val dim = if (group.hidden) 0.5f else 1f
                DenseListItem(
                    selected = false,
                    onClick = { viewModel.toggle(group) },
                    headlineContent = {
                        Text(
                            group.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Dim the row's own colour, which flips when focused, so the name stays readable.
                            color = LocalContentColor.current.copy(alpha = dim),
                        )
                    },
                    trailingContent = {
                        Text(if (group.hidden) "Hidden" else "${"%,d".format(group.channelCount)} · Shown")
                    },
                    modifier = if (group == state.groups.first()) Modifier.focusRequester(firstGroup) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ConfirmButton(label: String, confirmLabel: String, onConfirmed: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (armed) {
                onConfirmed()
                armed = false
            } else {
                armed = true
            }
        },
        modifier = Modifier.onFocusChanged { if (!it.isFocused) armed = false },
    ) {
        Text(if (armed) confirmLabel else label)
    }
}
