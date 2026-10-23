package com.fireiptv.feature.browse.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SearchResult(
    val id: Long,
    val number: Int,
    val name: String,
    val groupTitle: String?,
    val favorite: Boolean,
    /** The list Up/Down zaps through when this result is played: its group. */
    val zapList: ChannelList,
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    /** True once results for the current query have arrived. */
    val searched: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
) : ViewModel() {

    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val playlistId = playlistRepository.playlists.map { it.firstOrNull()?.id }.distinctUntilChanged()

    // Searching waits for typing to pause, so each key press on the remote keyboard doesn't query.
    private val results = combine(playlistId, query.debounce(SEARCH_DELAY_MS)) { id, text -> id to text }
        .flatMapLatest { (id, text) ->
            if (id == null || text.isBlank()) {
                flowOf(text to emptyList())
            } else {
                channelRepository.search(id, text).map { items ->
                    text to items.map {
                        SearchResult(
                            id = it.id,
                            number = it.number ?: (it.sortOrder + 1),
                            name = it.name,
                            groupTitle = it.groupTitle,
                            favorite = it.favorite,
                            zapList = it.groupId?.let { group -> ChannelList.Group(group) } ?: ChannelList.All,
                        )
                    }
                }
            }
        }

    val uiState: StateFlow<SearchUiState> = combine(query, results) { text, (searchedText, items) ->
        SearchUiState(query = text, results = items, searched = text.isNotBlank() && text == searchedText)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) {
        savedState[KEY_QUERY] = text
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch { channelRepository.toggleFavorite(channelId) }
    }

    /** The channel last played from these results, so focus can return to it. */
    fun takeLastPlayed(): Long? = channelRepository.takeLastPlayed()

    private companion object {
        const val KEY_QUERY = "query"
        const val SEARCH_DELAY_MS = 250L
    }
}
