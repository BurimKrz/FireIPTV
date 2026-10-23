package com.fireiptv.feature.settings.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

data class AddPlaylistUiState(
    val urlError: String? = null,
    val saving: Boolean = false,
    /** Set once the playlist is saved and its import has started. */
    val added: Boolean = false,
)

@HiltViewModel
class AddPlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPlaylistUiState())
    val uiState: StateFlow<AddPlaylistUiState> = _uiState.asStateFlow()

    fun submit(name: String, url: String) {
        if (_uiState.value.saving) return
        val cleanUrl = url.trim()

        val urlError = if (cleanUrl.toHttpUrlOrNull() == null) "Enter an http:// or https:// playlist URL" else null
        _uiState.update { it.copy(urlError = urlError) }
        if (urlError != null) return

        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.addPlaylist(name.trim(), cleanUrl)
            _uiState.update { it.copy(saving = false, added = true) }
        }
    }
}
