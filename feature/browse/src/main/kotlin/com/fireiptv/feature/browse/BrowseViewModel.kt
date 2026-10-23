package com.fireiptv.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
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
import javax.inject.Inject

/** A row of the groups column; a null [id] is "All channels". */
@Immutable
data class GroupRow(val id: Long?, val title: String, val channelCount: Int)

@Immutable
data class ChannelRow(val id: Long, val number: Int, val name: String)

data class BrowseUiState(
    val loading: Boolean = true,
    val playlist: PlaylistSummary? = null,
    val groups: List<GroupRow> = emptyList(),
    val selectedGroupId: Long? = null,
    val importState: ImportState = ImportState.Idle,
) {
    val selectedGroup: GroupRow? get() = groups.firstOrNull { it.id == selectedGroupId }
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
    private val selectedGroupId = MutableStateFlow<Long?>(null)

    private val groups: Flow<List<GroupRow>> = playlistId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else channelRepository.groups(id)
    }.map { summaries -> summaries.map { GroupRow(it.id, it.title, it.channelCount) } }

    val channels: Flow<PagingData<ChannelRow>> = combine(playlistId, selectedGroupId, ::Pair)
        .flatMapLatest { (playlistId, groupId) ->
            if (playlistId == null) {
                flowOf(PagingData.empty())
            } else {
                channelRepository.channels(playlistId, groupId).map { page -> page.map { it.toRow() } }
            }
        }
        .cachedIn(viewModelScope)

    val uiState: StateFlow<BrowseUiState> =
        combine(playlist, groups, selectedGroupId, playlistRepository.importState) { playlist, groups, selected, import ->
            val all = playlist?.let { GroupRow(id = null, title = "All channels", channelCount = it.channelCount) }
            BrowseUiState(
                loading = false,
                playlist = playlist,
                groups = listOfNotNull(all) + groups,
                selectedGroupId = selected,
                importState = import,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectGroup(groupId: Long?) {
        selectedGroupId.value = groupId
    }

    // Channels without a tvg-chno are numbered by their place in the playlist, which stays the
    // same in every group.
    private fun ChannelListItem.toRow() = ChannelRow(id = id, number = number ?: (sortOrder + 1), name = name)
}
