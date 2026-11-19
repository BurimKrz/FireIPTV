package com.fireiptv.feature.settings.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.playlist.XtreamLogin
import com.fireiptv.core.data.playlist.parseXtreamLogin
import com.fireiptv.core.data.playlist.xtreamPlaylistUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/** What the form starts with: the playlist as saved. */
data class EditPlaylistForm(
    val name: String,
    /** The saved login, when the link is an Xtream one; the form then shows its fields. */
    val login: XtreamLogin?,
    val url: String,
)

data class EditPlaylistUiState(
    val form: EditPlaylistForm? = null,
    /** The playlist was deleted meanwhile. */
    val missing: Boolean = false,
    val serverError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val urlError: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class EditPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle[ARG_PLAYLIST_ID])
    private var savedUrl: String? = null

    private val _uiState = MutableStateFlow(EditPlaylistUiState())
    val uiState: StateFlow<EditPlaylistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val playlist = repository.playlist(playlistId)
            if (playlist == null) {
                _uiState.update { it.copy(missing = true) }
            } else {
                savedUrl = playlist.url
                _uiState.update { it.copy(form = EditPlaylistForm(playlist.name, parseXtreamLogin(playlist.url), playlist.url)) }
            }
        }
    }

    fun saveXtream(name: String, server: String, username: String, password: String) {
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
                urlError = null,
            )
        }
        if (url == null) return
        // The same login typed again keeps the saved link (it may be a get.php link), so nothing reloads.
        val saved = savedUrl
        val same = saved != null && parseXtreamLogin(saved) == parseXtreamLogin(url)
        save(name, if (same) saved!! else url)
    }

    fun saveM3u(name: String, url: String) {
        if (_uiState.value.saving) return
        val cleanUrl = url.trim()
        val urlError = if (cleanUrl.toHttpUrlOrNull() == null) "Enter an http:// or https:// playlist URL" else null
        _uiState.update { it.copy(urlError = urlError, serverError = null, usernameError = null, passwordError = null) }
        if (urlError != null) return
        save(name, cleanUrl)
    }

    private fun save(name: String, url: String) {
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.edit(playlistId, name.trim(), url)
            _uiState.update { it.copy(saving = false, saved = true) }
        }
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
    }
}
