package com.fireiptv.feature.browse.movies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.fireiptv.core.data.db.MovieEntity
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.vod.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the categories column. [movieCount] is known once the category has been opened. */
@Immutable
data class MovieCategoryRow(val id: Long, val title: String, val movieCount: Int?, val loaded: Boolean)

@Immutable
data class MovieRow(val id: Long, val name: String, val year: Int?, val posterUrl: String?, val rating: Float?)

/** A download the user is waiting for. No entry means done (or not started). */
sealed interface Load {
    data object Running : Load
    data class Failed(val message: String) : Load
}

/** The movie whose page was opened, to put focus back on it when coming back: [index] in its category. */
data class MovieReturn(val movieId: Long, val index: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    playlists: PlaylistRepository,
    private val repository: MovieRepository,
) : ViewModel() {

    private val playlistId = playlists.activePlaylist.map { it?.id }.distinctUntilChanged()

    val categories: StateFlow<List<MovieCategoryRow>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.categories(id) }
        .map { rows -> rows.map { MovieCategoryRow(it.id, it.title, it.movieCount, it.loadedAt != null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _categoriesLoad = MutableStateFlow<Load?>(null)
    val categoriesLoad: StateFlow<Load?> = _categoriesLoad.asStateFlow()

    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private val _moviesLoad = MutableStateFlow<Map<Long, Load>>(emptyMap())
    val moviesLoad: StateFlow<Map<Long, Load>> = _moviesLoad.asStateFlow()

    val movies: Flow<PagingData<MovieRow>> = _selected
        .flatMapLatest { id -> if (id == null) flowOf(PagingData.empty()) else repository.movies(id) }
        .map { page -> page.map { it.toRow() } }
        .cachedIn(viewModelScope)

    /** True while Movies is on screen: only then does the category list download. */
    private val visible = MutableStateFlow(false)

    private var returnTo: MovieReturn? = null

    init {
        // Opening Movies, switching playlist while on it, or a refresh that dropped the lists:
        // fetch the category list (a few kB) if it isn't here.
        viewModelScope.launch {
            combine(playlistId, visible, categories.map { it.isEmpty() }.distinctUntilChanged(), ::Triple)
                .collectLatest { (id, shown, empty) -> if (id != null && shown && empty) loadCategories(id) }
        }
        viewModelScope.launch {
            playlistId.collect { _selected.value = null }
        }
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    /** Tries the category list again after a failure. */
    fun retryCategories() {
        viewModelScope.launch {
            val id = playlistId.first() ?: return@launch
            loadCategories(id)
        }
    }

    private suspend fun loadCategories(playlistId: Long) {
        if (_categoriesLoad.value == Load.Running) return
        _categoriesLoad.value = Load.Running
        _categoriesLoad.value = try {
            repository.loadCategories(playlistId)
            null
        } catch (e: CancellationException) {
            _categoriesLoad.value = null
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Shows a category's movies, downloading them the first time. */
    fun select(categoryId: Long) {
        _selected.value = categoryId
        val loaded = categories.value.firstOrNull { it.id == categoryId }?.loaded == true
        if (loaded || _moviesLoad.value[categoryId] == Load.Running) return
        _moviesLoad.update { it + (categoryId to Load.Running) }
        viewModelScope.launch {
            val result = try {
                repository.loadMovies(categoryId)
                null
            } catch (e: CancellationException) {
                _moviesLoad.update { it - categoryId }
                throw e
            } catch (e: Exception) {
                Load.Failed(e.message ?: e.javaClass.simpleName)
            }
            _moviesLoad.update { if (result == null) it - categoryId else it + (categoryId to result) }
        }
    }

    fun rememberOpened(movieId: Long, index: Int) {
        returnTo = MovieReturn(movieId, index)
    }

    /** The movie whose page was opened last, once. */
    fun takeReturn(): MovieReturn? = returnTo.also { returnTo = null }

    private fun MovieEntity.toRow() = MovieRow(id, name, year, posterUrl(posterUrl, PosterSize.Thumbnail), rating)
}
