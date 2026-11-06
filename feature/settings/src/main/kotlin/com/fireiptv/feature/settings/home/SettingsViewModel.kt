package com.fireiptv.feature.settings.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.StartMode
import com.fireiptv.core.data.update.UpdateRepository
import com.fireiptv.core.data.update.UpdateState
import com.fireiptv.core.log.AppLog
import com.fireiptv.core.log.LogEntry
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
    /** The playlist is an Xtream login, so there are account details to show. */
    val hasAccount: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    channelRepository: ChannelRepository,
    accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    appLog: AppLog,
) : ViewModel() {

    val log: StateFlow<List<LogEntry>> = appLog.entries

    val update: StateFlow<UpdateState> = updates.state
    val installedVersion: String = updates.installedVersion

    private val groups = playlistRepository.playlists
        .map { it.firstOrNull()?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.groupSettings(id) }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            playlistRepository.playlists,
            groups,
            settings.startMode,
            settings.logosEnabled,
            accountRepository.available,
        ) { playlists, groups, startMode, logos, hasAccount ->
            SettingsUiState(
                playlistCount = playlists.size,
                channelCount = playlists.sumOf { it.channelCount },
                groupCount = groups.size,
                hiddenGroupCount = groups.count { it.hidden },
                startMode = startMode,
                logosEnabled = logos,
                hasAccount = hasAccount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun toggleLogos() {
        viewModelScope.launch { settings.setLogosEnabled(!settings.logosEnabled.value) }
    }

    /** OK on the Updates row: checks, downloads, or returns the installer to start. */
    fun onUpdate(): Intent? {
        when (val state = updates.state.value) {
            UpdateState.Unknown, is UpdateState.UpToDate -> updates.check()
            is UpdateState.Available -> updates.download()
            is UpdateState.Failed -> if (state.release != null) updates.download() else updates.check()
            is UpdateState.Ready -> return updates.installIntent(state.apk)
            UpdateState.Checking, is UpdateState.Downloading -> Unit
        }
        return null
    }

    fun toggleStartMode() {
        val next = if (settings.startMode.value == StartMode.FAST) StartMode.STABLE else StartMode.FAST
        viewModelScope.launch { settings.setStartMode(next) }
    }
}
