package com.fireiptv.feature.settings.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.GroupSetting
import com.fireiptv.core.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupsUiState(val loading: Boolean = true, val groups: List<GroupSetting> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupsViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
) : ViewModel() {

    private val playlistId = playlistRepository.playlists.map { it.firstOrNull()?.id }.distinctUntilChanged()

    val uiState: StateFlow<GroupsUiState> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.groupSettings(id) }
        .map { GroupsUiState(loading = false, groups = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    fun toggle(group: GroupSetting) {
        viewModelScope.launch { channelRepository.setGroupHidden(group.id, !group.hidden) }
    }

    fun setAllHidden(hidden: Boolean) {
        viewModelScope.launch {
            val id = playlistId.first() ?: return@launch
            channelRepository.setAllGroupsHidden(id, hidden)
        }
    }
}
