package com.fireiptv.feature.browse.options

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.ChannelEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One of the user's categories, and whether the channel is in it. */
data class ListOption(val id: Long, val title: String, val contains: Boolean)

data class ChannelOptions(val channelId: Long, val name: String, val favorite: Boolean, val lists: List<ListOption>)

/** The menu a long press on a channel opens: favourite, and the user's categories. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelOptionsViewModel @Inject constructor(private val channels: ChannelRepository) : ViewModel() {

    private val channelId = MutableStateFlow<Long?>(null)

    val options: StateFlow<ChannelOptions?> = channelId
        .flatMapLatest { id -> flow<ChannelEntity?> { emit(id?.let { channels.channel(it) }) } }
        .flatMapLatest { channel ->
            if (channel == null) {
                flowOf(null)
            } else {
                combine(
                    channels.observeFavorite(channel),
                    channels.userLists(channel.playlistId),
                    channels.listsWith(channel),
                ) { favorite, lists, holding ->
                    ChannelOptions(channel.id, channel.name, favorite, lists.map { ListOption(it.id, it.title, it.id in holding) })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(channelId: Long) {
        this.channelId.value = channelId
    }

    fun toggleFavorite() {
        val id = channelId.value ?: return
        viewModelScope.launch { channels.toggleFavorite(id) }
    }

    fun toggleList(listId: Long) {
        val id = channelId.value ?: return
        viewModelScope.launch { channels.toggleInList(listId, id) }
    }

    /** Makes a category and puts the channel in it. */
    fun createList(title: String) {
        val id = channelId.value ?: return
        val name = title.trim().ifEmpty { return }
        viewModelScope.launch {
            val channel = channels.channel(id) ?: return@launch
            channels.toggleInList(channels.createList(channel.playlistId, name), id)
        }
    }
}
