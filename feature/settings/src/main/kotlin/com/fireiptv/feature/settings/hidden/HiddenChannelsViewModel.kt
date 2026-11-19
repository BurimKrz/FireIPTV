package com.fireiptv.feature.settings.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.HiddenChannelItem
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

data class HiddenChannelsUiState(val loading: Boolean = true, val channels: List<HiddenChannelItem> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HiddenChannelsViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
) : ViewModel() {

    private val playlistId = playlistRepository.activePlaylist.map { it?.id }.distinctUntilChanged()

    val uiState: StateFlow<HiddenChannelsUiState> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.hiddenChannels(id) }
        .map { HiddenChannelsUiState(loading = false, channels = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiddenChannelsUiState())

    fun show(channel: HiddenChannelItem) {
        viewModelScope.launch {
            val id = playlistId.first() ?: return@launch
            channelRepository.showChannel(id, channel.streamUrl)
        }
    }

    fun showAll() {
        viewModelScope.launch {
            val id = playlistId.first() ?: return@launch
            channelRepository.showAllChannels(id)
        }
    }
}
