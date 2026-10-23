package com.fireiptv.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Button
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.data.playlist.ImportState
import kotlinx.coroutines.delay

@Composable
fun BrowseScreen(
    onOpenPlaylists: () -> Unit,
    onAddPlaylist: () -> Unit,
    onPlayChannel: (channelId: Long) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()

    // Overscan-safe margins for TV: 5% of 960×540 dp.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state, onOpenPlaylists)
        when {
            state.loading -> Unit
            state.playlist == null -> NoPlaylist(onAddPlaylist)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                GroupsColumn(state, onSelect = viewModel::selectGroup)
                ChannelsColumn(state, channels, onPlayChannel)
            }
        }
    }
}

@Composable
private fun Header(state: BrowseUiState, onOpenPlaylists: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(state.playlist?.name ?: "FireIPTV", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        when (val import = state.importState) {
            is ImportState.Running -> Text(
                "Updating… ${"%,d".format(import.progress?.channels ?: 0)} channels",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ImportState.Failed -> Text(
                "Update failed: ${import.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        if (state.playlist != null) {
            OutlinedButton(onClick = onOpenPlaylists) { Text("Playlists") }
        }
    }
}

@Composable
private fun NoPlaylist(onAddPlaylist: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("No playlist yet. Add one to see your channels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAddPlaylist, modifier = Modifier.focusRequester(focus)) { Text("Add playlist") }
    }
}

@Composable
private fun GroupsColumn(state: BrowseUiState, onSelect: (Long?) -> Unit) {
    val focusManager = LocalFocusManager.current
    val firstGroup = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstGroup.requestFocus() }

    // Moving through groups shows each one's channels, but only once focus rests, so holding
    // the D-pad down doesn't start a query per group.
    var focusedGroup by remember { mutableStateOf(state.selectedGroupId) }
    LaunchedEffect(focusedGroup) {
        delay(GROUP_SETTLE_MS)
        onSelect(focusedGroup)
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .focusRestorer(),
    ) {
        items(state.groups, key = { it.id ?: ALL_KEY }) { group ->
            DenseListItem(
                selected = group.id == state.selectedGroupId,
                onClick = {
                    onSelect(group.id)
                    focusManager.moveFocus(FocusDirection.Right)
                },
                headlineContent = { Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text("%,d".format(group.channelCount)) },
                modifier = Modifier
                    .then(if (group.id == null) Modifier.focusRequester(firstGroup) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedGroup = group.id },
            )
        }
    }
}

@Composable
private fun ChannelsColumn(
    state: BrowseUiState,
    channels: LazyPagingItems<ChannelRow>,
    onPlayChannel: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        val group = state.selectedGroup
        Text(
            group?.let { "${it.title} · ${"%,d".format(it.channelCount)}" }.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (channels.itemCount == 0) {
            val import = state.importState
            val message = when {
                import is ImportState.Running -> "Importing channels…"
                import is ImportState.Failed && state.playlist?.channelCount == 0 -> "No channels: ${import.message}"
                channels.loadState.refresh is LoadState.Loading -> return@Column
                else -> "No channels in this group."
            }
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        // A fresh list state per group, so a new group starts at its top.
        val listState = remember(state.selectedGroupId) { LazyListState() }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
        ) {
            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                val channel = channels[index]
                if (channel == null) {
                    // Placeholder while its page loads: same height, not focusable.
                    DenseListItem(selected = false, onClick = {}, enabled = false, headlineContent = { Text("") })
                } else {
                    ChannelItem(channel, onClick = { onPlayChannel(channel.id) })
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(channel: ChannelRow, onClick: () -> Unit) {
    DenseListItem(
        selected = false,
        onClick = onClick,
        headlineContent = {
            Row {
                Text(
                    channel.number.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
                Box(Modifier.weight(1f)) {
                    Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val GROUP_SETTLE_MS = 250L
private const val ALL_KEY = -1L
