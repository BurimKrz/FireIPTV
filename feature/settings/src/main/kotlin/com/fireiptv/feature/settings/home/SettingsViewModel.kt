package com.fireiptv.feature.settings.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.StartMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class SettingsUiState(
    val playlistCount: Int = 0,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val hiddenGroupCount: Int = 0,
    val startMode: StartMode = StartMode.FAST,
    val logosEnabled: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    channelRepository: ChannelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val groups = playlistRepository.playlists
        .map { it.firstOrNull()?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.groupSettings(id) }

    val uiState: StateFlow<SettingsUiState> =
        combine(playlistRepository.playlists, groups, settings.startMode, settings.logosEnabled) { playlists, groups, startMode, logos ->
            SettingsUiState(
                playlistCount = playlists.size,
                channelCount = playlists.sumOf { it.channelCount },
                groupCount = groups.size,
                hiddenGroupCount = groups.count { it.hidden },
                startMode = startMode,
                logosEnabled = logos,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun toggleLogos() {
        viewModelScope.launch { settings.setLogosEnabled(!settings.logosEnabled.value) }
    }

    fun toggleStartMode() {
        val next = if (settings.startMode.value == StartMode.FAST) StartMode.STABLE else StartMode.FAST
        viewModelScope.launch { settings.setStartMode(next) }
    }
}
