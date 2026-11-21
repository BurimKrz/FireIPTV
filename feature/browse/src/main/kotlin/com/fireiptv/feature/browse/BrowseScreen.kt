package com.fireiptv.feature.browse

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.fireiptv.core.data.AppEdition
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.ui.ChannelLogo
import com.fireiptv.core.ui.rememberScrollIdle
import com.fireiptv.feature.browse.movies.MoviesContent
import com.fireiptv.feature.browse.options.ChannelOptionsDialog
import com.fireiptv.feature.browse.options.GroupOptionsDialog
import com.fireiptv.feature.browse.options.PlaylistPickerDialog
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

@Composable
fun BrowseScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onAddPlaylist: () -> Unit,
    /** Settings → Playlists, to rename or change a playlist. */
    onEditPlaylists: () -> Unit,
    /** [list] is the list the channel was picked from, for zapping. */
    onPlayChannel: (channelId: Long, list: ChannelList) -> Unit,
    onOpenMovie: (playlistId: Long, streamId: String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chosenSection by viewModel.section.collectAsStateWithLifecycle()
    // A playlist without movies (an M3U link) always shows Live TV.
    val section = if (state.moviesAvailable) chosenSection else Section.Live
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()

    // Coming back from the player, focus goes to the channel last watched; otherwise to the groups.
    var returnTarget by remember { mutableStateOf<ReturnTarget?>(null) }
    var focusGroups by remember { mutableStateOf(false) }
    // Long-press menus: one for a channel, one for a group or category.
    var channelOptions by remember { mutableStateOf<Long?>(null) }
    var groupOptions by remember { mutableStateOf<GroupRow?>(null) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    // Entering the channels from a group lands on the first channel of that group, once it's loaded.
    var channelsTopFor by remember { mutableStateOf<ChannelList?>(null) }
    // The group row holding focus, or null when focus is on the channels or the buttons.
    var focusedGroupRow by remember { mutableStateOf<ChannelList?>(null) }
    var groupJump by remember { mutableStateOf<GroupJump?>(null) }
    var groupJumps by remember { mutableIntStateOf(0) }
    fun jumpToGroup(list: ChannelList) {
        channelsTopFor = null
        groupJump = GroupJump(list, ++groupJumps)
    }

    // Home is the first screen, so Back here leaves the app. It steps out one level at a time:
    // channels → their group → All channels at the top → "Press Back again", so a stray press
    // while browsing doesn't close it.
    val activity = LocalActivity.current
    val context = LocalContext.current
    var backPressedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val target = when {
            state.groups.isEmpty() -> null
            focusedGroupRow == null -> state.selectedList
            focusedGroupRow != ChannelList.All -> ChannelList.All
            else -> null
        }
        if (target != null) {
            jumpToGroup(target)
            return@BackHandler
        }
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
        Header(
            state,
            section,
            updateAvailable,
            onSection = viewModel::showSection,
            onOpenSettings = onOpenSettings,
            onOpenSearch = onOpenSearch,
            onOpenPlaylists = { pickingPlaylist = true },
        )
        when {
            state.loading -> Unit
            state.playlist == null -> NoPlaylist(onAddPlaylist)
            section == Section.Movies -> MoviesContent(
                onOpenMovie = onOpenMovie,
                // Back from the first category: Live TV, on All channels at the top.
                onBackToLive = {
                    viewModel.showSection(Section.Live)
                    jumpToGroup(ChannelList.All)
                },
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                GroupsColumn(
                    state,
                    focusOnStart = focusGroups,
                    jump = groupJump,
                    onJumpDone = { groupJump = null },
                    onFocusedRowChanged = { focusedGroupRow = it },
                    onSelect = viewModel::select,
                    onEnterChannels = { group ->
                        viewModel.select(group.list)
                        // An empty group has no channel to go to: focus stays on it.
                        channelsTopFor = if (group.channelCount > 0) group.list else null
                    },
                    onGroupFocused = { list -> if (channelsTopFor != list) channelsTopFor = null },
                    onTogglePinned = viewModel::togglePinned,
                    onOptions = { groupOptions = it },
                )
                ChannelsColumn(
                    state,
                    channels,
                    returnTarget,
                    focusTopFor = channelsTopFor,
                    onTopFocused = { channelsTopFor = null },
                    onPlayChannel = { channelId -> onPlayChannel(channelId, state.selectedList) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOptions = { channelOptions = it },
                )
            }
        }
        channelOptions?.let { id ->
            ChannelOptionsDialog(
                channelId = id,
                list = state.selectedList,
                // The hidden row leaves the list: put focus on the channel that takes its place.
                onHidden = {
                    val index = channels.itemSnapshotList.indexOfFirst { it?.id == id }
                    val next = if (index < 0) null else (channels.peek(index + 1)?.let { it to index } ?: channels.peek(index - 1)?.let { it to index - 1 })
                    returnTarget = next?.let { (row, at) -> ReturnTarget(row.id, at) }
                    if (next == null) focusGroups = true
                },
                onDismiss = { channelOptions = null },
            )
        }
        if (pickingPlaylist) {
            PlaylistPickerDialog(
                playlists = state.playlists,
                activeId = state.playlist?.id,
                onPick = viewModel::usePlaylist,
                onAdd = onAddPlaylist,
                onEdit = onEditPlaylists,
                onDismiss = { pickingPlaylist = false },
            )
        }
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
private fun Header(
    state: BrowseUiState,
    section: Section,
    updateAvailable: Boolean,
    onSection: (Section) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlaylists: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The FireIPTV wordmark. The playlist's name isn't shown: the Playlists button tells which one is on.
        Image(
            painter = painterResource(com.fireiptv.core.ui.R.drawable.wordmark),
            contentDescription = "FireIPTV",
            modifier = Modifier.size(width = 150.dp, height = 30.dp),
        )
        // FireIPTV Beta looks the same otherwise: say which app this is.
        if (AppEdition.of(LocalContext.current) == AppEdition.Beta) {
            Text(
                "BETA",
                style = MaterialTheme.typography.labelMedium,
                color = BETA_TEXT,
                modifier = Modifier
                    .background(BETA_BADGE, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        // OK switches (not focus), so moving along the header never starts a download.
        if (state.moviesAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTab("Live TV", selected = section == Section.Live) { onSection(Section.Live) }
                SectionTab("Movies", selected = section == Section.Movies) { onSection(Section.Movies) }
            }
        }
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
            OutlinedButton(onClick = onOpenPlaylists) { Text("Playlists") }
            OutlinedButton(onClick = onOpenSearch) { Text("Search") }
            OutlinedButton(onClick = onOpenSettings) { Text(if (updateAvailable) "Settings · Update available" else "Settings") }
        }
    }
}

/** One side of the Live TV / Movies switch: filled when it's the one showing. */
@Composable
private fun SectionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
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
    /** Each new value scrolls to its group, if it's out of sight, and focuses it; then [onJumpDone]. */
    jump: GroupJump?,
    onJumpDone: () -> Unit,
    onFocusedRowChanged: (ChannelList?) -> Unit,
    onSelect: (ChannelList) -> Unit,
    /** OK or Right on a group: open it and go to its first channel. */
    onEnterChannels: (GroupRow) -> Unit,
    onGroupFocused: (ChannelList) -> Unit,
    onTogglePinned: (GroupRow) -> Unit,
    onOptions: (GroupRow) -> Unit,
) {
    val listState = rememberLazyListState()
    val firstGroup = remember { FocusRequester() }
    LaunchedEffect(focusOnStart) { if (focusOnStart) firstGroup.requestFocus() }
    // The row holding focus, or null when focus is outside the column.
    var focusedRow by remember { mutableStateOf<ChannelList?>(null) }
    LaunchedEffect(focusedRow) { onFocusedRowChanged(focusedRow) }
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableStateOf<ChannelList?>(null) }
    LaunchedEffect(jump) {
        jump ?: return@LaunchedEffect
        // A group that's gone (hidden, refreshed away) falls back to the top.
        val index = state.groups.indexOfFirst { it.list == jump.list }.coerceAtLeast(0)
        val target = state.groups.getOrNull(index)?.list ?: return@LaunchedEffect
        val key = target.encode()
        jumpTarget = target
        if (index == 0) {
            listState.scrollToItem(0)
        } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
            listState.scrollToItem(index)
        }
        // Coming from outside the column, the focus restorer sends the first request to the group
        // focused last; the next one, from inside, reaches the target.
        repeat(RETURN_FOCUS_FRAMES) {
            withFrameNanos {}
            if (focusedRow == target) {
                onJumpDone()
                return@LaunchedEffect
            }
            if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) jumpFocus.requestFocus()
        }
        onJumpDone()
    }

    // Moving through groups shows each one's channels, but only once focus rests, so holding
    // the D-pad down doesn't start a query per group.
    var focusedGroup by remember { mutableStateOf(state.selectedList) }
    LaunchedEffect(focusedGroup) {
        delay(GROUP_SETTLE_MS)
        onSelect(focusedGroup)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .focusRestorer(),
    ) {
        items(state.groups, key = { it.list.encode() }) { group ->
            DenseListItem(
                selected = group.list == state.selectedList,
                onClick = { onEnterChannels(group) },
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
                    // All channels is always the first row.
                    .then(if (group.list == ChannelList.All) Modifier.focusRequester(firstGroup) else Modifier)
                    .then(if (group.list == jumpTarget) Modifier.focusRequester(jumpFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedGroup = group.list
                            focusedRow = group.list
                            onGroupFocused(group.list)
                        } else if (focusedRow == group.list) {
                            focusedRow = null
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when {
                            group.pinnable && event.key == Key.Menu -> {
                                onTogglePinned(group)
                                true
                            }
                            // Right goes to the group's first channel, not the one level with this row.
                            event.key == Key.DirectionRight -> {
                                onEnterChannels(group)
                                true
                            }
                            else -> false
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
    /** When this is the open list, scroll to its top and focus its first channel, then call [onTopFocused]. */
    focusTopFor: ChannelList?,
    onTopFocused: () -> Unit,
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
                state.selectedList == ChannelList.All && state.selectedGroup?.channelCount == 0 ->
                    "Every channel is hidden. Show groups or channels again in Settings."
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
        val topFocus = remember { FocusRequester() }
        val listIdle by rememberScrollIdle(listState)
        LaunchedEffect(focusTopFor, listState) {
            // The open list may still be the previous group for a frame or two after OK/Right.
            if (focusTopFor == null || focusTopFor != state.selectedList) return@LaunchedEffect
            listState.scrollToItem(0)
            repeat(RETURN_FOCUS_FRAMES) {
                withFrameNanos {}
                val first = channels.peek(0)
                // Wait for this group's first page, so focus doesn't land on a row about to be replaced.
                if (channels.loadState.refresh is LoadState.NotLoading && first != null &&
                    listState.layoutInfo.visibleItemsInfo.any { it.key == first.id }
                ) {
                    topFocus.requestFocus()
                    onTopFocused()
                    return@LaunchedEffect
                }
            }
            onTopFocused()
        }
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
        // No focus restorer here: coming from the groups always lands on the first channel (see above).
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
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
                        modifier = Modifier
                            .then(if (channel.id == returnTarget?.channelId) Modifier.focusRequester(returnFocus) else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(topFocus) else Modifier),
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

/** Back moves focus to [list] in the groups column; [seq] makes each press a new request. */
private data class GroupJump(val list: ChannelList, val seq: Int)

// The same cyan and near-black as the BETA badge on FireIPTV Beta's icon (tools/make-icon.py).
private val BETA_BADGE = Color(0xFF26C6DA)
private val BETA_TEXT = Color(0xFF0B0C11)

private const val RETURN_FOCUS_FRAMES = 60
private const val EXIT_CONFIRM_MS = 2_000L
private const val EXPIRY_WARNING_DAYS = 7
private val DAY_MS = TimeUnit.DAYS.toMillis(1).toDouble()
private const val GROUP_SETTLE_MS = 250L
