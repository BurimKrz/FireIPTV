package com.fireiptv.feature.settings.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.backup.BackupException
import com.fireiptv.core.data.backup.BackupRepository
import com.fireiptv.core.data.backup.BackupSummary
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.di.ApplicationScope
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.StartMode
import com.fireiptv.core.data.update.UpdateRepository
import com.fireiptv.core.data.update.UpdateState
import com.fireiptv.core.data.usage.DataUsage
import com.fireiptv.core.data.usage.DataUsageRepository
import com.fireiptv.core.log.AppLog
import com.fireiptv.core.log.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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
    val hiddenChannelCount: Int = 0,
)

/** Back up or Restore: running, or how the last one went. */
data class BackupStatus(
    val running: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    /** Which row the status belongs to: Restore, or Back up. */
    val restore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    playlistRepository: PlaylistRepository,
    channelRepository: ChannelRepository,
    accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val dataUsage: DataUsageRepository,
    private val backups: BackupRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    appLog: AppLog,
) : ViewModel() {

    private val _backup = MutableStateFlow(BackupStatus())
    val backup: StateFlow<BackupStatus> = _backup.asStateFlow()
    val backupLocation: String = backups.location

    val usage: StateFlow<DataUsage> = dataUsage.usage

    val log: StateFlow<List<LogEntry>> = appLog.entries

    val update: StateFlow<UpdateState> = updates.state
    val installedVersion: String = updates.installedVersion

    private val playlistId = playlistRepository.playlists.map { it.firstOrNull()?.id }.distinctUntilChanged()
    private val groups = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.groupSettings(id) }
    private val hiddenChannels = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channelRepository.hiddenChannels(id) }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            combine(playlistRepository.playlists, groups, hiddenChannels, ::Triple),
            settings.startMode,
            settings.logosEnabled,
            accountRepository.available,
        ) { (playlists, groups, hidden), startMode, logos, hasAccount ->
            SettingsUiState(
                hiddenChannelCount = hidden.size,
                playlistCount = playlists.size,
                channelCount = playlists.sumOf { it.channelCount },
                groupCount = groups.size,
                hiddenGroupCount = groups.count { it.hidden },
                startMode = startMode,
                logosEnabled = logos,
                hasAccount = hasAccount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** OK on the data row counts what arrived since the last sample, so the numbers are current. */
    fun refreshUsage() {
        viewModelScope.launch { dataUsage.sample() }
    }

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

    /** Saves favourites, categories, hidden things and settings to Pictures/FireIPTV-backup.png. */
    fun backUp() = runBackup(restore = false, backups::save) { "Saved to $backupLocation: ${describe(it)}." }

    /** Loads the backup, adding to what is here; with no playlist yet, adds the backup's first. */
    fun restore() = runBackup(restore = true, backups::restore) {
        (if (it.playlistAdded) "Playlist added. " else "") + "Restored ${describe(it)}."
    }

    private fun runBackup(restore: Boolean, action: suspend () -> BackupSummary, done: (BackupSummary) -> String) {
        if (_backup.value.running) return
        _backup.value = BackupStatus(running = true, restore = restore)
        // A restore can wait for a whole playlist import: leaving Settings mustn't stop it half-way.
        appScope.launch {
            _backup.value = try {
                BackupStatus(message = done(action()), restore = restore)
            } catch (e: BackupException) {
                BackupStatus(message = e.message, failed = true, restore = restore)
            }
        }
    }

    private fun describe(summary: BackupSummary): String = listOf(
        count(summary.favorites, "favourite"),
        count(summary.categories, "category", "categories"),
        count(summary.hiddenGroups, "hidden group"),
        count(summary.hiddenChannels, "hidden channel"),
    ).joinToString(", ")

    private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    fun toggleStartMode() {
        val next = if (settings.startMode.value == StartMode.FAST) StartMode.STABLE else StartMode.FAST
        viewModelScope.launch { settings.setStartMode(next) }
    }
}
