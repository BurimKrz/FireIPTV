package com.fireiptv.feature.player.movie

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.fireiptv.core.data.di.ApplicationScope
import com.fireiptv.core.data.playlist.ImportException
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.SubtitleChoice
import com.fireiptv.core.data.vod.Movie
import com.fireiptv.core.data.vod.MovieRepository
import com.fireiptv.core.data.vod.WatchProgressRepository
import com.fireiptv.core.player.MoviePlayer
import com.fireiptv.core.player.MovieStatus
import com.fireiptv.core.player.StreamRequest
import com.fireiptv.feature.player.STILL_WATCHING_AFTER_MS
import com.fireiptv.feature.player.audioLabel
import com.fireiptv.feature.player.codecName
import com.fireiptv.feature.player.nextSleepChoice
import com.fireiptv.feature.player.sleepCountdown
import com.fireiptv.feature.player.stillWatchingCountdown
import com.fireiptv.feature.player.subtitleLabel
import com.fireiptv.feature.player.trackLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One audio or subtitle track the Menu (☰) offers. */
@Immutable
data class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

@Immutable
data class MovieTracks(
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val subtitlesOff: Boolean = true,
    /** Set when the file has sound but none this device can play, e.g. "DTS": the movie plays silent. */
    val unplayableSound: String? = null,
)

data class MoviePlayerUiState(
    val title: String = "",
    val status: MovieStatus = MovieStatus.Idle,
    /** Set when the movie couldn't be opened at all, with the reason. */
    val openError: String? = null,
    val controlsVisible: Boolean = true,
    /** Where Left/Right are taking playback; shown on the bar until the seek happens. */
    val seekTargetMs: Long? = null,
    val menuOpen: Boolean = false,
    val tracks: MovieTracks = MovieTracks(),
    /** Minutes until the sleep timer stops the movie; null when it's off. */
    val sleepMinutesLeft: Int? = null,
    /** Seconds left to answer "Still watching?"; null while the question isn't on screen. */
    val stillWatchingSeconds: Int? = null,
    /** Why the app stopped the movie by itself, shown until it plays again. */
    val stopReason: String? = null,
)

/**
 * A movie playing full screen: pause, seek, audio and subtitle choice, and its position saved
 * every 10 s and when it closes, so it can be resumed.
 */
@HiltViewModel
class MoviePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movies: MovieRepository,
    private val progress: WatchProgressRepository,
    private val settings: SettingsRepository,
    private val moviePlayer: MoviePlayer,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle[ARG_PLAYLIST_ID])
    private val streamId: String = checkNotNull(savedStateHandle[ARG_STREAM_ID])
    private val startMs: Long = checkNotNull(savedStateHandle[ARG_START_MS])

    val player: Player get() = moviePlayer.player

    private val _state = MutableStateFlow(MoviePlayerUiState())
    val state: StateFlow<MoviePlayerUiState> = _state.asStateFlow()

    private var movie: Movie? = null
    private var hideControlsJob: Job? = null
    private var seekJob: Job? = null
    private var holdStartedAt = 0L
    private var lastStepAt = 0L
    private var idleJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepChoice: Int? = null
    private var stoppedInBackground = false

    private val tracksListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(tracks = tracks.toOptions()) }
        }
    }

    init {
        player.addListener(tracksListener)
        viewModelScope.launch { open() }
        viewModelScope.launch {
            moviePlayer.status.collect { status ->
                _state.update { it.copy(status = status, stopReason = if (status is MovieStatus.Idle) it.stopReason else null) }
                when (status) {
                    // Paused stays on screen with its controls; playing hides them after a while.
                    MovieStatus.Paused -> showControls()
                    MovieStatus.Playing -> if (_state.value.controlsVisible) showControlsForAWhile()
                    MovieStatus.Ended -> saveFinished()
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                if (moviePlayer.status.value == MovieStatus.Playing) saveProgress()
            }
        }
        restartIdleTimer()
    }

    private suspend fun open() {
        val movie = movies.movie(playlistId, streamId)
        if (movie == null) {
            _state.update { it.copy(openError = "This movie isn't in the list any more. Go back and open it again.") }
            return
        }
        val file = try {
            movies.file(movie)
        } catch (e: ImportException) {
            _state.update { it.copy(openError = e.message) }
            return
        }
        this.movie = movie
        _state.update { it.copy(title = movie.name) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.value)
            .apply {
                when (val subtitles = settings.subtitleChoice.value) {
                    SubtitleChoice.Unset -> Unit
                    SubtitleChoice.Off -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    is SubtitleChoice.Language -> setPreferredTextLanguage(subtitles.code)
                }
            }
            .build()
        moviePlayer.play(StreamRequest(url = file.url, userAgent = file.userAgent, label = movie.name), startMs)
    }

    /**
     * Every key press while the movie is on screen. Returns true when the press only answered
     * "Still watching?", so it doesn't also pause or seek.
     */
    fun onKey(): Boolean {
        val answered = _state.value.stillWatchingSeconds != null
        if (answered) _state.update { it.copy(stillWatchingSeconds = null) }
        restartIdleTimer()
        return answered
    }

    /** OK or Play/Pause: pause or play; a stopped or failed movie opens again where it was. */
    fun togglePause() {
        moviePlayer.togglePause()
        showControlsForAWhile()
    }

    /**
     * Left/Right (or ⏪/⏩): 10 s a press. Held down, the steps grow to 30 s, 1 min and 5 min.
     * The seek itself waits until the presses stop, so holding the key opens one connection, not dozens.
     */
    fun seekPress(forward: Boolean, firstPress: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (firstPress) {
            holdStartedAt = now
        } else if (now - lastStepAt < HOLD_STEP_MS) {
            return
        }
        lastStepAt = now
        val step = seekStepMs(now - holdStartedAt)
        val from = _state.value.seekTargetMs ?: moviePlayer.positionMs
        val target = (from + if (forward) step else -step).coerceIn(0, moviePlayer.durationMs ?: Long.MAX_VALUE)
        _state.update { it.copy(seekTargetMs = target) }
        showControlsForAWhile()
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SEEK_SETTLE_MS)
            moviePlayer.seekTo(target)
            _state.update { it.copy(seekTargetMs = null) }
        }
    }

    fun showControlsForAWhile() {
        showControls()
        hideControlsJob = viewModelScope.launch {
            delay(CONTROLS_MS)
            // Paused keeps them up, so it's clear why the picture stands still.
            if (moviePlayer.status.value != MovieStatus.Paused) _state.update { it.copy(controlsVisible = false) }
        }
    }

    private fun showControls() {
        hideControlsJob?.cancel()
        _state.update { it.copy(controlsVisible = true) }
    }

    fun openMenu() {
        hideControlsJob?.cancel()
        _state.update { it.copy(menuOpen = true, controlsVisible = false) }
    }

    fun closeMenu() {
        _state.update { it.copy(menuOpen = false) }
    }

    /** Plays this audio track; its language, when the file names one, is chosen for the next movies too. */
    fun selectAudio(option: TrackOption) {
        select(option, C.TRACK_TYPE_AUDIO)
        trackLanguage(option.group.getTrackFormat(option.index))?.let { viewModelScope.launch { settings.setAudioLanguage(it) } }
    }

    /** Shows these subtitles; their language, when the file names one, is chosen for the next movies too. */
    fun selectSubtitles(option: TrackOption) {
        select(option, C.TRACK_TYPE_TEXT)
        trackLanguage(option.group.getTrackFormat(option.index))?.let { viewModelScope.launch { settings.setSubtitleLanguage(it) } }
    }

    fun subtitlesOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        viewModelScope.launch { settings.setSubtitlesOff() }
    }

    private fun select(option: TrackOption, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    /** Steps the sleep timer through off, 30, 60 and 90 minutes. */
    fun cycleSleepTimer() {
        val next = nextSleepChoice(sleepChoice)
        sleepChoice = next
        sleepJob?.cancel()
        _state.update { it.copy(sleepMinutesLeft = next) }
        if (next != null) {
            sleepJob = viewModelScope.launch {
                sleepCountdown(next) { left -> _state.update { it.copy(sleepMinutesLeft = left) } }
                sleepChoice = null
                _state.update { it.copy(sleepMinutesLeft = null) }
                stopToSaveData("The sleep timer ran out, so the movie stopped.")
            }
        }
    }

    /**
     * After hours with no key press, asks once whether anyone is still watching, and stops the
     * movie if nobody answers: a TV left on would otherwise download all night.
     */
    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(STILL_WATCHING_AFTER_MS)
            moviePlayer.status.first { it == MovieStatus.Playing }
            stillWatchingCountdown { left -> _state.update { it.copy(stillWatchingSeconds = left) } }
            _state.update { it.copy(stillWatchingSeconds = null) }
            stopToSaveData("Nobody pressed a key for ${TimeUnit.MILLISECONDS.toHours(STILL_WATCHING_AFTER_MS)} hours, so the movie stopped to save data.")
        }
    }

    private fun stopToSaveData(reason: String) {
        if (moviePlayer.status.value == MovieStatus.Idle) return
        saveProgress()
        moviePlayer.stop()
        _state.update { it.copy(stopReason = reason, menuOpen = false) }
        showControls()
    }

    /** Leaving the app (Home, another app) closes the file: nothing downloads unwatched. */
    fun onBackground() {
        if (moviePlayer.status.value == MovieStatus.Idle) return
        saveProgress()
        moviePlayer.stop()
        stoppedInBackground = true
    }

    fun onForeground() {
        if (!stoppedInBackground) return
        stoppedInBackground = false
        restartIdleTimer()
        moviePlayer.resume()
    }

    /**
     * Reads the position here, on the main thread (the player's), and writes it in the app's scope,
     * which leaving the screen doesn't cancel. A movie played to the end was saved as finished.
     */
    private fun saveProgress() {
        val movie = movie ?: return
        if (moviePlayer.status.value == MovieStatus.Ended) return
        val position = moviePlayer.positionMs
        val duration = moviePlayer.durationMs
        appScope.launch { progress.save(movie, position, duration) }
    }

    private fun saveFinished() {
        val movie = movie ?: return
        val duration = moviePlayer.durationMs
        appScope.launch { progress.saveFinished(movie, duration) }
    }

    private fun Tracks.toOptions(): MovieTracks {
        val audio = ArrayList<TrackOption>()
        val subtitles = ArrayList<TrackOption>()
        var unplayable: String? = null
        for (group in groups) {
            val list = when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio
                C.TRACK_TYPE_TEXT -> subtitles
                else -> continue
            }
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                // A track this device can't play isn't offered.
                if (!group.isTrackSupported(index)) {
                    if (group.type == C.TRACK_TYPE_AUDIO) unplayable = codecName(format.sampleMimeType) ?: format.sampleMimeType
                    continue
                }
                val number = list.size + 1
                val label = if (group.type == C.TRACK_TYPE_AUDIO) audioLabel(format, number) else subtitleLabel(format, number)
                list += TrackOption(group, index, label, group.isTrackSelected(index))
            }
        }
        return MovieTracks(
            audio = audio,
            subtitles = subtitles,
            subtitlesOff = subtitles.none { it.selected },
            unplayableSound = if (audio.isEmpty()) unplayable else null,
        )
    }

    override fun onCleared() {
        saveProgress()
        player.removeListener(tracksListener)
        moviePlayer.release()
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_STREAM_ID = "streamId"
        const val ARG_START_MS = "startMs"
        private const val SAVE_EVERY_MS = 10_000L
        private const val CONTROLS_MS = 4_000L
        private const val SEEK_SETTLE_MS = 700L
        private const val HOLD_STEP_MS = 250L
    }
}

/** How far one seek step goes after the key has been held for [heldMs]. */
internal fun seekStepMs(heldMs: Long): Long = when {
    heldMs < 2_000 -> 10_000
    heldMs < 4_000 -> 30_000
    heldMs < 6_000 -> 60_000
    else -> 300_000
}
