package com.fireiptv.feature.settings.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.backup.BackupException
import com.fireiptv.core.data.backup.BackupRepository
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.playlist.xtreamPlaylistUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

data class AddPlaylistUiState(
    val serverError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val urlError: String? = null,
    val saving: Boolean = false,
    /** Set once the playlist is saved and its import has started. */
    val added: Boolean = false,
    val restoring: Boolean = false,
    val restoreError: String? = null,
)

@HiltViewModel
class AddPlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val backups: BackupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPlaylistUiState())
    val uiState: StateFlow<AddPlaylistUiState> = _uiState.asStateFlow()

    /** After a reinstall: the playlist, favourites and the rest come back from the backup file. */
    fun restore() {
        if (_uiState.value.restoring || _uiState.value.saving) return
        _uiState.update { it.copy(restoring = true, restoreError = null) }
        viewModelScope.launch {
            try {
                backups.restore()
                _uiState.update { it.copy(restoring = false, added = true) }
            } catch (e: BackupException) {
                // The playlist may have been added even though the rest failed: then go home anyway.
                val added = repository.hasPlaylists()
                _uiState.update { it.copy(restoring = false, restoreError = e.message, added = added) }
            }
        }
    }

    /** An Xtream login, imported through the Xtream API (live channels only). */
    fun submitXtream(server: String, username: String, password: String) {
        if (_uiState.value.saving) return
        val user = username.trim()
        val pass = password.trim()
        val url = xtreamPlaylistUrl(server, user, pass)
        _uiState.update {
            it.copy(
                serverError = when {
                    server.isBlank() -> "Enter the server, e.g. http://provider.com:8080"
                    url == null && user.isNotEmpty() && pass.isNotEmpty() -> "That isn't a server address"
                    else -> null
                },
                usernameError = if (user.isEmpty()) "Enter the username" else null,
                passwordError = if (pass.isEmpty()) "Enter the password" else null,
            )
        }
        if (url == null) return
        save(name = "", url = url)
    }

    /** Any other playlist, by its M3U link. A get.php link still goes through the Xtream API. */
    fun submitM3u(name: String, url: String) {
        if (_uiState.value.saving) return
        val cleanUrl = url.trim()
        val urlError = if (cleanUrl.toHttpUrlOrNull() == null) "Enter an http:// or https:// playlist URL" else null
        _uiState.update { it.copy(urlError = urlError) }
        if (urlError != null) return
        save(name.trim(), cleanUrl)
    }

    private fun save(name: String, url: String) {
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.addPlaylist(name, url)
            _uiState.update { it.copy(saving = false, added = true) }
        }
    }
}
