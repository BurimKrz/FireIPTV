package com.fireiptv.feature.player.vlc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.player.StreamRequest
import com.fireiptv.core.player.VlcPlayer
import com.fireiptv.core.player.VlcStatus
import com.fireiptv.feature.player.STILL_WATCHING_AFTER_MS
import com.fireiptv.feature.player.stillWatchingCountdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class VlcPlayerUiState(
    val channelName: String? = null,
    val status: VlcStatus = VlcStatus.Idle,
    /** Set when the channel to open no longer exists (deleted by a refresh). */
    val missing: Boolean = false,
    /** Seconds left to answer "Still watching?"; null while the question isn't on screen. */
    val stillWatchingSeconds: Int? = null,
    /** Why playback stopped by itself to save data, shown until OK plays again. */
    val stopReason: String? = null,
)

/** One channel in the built-in VLC (Settings → Channel player: VLC). */
@HiltViewModel
class VlcPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channels: ChannelRepository,
    private val vlc: VlcPlayer,
) : ViewModel() {

    private val channelId: Long = checkNotNull(savedStateHandle[ARG_CHANNEL_ID])

    private val _state = MutableStateFlow(VlcPlayerUiState())
    val state: StateFlow<VlcPlayerUiState> = _state.asStateFlow()

    private var channel: ChannelEntity? = null
    private var idleJob: Job? = null

    init {
        viewModelScope.launch { vlc.status.collect { status -> _state.update { it.copy(status = status) } } }
    }

    /** The screen's video view is in place; VLC needs it before the first [play]. */
    fun attach(layout: VLCVideoLayout) = vlc.attach(layout)

    fun detach() = vlc.detach()

    /** The screen is visible: open the channel (again, after [onBackground]). */
    fun onForeground() {
        viewModelScope.launch {
            val channel = channel ?: channels.channel(channelId)
            if (channel == null) {
                _state.update { it.copy(missing = true) }
                return@launch
            }
            this@VlcPlayerViewModel.channel = channel
            _state.update { it.copy(channelName = channel.name) }
            play(channel)
        }
    }

    /** Nothing streams while the app isn't on screen. */
    fun onBackground() {
        idleJob?.cancel()
        vlc.stop()
    }

    /**
     * Every key press. Returns true when the press only answered "Still watching?", so it
     * doesn't also act.
     */
    fun onKey(): Boolean {
        val answered = _state.value.stillWatchingSeconds != null
        if (answered) _state.update { it.copy(stillWatchingSeconds = null) }
        restartIdleTimer()
        return answered
    }

    /** OK after the stream stopped: open it again. */
    fun retry() {
        val status = _state.value.status
        if (status is VlcStatus.Stopped || status == VlcStatus.Idle) channel?.let(::play)
    }

    private fun play(channel: ChannelEntity) {
        _state.update { it.copy(stopReason = null) }
        vlc.play(StreamRequest(url = channel.streamUrl, userAgent = channel.userAgent, referrer = channel.referrer, label = channel.name))
        restartIdleTimer()
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(STILL_WATCHING_AFTER_MS)
            vlc.status.first { it is VlcStatus.Playing }
            stillWatchingCountdown { left -> _state.update { it.copy(stillWatchingSeconds = left) } }
            val hours = TimeUnit.MILLISECONDS.toHours(STILL_WATCHING_AFTER_MS)
            vlc.stop()
            _state.update {
                it.copy(stillWatchingSeconds = null, stopReason = "Nobody pressed a key for $hours hours, so the channel stopped to save data.")
            }
        }
    }

    override fun onCleared() {
        vlc.release()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
    }
}
