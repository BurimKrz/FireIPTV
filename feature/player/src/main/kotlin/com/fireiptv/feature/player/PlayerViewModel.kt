package com.fireiptv.feature.player

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.account.AccountResult
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.data.usage.DataUsageRepository
import com.fireiptv.core.player.PlaybackStatus
import com.fireiptv.core.player.PlayerManager
import com.fireiptv.core.player.StreamRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

/** A row of the channel list that Left/Right opens over the picture. */
@Immutable
data class PanelChannel(val id: Long, val number: Int, val name: String, val logoUrl: String?, val favorite: Boolean)

/** The side list is open; it starts on the row at [startIndex], the channel in the banner. */
data class PanelState(val title: String, val currentId: Long, val startIndex: Int)

data class PlayerUiState(
    val channel: BannerChannel? = null,
    val bannerVisible: Boolean = true,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val lastZapMs: Long? = null,
    /** Set when the channel to open no longer exists (deleted by a refresh). */
    val missing: Boolean = false,
    /** Why the provider refused the stream, from the account status, when that explains it. */
    val accountNote: String? = null,
    val panel: PanelState? = null,
    val extras: PlayerExtras = PlayerExtras(),
)

/** What the player shows besides the channel: data-saving prompts, the sleep timer, stream info. */
data class PlayerExtras(
    /** Seconds left to answer "Still watching?"; null while the question isn't on screen. */
    val stillWatchingSeconds: Int? = null,
    /** Why the app stopped the stream by itself, shown until it plays again. */
    val stopReason: String? = null,
    /** Minutes until the sleep timer stops the stream; null when it's off. */
    val sleepMinutesLeft: Int? = null,
    /** Resolution and incoming data rate, e.g. "1080p · 6.2 Mb/s"; null until measured. */
    val streamInfo: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channels: ChannelRepository,
    private val playerManager: PlayerManager,
    private val accounts: AccountRepository,
    private val dataUsage: DataUsageRepository,
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
    private val panel = MutableStateFlow<PanelState?>(null)
    private val extras = MutableStateFlow(PlayerExtras())

    /** Set with the first channel: the playlist and list the side panel shows. */
    private val panelSource = MutableStateFlow<Pair<Long, ChannelList>?>(null)

    /** The side panel's channels; only queried while the panel is on screen. */
    val panelChannels: Flow<PagingData<PanelChannel>> = panelSource
        .flatMapLatest { source ->
            if (source == null) {
                flowOf(PagingData.empty())
            } else {
                channels.channels(source.first, source.second).map { page ->
                    page.map { PanelChannel(it.id, it.number ?: (it.sortOrder + 1), it.name, it.logoUrl, it.favorite) }
                }
            }
        }
        .cachedIn(viewModelScope)

    val uiState: StateFlow<PlayerUiState> = combine(
        combine(banner, bannerFavorite) { channel, favorite -> channel?.copy(favorite = favorite) },
        bannerVisible,
        combine(playerManager.status, accountNote, ::Pair),
        combine(playerManager.lastZapMs, missing, ::Pair),
        combine(panel, extras, ::Pair),
    ) { channel, visible, (status, note), (zapMs, missing), (panel, extras) ->
        PlayerUiState(channel, visible, status, zapMs, missing, note, panel, extras)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private var target: ChannelEntity? = null
    private var listTitle = ""
    private val zaps = Channel<Boolean>(Channel.UNLIMITED)
    private var playJob: Job? = null
    private var hideBannerJob: Job? = null
    private var stoppedInBackground = false
    private var playing: ChannelEntity? = null
    private var recordedId: Long? = null
    private var idleJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepChoice: Int? = null

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
                is ChannelList.Custom -> channels.listTitle(list.id).orEmpty()
            }
            panelSource.value = start.playlistId to zapList
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
                if (status !is PlaybackStatus.Idle) extras.update { it.copy(stopReason = null) }
            }
        }
        restartIdleTimer()
        measureStream()
    }

    /**
     * Every key press while the player is on screen. Returns true when the press only answered
     * "Still watching?", so it doesn't also zap or open something.
     */
    fun onKey(): Boolean {
        val answered = extras.value.stillWatchingSeconds != null
        if (answered) extras.update { it.copy(stillWatchingSeconds = null) }
        restartIdleTimer()
        return answered
    }

    /** Fast-forward (⏩) steps the sleep timer through off, 30, 60 and 90 minutes. */
    fun cycleSleepTimer() {
        val next = SLEEP_CHOICES_MIN[(SLEEP_CHOICES_MIN.indexOf(sleepChoice) + 1) % SLEEP_CHOICES_MIN.size]
        sleepChoice = next
        sleepJob?.cancel()
        extras.update { it.copy(sleepMinutesLeft = next) }
        if (next != null) {
            sleepJob = viewModelScope.launch {
                val endsAt = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(next.toLong())
                while (true) {
                    val left = endsAt - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    extras.update { it.copy(sleepMinutesLeft = ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()) }
                    delay(minOf(left, SLEEP_TICK_MS))
                }
                sleepChoice = null
                extras.update { it.copy(sleepMinutesLeft = null) }
                stopToSaveData("The sleep timer ran out, so the stream stopped.")
            }
        }
        showBannerForAWhile()
    }

    /**
     * After hours with no key press, asks once whether anyone is still watching, and stops the
     * stream if nobody answers: a TV left on would otherwise download all night.
     */
    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(STILL_WATCHING_AFTER_MS)
            // Stopped or failed: nothing downloads, so only ask once the channel plays again by itself.
            playerManager.status.first { it is PlaybackStatus.Playing }
            for (left in STILL_WATCHING_ANSWER_S downTo 1) {
                extras.update { it.copy(stillWatchingSeconds = left) }
                delay(1_000)
            }
            extras.update { it.copy(stillWatchingSeconds = null) }
            stopToSaveData("Nobody pressed a key for ${TimeUnit.MILLISECONDS.toHours(STILL_WATCHING_AFTER_MS)} hours, so the stream stopped to save data.")
        }
    }

    private fun stopToSaveData(reason: String) {
        if (playerManager.status.value is PlaybackStatus.Idle) return
        playJob?.cancel()
        playerManager.stop()
        extras.update { it.copy(stopReason = reason) }
        hideBannerJob?.cancel()
        bannerVisible.value = false
        panel.value = null
    }

    /**
     * Resolution and the rate data actually arrives at, from the system's byte count over the last
     * few seconds (no request). Shown on the banner, so it updates only while the banner is up.
     */
    private fun measureStream() {
        viewModelScope.launch {
            val samples = ArrayDeque<Pair<Long, Long>>()
            while (true) {
                delay(1_000)
                val bytes = dataUsage.receivedBytes()
                if (playerManager.status.value !is PlaybackStatus.Playing || bytes == null) {
                    // A new channel starts with a burst; measure only once it plays.
                    samples.clear()
                    extras.update { it.copy(streamInfo = null) }
                    continue
                }
                samples.addLast(SystemClock.elapsedRealtime() to bytes)
                if (samples.size > RATE_SAMPLES) samples.removeFirst()
                if (!bannerVisible.value) continue
                val height = player.videoSize.height
                val resolution = when {
                    height <= 0 -> null
                    height >= 2_000 -> "4K"
                    else -> "${height}p"
                }
                val rate = if (samples.size < 3) {
                    null
                } else {
                    val (startAt, startBytes) = samples.first()
                    val (endAt, endBytes) = samples.last()
                    // Bytes per millisecond × 8 = kilobits per second.
                    "%.1f Mb/s".format((endBytes - startBytes) * 8.0 / (endAt - startAt) / 1_000)
                }
                extras.update { it.copy(streamInfo = listOfNotNull(resolution, rate).joinToString(" · ").ifEmpty { null }) }
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

    /** Left/Right: the channels of the list being zapped, starting on the one in the banner. */
    fun togglePanel() {
        if (panel.value != null) {
            panel.value = null
            return
        }
        val current = target ?: return
        viewModelScope.launch {
            val index = channels.position(current, zapList) ?: 0
            panel.value = PanelState(listTitle, current.id, index)
            hideBannerJob?.cancel()
            bannerVisible.value = false
        }
    }

    fun closePanel() {
        panel.value = null
    }

    /** OK on a row of the side panel plays it at once and closes the panel. */
    fun playFromPanel(channelId: Long) {
        panel.value = null
        viewModelScope.launch {
            val channel = channels.channel(channelId) ?: return@launch
            playJob?.cancel()
            show(channel)
            play(channel)
        }
    }

    /** OK on a failed or stopped channel tries it again. */
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
        restartIdleTimer()
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
        private const val STILL_WATCHING_AFTER_MS = 3 * 60 * 60 * 1_000L
        private const val STILL_WATCHING_ANSWER_S = 60
        private val SLEEP_CHOICES_MIN = listOf(null, 30, 60, 90)
        private const val MINUTE_MS = 60_000L
        private const val SLEEP_TICK_MS = 15_000L
        private const val RATE_SAMPLES = 6

        /** Refusals that can come from the account: login, expiry, or too many connections. */
        private val REFUSAL_CODES = setOf(401, 403, 429, 456, 458, 509, 512, 513)
    }
}
