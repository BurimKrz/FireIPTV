package com.fireiptv.feature.settings.playlists

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.data.playlist.ImportOutcome
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.data.playlist.displayUrl

@Composable
fun PlaylistsScreen(
    onAddPlaylist: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { addFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Playlists", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onAddPlaylist, modifier = Modifier.focusRequester(addFocus)) {
                Text("Add playlist")
            }
        }

        ImportStatus(state.importState)

        if (!state.loading && state.playlists.isEmpty()) {
            Text("No playlists yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val running = state.importState as? ImportState.Running
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    importing = running?.playlistId == playlist.id,
                    onRefresh = { viewModel.refresh(playlist.id) },
                    onDelete = { viewModel.delete(playlist.id) },
                )
            }
        }
    }
}

@Composable
private fun ImportStatus(state: ImportState) {
    when (state) {
        ImportState.Idle -> Unit

        is ImportState.Running -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val channels = state.progress?.channels ?: 0
            Text("Importing ${state.playlistName}: ${"%,d".format(channels)} channels so far")
            val progress = state.progress
            val modifier = Modifier.fillMaxWidth(0.6f)
            if (progress != null && progress.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                    modifier = modifier,
                )
            } else {
                LinearProgressIndicator(modifier = modifier)
            }
        }

        is ImportState.Done -> Text(
            when (val outcome = state.outcome) {
                is ImportOutcome.Imported -> buildString {
                    append("${state.playlistName}: imported ${"%,d".format(outcome.channels)} channels")
                    append(" in ${outcome.groups} groups")
                    if (outcome.skippedVod > 0) append(", left out ${"%,d".format(outcome.skippedVod)} movies and series")
                    if (outcome.skippedLines > 0) append(" (skipped ${outcome.skippedLines} unusable lines)")
                }
                ImportOutcome.NotModified -> "${state.playlistName} is already up to date"
            },
        )

        is ImportState.Failed -> Text(
            "${state.playlistName}: import failed. ${state.message}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    importing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${"%,d".format(playlist.channelCount)} channels · ${playlist.groupCount} groups")
                        val synced = playlist.lastSync
                        append(" · ")
                        append(
                            when {
                                synced == null -> "never synced"
                                System.currentTimeMillis() - synced < DateUtils.MINUTE_IN_MILLIS -> "synced just now"
                                else -> "synced ${DateUtils.getRelativeTimeSpanString(synced)}"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    displayUrl(playlist.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // A stray press on the remote shouldn't wipe a 50k-channel list: ask for a second press.
            var confirmDelete by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                enabled = !importing,
                modifier = Modifier.onFocusChanged { if (!it.isFocused) confirmDelete = false },
            ) {
                Text(if (confirmDelete) "Press again to delete" else "Delete")
            }
            // Rightmost, so D-pad down from "Add playlist" lands here.
            OutlinedButton(onClick = onRefresh, enabled = !importing) {
                Text(if (importing) "Importing…" else "Refresh")
            }
        }
    }
}
