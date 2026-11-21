package com.fireiptv.feature.browse.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.playlist.xtream.MovieInfo
import com.fireiptv.core.data.vod.Movie
import com.fireiptv.core.data.vod.MovieRepository
import com.fireiptv.core.data.vod.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieInfoUiState(
    /** From the list, so the page shows at once; null only if the movie is gone (after a refresh). */
    val movie: Movie? = null,
    val loadingMovie: Boolean = true,
    /** The details, downloaded when the page opens. */
    val info: MovieInfo? = null,
    val infoError: String? = null,
    /** False until the saved position is read, so Play doesn't flash before Resume. */
    val progressRead: Boolean = false,
    /** Where the movie was stopped part-way, to offer Resume; null to start from the beginning. */
    val resumeFromMs: Long? = null,
)

@HiltViewModel
class MovieInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MovieRepository,
    private val progress: WatchProgressRepository,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle[ARG_PLAYLIST_ID])
    private val streamId: String = checkNotNull(savedStateHandle[ARG_STREAM_ID])
    private val _state = MutableStateFlow(MovieInfoUiState())
    val state: StateFlow<MovieInfoUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val movie = repository.movie(playlistId, streamId)
            _state.update { it.copy(movie = movie, loadingMovie = false) }
            if (movie == null) return@launch
            // Coming back from the player updates the Resume button.
            launch {
                progress.progress(movie).collect { saved ->
                    val resumeFrom = saved?.takeIf { !it.finished && it.positionMs >= WatchProgressRepository.RESUMABLE_FROM_MS }?.positionMs
                    _state.update { it.copy(progressRead = true, resumeFromMs = resumeFrom) }
                }
            }
            loadInfo(movie)
        }
    }

    private suspend fun loadInfo(movie: Movie) {
        val result = try {
            Result.success(repository.info(movie))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        _state.update {
            it.copy(info = result.getOrNull(), infoError = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName })
        }
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_STREAM_ID = "streamId"
    }
}
