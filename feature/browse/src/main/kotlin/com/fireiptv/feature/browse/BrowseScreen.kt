package com.fireiptv.feature.browse

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.ui.ChannelLogo
import com.fireiptv.core.ui.rememberScrollIdle
import com.fireiptv.feature.browse.options.ChannelOptionsDialog
import com.fireiptv.feature.browse.options.GroupOptionsDialog
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

@Composable
fun BrowseScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onAddPlaylist: () -> Unit,
    /** [list] is the list the channel was picked from, for zapping. */
    onPlayChannel: (channelId: Long, list: ChannelList) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()

    // Coming back from the player, focus goes to the channel last watched; otherwise to the groups.
    var returnTarget by remember { mutableStateOf<ReturnTarget?>(null) }
    var focusGroups by remember { mutableStateOf(false) }
    // Long-press menus: one for a channel, one for a group or category.
    var channelOptions by remember { mutableStateOf<Long?>(null) }
    var groupOptions by remember { mutableStateOf<GroupRow?>(null) }

    // Home is the first screen, so Back here leaves the app: ask for a second press, so a stray
    // press while browsing doesn't close it.
    val activity = LocalActivity.current
    val context = LocalContext.current
    var backPressedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = SystemClock.elapsedRealtime()
        if (now - backPressedAt < EXIT_CONFIRM_MS) {
            activity?.finish()
        } else {
            backPressedAt = now
            Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        returnTarget = viewModel.takeReturnTarget()
        focusGroups = returnTarget == null
    }

    // Overscan-safe margins for TV: 5% of 960×540 dp.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state, updateAvailable, onOpenSettings, onOpenSearch)
        when {
            state.loading -> Unit
            state.playlist == null -> NoPlaylist(onAddPlaylist)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                GroupsColumn(
                    state,
                    focusOnStart = focusGroups,
                    onSelect = viewModel::select,
                    onTogglePinned = viewModel::togglePinned,
                    onOptions = { groupOptions = it },
                )
                ChannelsColumn(
                    state,
                    channels,
                    returnTarget,
                    onPlayChannel = { channelId -> onPlayChannel(channelId, state.selectedList) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOptions = { channelOptions = it },
                )
            }
        }
        channelOptions?.let { id -> ChannelOptionsDialog(channelId = id, onDismiss = { channelOptions = null }) }
        groupOptions?.let { row ->
            val listId = (row.list as? ChannelList.Custom)?.id
            GroupOptionsDialog(
                row = row,
                onTogglePinned = { viewModel.togglePinned(row) },
                onRename = { title -> listId?.let { viewModel.renameList(it, title) } },
                onDelete = { listId?.let(viewModel::deleteList) },
                onDismiss = { groupOptions = null },
            )
        }
    }
}

@Composable
private fun Header(state: BrowseUiState, updateAvailable: Boolean, onOpenSettings: () -> Unit, onOpenSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(state.playlist?.name ?: "FireIPTV", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        // Refresh only runs on request, so say how old the list is.
        state.playlist?.lastSync?.let { synced ->
            val days = ((System.currentTimeMillis() - synced) / DAY_MS).toLong().coerceAtLeast(0)
            Text(
                when (days) {
                    0L -> "Refreshed today"
                    1L -> "Refreshed yesterday"
                    else -> "Refreshed $days days ago"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // From the expiry saved at the last refresh: no request just to show this.
        state.playlist?.accountExpiresAt?.let { expiresAt ->
            val days = ceil((TimeUnit.SECONDS.toMillis(expiresAt) - System.currentTimeMillis()) / DAY_MS).toLong()
            val warning = when {
                days < 0 -> "Account expired"
                days <= EXPIRY_WARNING_DAYS -> if (days == 1L) "Account expires in 1 day" else "Account expires in $days days"
                else -> null
            }
            warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
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
            OutlinedButton(onClick = onOpenSearch) { Text("Search") }
            OutlinedButton(onClick = onOpenSettings) { Text(if (updateAvailable) "Settings · Update available" else "Settings") }
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
private fun GroupsColumn(
    state: BrowseUiState,
    focusOnStart: Boolean,
    onSelect: (ChannelList) -> Unit,
    onTogglePinned: (GroupRow) -> Unit,
    onOptions: (GroupRow) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val firstGroup = remember { FocusRequester() }
    LaunchedEffect(focusOnStart) { if (focusOnStart) firstGroup.requestFocus() }

    // Moving through groups shows each one's channels, but only once focus rests, so holding
    // the D-pad down doesn't start a query per group.
    var focusedGroup by remember { mutableStateOf(state.selectedList) }
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
        items(state.groups, key = { it.list.encode() }) { group ->
            DenseListItem(
                selected = group.list == state.selectedList,
                onClick = {
                    onSelect(group.list)
                    focusManager.moveFocus(FocusDirection.Right)
                },
                // Long-press OK: pin, and rename or delete the user's own; Menu (☰) pins at once.
                onLongClick = if (group.pinnable) {
                    { onOptions(group) }
                } else {
                    null
                },
                headlineContent = {
                    Text(if (group.pinned) "★ ${group.title}" else group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { Text("%,d".format(group.channelCount)) },
                modifier = Modifier
                    .then(if (group.list == ChannelList.All) Modifier.focusRequester(firstGroup) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedGroup = group.list }
                    .onKeyEvent { event ->
                        if (group.pinnable && event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                            onTogglePinned(group)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

@Composable
private fun ChannelsColumn(
    state: BrowseUiState,
    channels: LazyPagingItems<ChannelRow>,
    returnTarget: ReturnTarget?,
    onPlayChannel: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOptions: (Long) -> Unit,
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
                state.selectedList == ChannelList.Favorites ->
                    "No favourites yet. Long-press OK on a channel, or press Menu (☰), to add it."
                state.selectedList == ChannelList.Recents -> "Channels you watch will appear here."
                state.selectedList is ChannelList.Custom ->
                    "No channels yet. Long-press OK on any channel and pick this category to add it."
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
        val listState = remember(state.selectedList) { LazyListState() }
        val returnFocus = remember { FocusRequester() }
        val listIdle by rememberScrollIdle(listState)
        LaunchedEffect(returnTarget) {
            val target = returnTarget ?: return@LaunchedEffect
            listState.scrollToItem(target.index)
            // Its page may still be loading: wait until the row is laid out, then focus it.
            repeat(RETURN_FOCUS_FRAMES) {
                if (listState.layoutInfo.visibleItemsInfo.any { it.key == target.channelId }) {
                    withFrameNanos {}
                    returnFocus.requestFocus()
                    return@LaunchedEffect
                }
                withFrameNanos {}
            }
        }
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
                    ChannelItem(
                        channel,
                        onClick = { onPlayChannel(channel.id) },
                        onToggleFavorite = { onToggleFavorite(channel.id) },
                        onOptions = { onOptions(channel.id) },
                        logosMayDownload = listIdle,
                        modifier = if (channel.id == returnTarget?.channelId) Modifier.focusRequester(returnFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(
    channel: ChannelRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOptions: () -> Unit,
    logosMayDownload: Boolean,
    modifier: Modifier = Modifier,
) {
    DenseListItem(
        selected = false,
        onClick = onClick,
        // Long-press OK opens favourite and category choices; Menu (☰) toggles the favourite at once.
        onLongClick = onOptions,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.number.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
                ChannelLogo(
                    channel.logoUrl,
                    Modifier.padding(end = 16.dp).size(width = 48.dp, height = 27.dp),
                    mayDownload = logosMayDownload,
                )
                Box(Modifier.weight(1f)) {
                    Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = if (channel.favorite) {
            { Text("★") }
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    onToggleFavorite()
                    true
                } else {
                    false
                }
            },
    )
}

private const val RETURN_FOCUS_FRAMES = 60
private const val EXIT_CONFIRM_MS = 2_000L
private const val EXPIRY_WARNING_DAYS = 7
private val DAY_MS = TimeUnit.DAYS.toMillis(1).toDouble()
private const val GROUP_SETTLE_MS = 250L
