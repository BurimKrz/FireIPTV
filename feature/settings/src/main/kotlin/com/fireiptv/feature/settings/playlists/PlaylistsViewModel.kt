package com.fireiptv.feature.settings.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    val loading: Boolean = true,
    val playlists: List<PlaylistSummary> = emptyList(),
    val importState: ImportState = ImportState.Idle,
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistsUiState> =
        combine(repository.playlists, repository.importState) { playlists, importState ->
            PlaylistsUiState(loading = false, playlists = playlists, importState = importState)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    fun refresh(playlistId: Long) {
        repository.refresh(playlistId)
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { repository.delete(playlistId) }
    }
}
