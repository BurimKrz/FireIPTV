package com.fireiptv.feature.player

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
data class MenuCategory(val id: Long, val title: String, val contains: Boolean)

data class ChannelMenu(val channelId: Long, val name: String, val favorite: Boolean, val categories: List<MenuCategory>)

/** Long-press OK on a row of the player's side list: favourites, the user's categories, and hiding. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelMenuViewModel @Inject constructor(private val channels: ChannelRepository) : ViewModel() {

    private val channelId = MutableStateFlow<Long?>(null)

    val menu: StateFlow<ChannelMenu?> = channelId
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
                    ChannelMenu(channel.id, channel.name, favorite, lists.map { MenuCategory(it.id, it.title, it.id in holding) })
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

    fun toggleCategory(listId: Long) {
        val id = channelId.value ?: return
        viewModelScope.launch { channels.toggleInList(listId, id) }
    }

    fun hide() {
        val id = channelId.value ?: return
        viewModelScope.launch { channels.hideChannel(id) }
    }
}
