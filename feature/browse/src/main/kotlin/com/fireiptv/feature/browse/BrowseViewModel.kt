package com.fireiptv.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.ChannelListItem
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.update.UpdateRepository
import com.fireiptv.core.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the groups column: all channels, favourites, recents, one group or one of the user's categories. */
@Immutable
data class GroupRow(
    val list: ChannelList,
    val title: String,
    val channelCount: Int,
    /** Epoch millis when pinned to the top, or null. */
    val pinnedAt: Long? = null,
) {
    /** Groups and the user's categories can be pinned; All, Favourites and Recents always come first. */
    val pinnable: Boolean get() = list is ChannelList.Group || list is ChannelList.Custom
    val pinned: Boolean get() = pinnedAt != null
}

@Immutable
data class ChannelRow(val id: Long, val number: Int, val name: String, val logoUrl: String?, val favorite: Boolean)

/** Where focus goes when coming back from the player: [index] in the current channel list. */
data class ReturnTarget(val channelId: Long, val index: Int)

data class BrowseUiState(
    val loading: Boolean = true,
    val playlist: PlaylistSummary? = null,
    val groups: List<GroupRow> = emptyList(),
    val selectedList: ChannelList = ChannelList.All,
    val importState: ImportState = ImportState.Idle,
) {
    val selectedGroup: GroupRow? get() = groups.firstOrNull { it.list == selectedList }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    updateRepository: UpdateRepository,
) : ViewModel() {

    /** A newer version is on GitHub (from today's check), so Settings gets a hint. */
    val updateAvailable: StateFlow<Boolean> = updateRepository.state
        .map { it is UpdateState.Available || it is UpdateState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // v1 browses one playlist: the first one added.
    private val playlist = playlistRepository.playlists.map { it.firstOrNull() }

    // Counts in the summary change on every import batch; the lists below only restart on a new id.
    private val playlistId = playlist.map { it?.id }.distinctUntilChanged()
    private val selectedList = MutableStateFlow<ChannelList>(ChannelList.All)

    private val groups: Flow<List<GroupRow>> = playlistId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            combine(
                channelRepository.visibleCount(id),
                channelRepository.favoriteCount(id),
                channelRepository.recentCount(id),
                channelRepository.groups(id),
                channelRepository.userLists(id),
            ) { all, favorites, recents, groups, lists ->
                val mine = lists.map { GroupRow(ChannelList.Custom(it.id), it.title, it.channelCount, it.pinnedAt) }
                val provider = groups.map { GroupRow(ChannelList.Group(it.id), it.title, it.channelCount, it.pinnedAt) }
                // Pinned ones first, in the order they were pinned; then the user's own, then the provider's.
                listOf(
                    GroupRow(ChannelList.All, "All channels", all),
                    GroupRow(ChannelList.Favorites, "Favourites", favorites),
                    GroupRow(ChannelList.Recents, "Recently watched", recents),
                ) + (mine + provider).filter { it.pinned }.sortedBy { it.pinnedAt } +
                    mine.filterNot { it.pinned } + provider.filterNot { it.pinned }
            }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    init {
        // A group hidden in settings (or gone after a refresh) can't stay open: fall back to all channels.
        viewModelScope.launch {
            groups.collect { rows ->
                val selected = selectedList.value
                if (selected is ChannelList.Group && rows.none { it.list == selected }) selectedList.value = ChannelList.All
            }
        }
    }

    val channels: Flow<PagingData<ChannelRow>> = combine(playlistId, selectedList, ::Pair)
        .flatMapLatest { (playlistId, list) ->
            if (playlistId == null) {
                flowOf(PagingData.empty())
            } else {
                channelRepository.channels(playlistId, list).map { page -> page.map { it.toRow() } }
            }
        }
        .cachedIn(viewModelScope)

    val uiState: StateFlow<BrowseUiState> =
        combine(playlist, groups, selectedList, playlistRepository.importState) { playlist, groups, selected, import ->
            BrowseUiState(
                loading = false,
                playlist = playlist,
                groups = groups,
                selectedList = selected,
                importState = import,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    /** The channel last played, if the player opened one since the last call. */
    suspend fun takeReturnTarget(): ReturnTarget? {
        val id = channelRepository.takeLastPlayed() ?: return null
        val channel = channelRepository.channel(id) ?: return null
        val index = channelRepository.position(channel, selectedList.value) ?: return null
        return ReturnTarget(id, index)
    }

    fun select(list: ChannelList) {
        selectedList.value = list
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch { channelRepository.toggleFavorite(channelId) }
    }

    /** Menu (☰) on a group or category pins it to the top of the column, or unpins it. */
    fun togglePinned(row: GroupRow) {
        if (!row.pinnable) return
        viewModelScope.launch { channelRepository.setPinned(row.list, !row.pinned) }
    }

    fun renameList(listId: Long, title: String) {
        viewModelScope.launch { channelRepository.renameList(listId, title.trim()) }
    }

    fun deleteList(listId: Long) {
        if (selectedList.value == ChannelList.Custom(listId)) selectedList.value = ChannelList.All
        viewModelScope.launch { channelRepository.deleteList(listId) }
    }

    // Channels without a tvg-chno are numbered by their place in the playlist, which stays the
    // same in every group.
    private fun ChannelListItem.toRow() =
        ChannelRow(id = id, number = number ?: (sortOrder + 1), name = name, logoUrl = logoUrl, favorite = favorite)
}
