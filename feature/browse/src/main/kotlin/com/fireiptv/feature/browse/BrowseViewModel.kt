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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the groups column: all channels, favourites, recents or one group. */
@Immutable
data class GroupRow(val list: ChannelList, val title: String, val channelCount: Int)

@Immutable
data class ChannelRow(val id: Long, val number: Int, val name: String, val favorite: Boolean)

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
) : ViewModel() {

    // v1 browses one playlist: the first one added.
    private val playlist = playlistRepository.playlists.map { it.firstOrNull() }

    // Counts in the summary change on every import batch; the lists below only restart on a new id.
    private val playlistId = playlist.map { it?.id }.distinctUntilChanged()
    private val selectedList = MutableStateFlow<ChannelList>(ChannelList.All)

    private val groups: Flow<List<GroupRow>> = playlistId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            combine(channelRepository.favoriteCount(id), channelRepository.recentCount(id), channelRepository.groups(id)) { favorites, recents, groups ->
                listOf(
                    GroupRow(ChannelList.Favorites, "Favourites", favorites),
                    GroupRow(ChannelList.Recents, "Recently watched", recents),
                ) + groups.map { GroupRow(ChannelList.Group(it.id), it.title, it.channelCount) }
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
            val all = playlist?.let { GroupRow(ChannelList.All, "All channels", it.channelCount) }
            BrowseUiState(
                loading = false,
                playlist = playlist,
                groups = listOfNotNull(all) + groups,
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

    // Channels without a tvg-chno are numbered by their place in the playlist, which stays the
    // same in every group.
    private fun ChannelListItem.toRow() =
        ChannelRow(id = id, number = number ?: (sortOrder + 1), name = name, favorite = favorite)
}
