package com.fireiptv.feature.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.account.AccountResult
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.player.PlaybackStatus
import com.fireiptv.core.player.PlayerManager
import com.fireiptv.core.player.StreamRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** The channel in the banner: the one playing, or the one a zap is heading to. */
@Immutable
data class BannerChannel(
    val number: Int,
    val name: String,
    val listTitle: String,
    val logoUrl: String?,
    val favorite: Boolean = false,
)

data class PlayerUiState(
    val channel: BannerChannel? = null,
    val bannerVisible: Boolean = true,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val lastZapMs: Long? = null,
    /** Set when the channel to open no longer exists (deleted by a refresh). */
    val missing: Boolean = false,
    /** Why the provider refused the stream, from the account status, when that explains it. */
    val accountNote: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channels: ChannelRepository,
    private val playerManager: PlayerManager,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val startChannelId: Long = checkNotNull(savedStateHandle[ARG_CHANNEL_ID])

    /** The list the channel was opened from. */
    private val openedFrom: ChannelList = ChannelList.decode(savedStateHandle.get<String>(ARG_LIST))

    /** The list Up/Down moves through; set once the first channel is loaded. */
    private var zapList: ChannelList = openedFrom

    val player: Player get() = playerManager.player

    private val banner = MutableStateFlow<BannerChannel?>(null)
    private val bannerTarget = MutableStateFlow<ChannelEntity?>(null)
    private val bannerFavorite = bannerTarget.flatMapLatest { channel ->
        if (channel == null) flowOf(false) else channels.observeFavorite(channel)
    }
    private val bannerVisible = MutableStateFlow(true)
    private val missing = MutableStateFlow(false)
    private val accountNote = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlayerUiState> = combine(
        combine(banner, bannerFavorite) { channel, favorite -> channel?.copy(favorite = favorite) },
        bannerVisible,
        combine(playerManager.status, accountNote, ::Pair),
        playerManager.lastZapMs,
        missing,
    ) { channel, visible, (status, note), zapMs, missing ->
        PlayerUiState(channel, visible, status, zapMs, missing, note)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private var target: ChannelEntity? = null
    private var listTitle = ""
    private val zaps = Channel<Boolean>(Channel.UNLIMITED)
    private var playJob: Job? = null
    private var hideBannerJob: Job? = null
    private var stoppedInBackground = false
    private var playing: ChannelEntity? = null
    private var recordedId: Long? = null

    init {
        viewModelScope.launch {
            val start = channels.channel(startChannelId)
            if (start == null) {
                missing.value = true
                return@launch
            }
            // Recents reorder as you watch, so from there Up/Down moves through the channel's group.
            zapList = if (openedFrom == ChannelList.Recents) {
                start.groupId?.let { ChannelList.Group(it) } ?: ChannelList.All
            } else {
                openedFrom
            }
            listTitle = when (val list = zapList) {
                ChannelList.All, ChannelList.Recents -> "All channels"
                ChannelList.Favorites -> "Favourites"
                is ChannelList.Group -> channels.groupTitle(list.id).orEmpty()
            }
            show(start)
            play(start)

            // Presses are handled one by one so none is lost; each moves the banner at once, and the
            // stream follows only once the presses stop, so skipping past channels opens nothing.
            for (forward in zaps) {
                val from = target ?: continue
                val next = channels.neighbour(from, zapList, forward) ?: continue
                show(next)
                playJob?.cancel()
                playJob = launch {
                    delay(ZAP_DEBOUNCE_MS)
                    play(next)
                }
            }
        }
    }

    init {
        // A channel counts as watched once it actually plays, not when zapped past.
        viewModelScope.launch {
            playerManager.status.collect { status ->
                val channel = playing
                if (status is PlaybackStatus.Playing && channel != null && channel.id != recordedId) {
                    recordedId = channel.id
                    channels.recordWatched(channel)
                }
                if (status is PlaybackStatus.Failed && status.httpCode in REFUSAL_CODES) {
                    // One small account request, only now, to say why instead of showing a bare HTTP code.
                    launch { accountNote.value = explainRefusal() }
                } else if (status !is PlaybackStatus.Failed) {
                    accountNote.value = null
                }
            }
        }
    }

    /** [forward] = the next channel down the list. */
    fun zap(forward: Boolean) {
        zaps.trySend(forward)
    }

    fun toggleBanner() {
        if (bannerVisible.value) {
            hideBannerJob?.cancel()
            bannerVisible.value = false
        } else {
            showBannerForAWhile()
        }
    }

    /** Menu (☰) adds the channel in the banner to favourites, or removes it. */
    fun toggleFavorite() {
        val channel = target ?: return
        viewModelScope.launch { channels.toggleFavorite(channel.id) }
        showBannerForAWhile()
    }

    /** OK on a failed channel tries it again. */
    fun retry() {
        playerManager.resume()
        showBannerForAWhile()
    }

    fun togglePlayback() {
        val status = playerManager.status.value
        if (status is PlaybackStatus.Idle || status is PlaybackStatus.Failed) {
            playerManager.resume()
        } else {
            playJob?.cancel()
            playerManager.stop()
        }
        showBannerForAWhile()
    }

    /** Leaving the app (Home, another app) closes the stream: nothing downloads unwatched. */
    fun onBackground() {
        if (playerManager.status.value is PlaybackStatus.Idle) return
        playJob?.cancel()
        playerManager.stop()
        stoppedInBackground = true
    }

    fun onForeground() {
        if (!stoppedInBackground) return
        stoppedInBackground = false
        target?.let { play(it) }
    }

    private fun show(channel: ChannelEntity) {
        target = channel
        bannerTarget.value = channel
        banner.value = BannerChannel(
            number = channel.number ?: (channel.sortOrder + 1),
            name = channel.name,
            listTitle = listTitle,
            logoUrl = channel.logoUrl,
        )
        showBannerForAWhile()
    }

    private fun showBannerForAWhile() {
        bannerVisible.value = true
        hideBannerJob?.cancel()
        hideBannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            bannerVisible.value = false
        }
    }

    private fun play(channel: ChannelEntity) {
        playing = channel
        channels.setLastPlayed(channel.id)
        playerManager.play(
            StreamRequest(url = channel.streamUrl, userAgent = channel.userAgent, referrer = channel.referrer, label = channel.name),
        )
    }

    private suspend fun explainRefusal(): String? {
        val info = (accounts.load() as? AccountResult.Loaded)?.info ?: return null
        val active = info.activeConnections
        val max = info.maxConnections
        return when {
            info.status != null && !info.status.equals("Active", ignoreCase = true) ->
                "Your provider reports the account as ${info.status}."
            info.expiresAt != null && TimeUnit.SECONDS.toMillis(info.expiresAt!!) < System.currentTimeMillis() ->
                "Your account expired on ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(TimeUnit.SECONDS.toMillis(info.expiresAt!!)))}."
            // Panels can keep counting a stream for a while after it closed, so don't claim another device.
            active != null && max != null && active >= max ->
                "Your provider reports $active of $max connections in use, so it won't open another. " +
                    "Stop playback on any other device; if nothing else is playing, wait a minute, then press OK."
            else -> "Your account is active and free, so the provider is refusing this channel itself."
        }
    }

    override fun onCleared() {
        playerManager.release()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_LIST = "list"
        private const val ZAP_DEBOUNCE_MS = 250L
        private const val BANNER_MS = 4_000L

        /** Refusals that can come from the account: login, expiry, or too many connections. */
        private val REFUSAL_CODES = setOf(401, 403, 429, 456, 458, 509, 512, 513)
    }
}
