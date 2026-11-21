package com.fireiptv.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.fireiptv.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

sealed interface MovieStatus {
    /** Not opened yet, or stopped; the position is kept for [MoviePlayer.resume]. */
    data object Idle : MovieStatus
    data object Loading : MovieStatus
    data object Playing : MovieStatus
    data object Paused : MovieStatus

    /** Played to the end. */
    data object Ended : MovieStatus

    /** The device is offline; the movie goes on from the same place when a network is back. */
    data object WaitingForNetwork : MovieStatus

    data class Failed(val message: String, val httpCode: Int?) : MovieStatus
}

/**
 * Owns one ExoPlayer for a movie (or an episode): a file with a start and an end, unlike a live
 * channel. It keeps ExoPlayer's normal buffer and preloads nothing else. A dropped connection is
 * retried after 1, 3 and 6 s, each time from the position it stopped at, never from the start.
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class MoviePlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OkHttpClient,
    private val log: AppLog,
) {
    val player: ExoPlayer = buildExoPlayer(context, DefaultLoadControl())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Called on a binder thread: hop to main, where the player lives.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (_status.value == MovieStatus.WaitingForNetwork) {
                    Log.i(TAG, "network back, reopening ${current?.label}")
                    resume()
                }
            }
        }
    }

    private val _status = MutableStateFlow<MovieStatus>(MovieStatus.Idle)
    val status: StateFlow<MovieStatus> = _status.asStateFlow()

    private var current: StreamRequest? = null
    private var retries = 0
    private var retryJob: Job? = null
    private var steadyJob: Job? = null

    // Files without a seek index (some AVI, MP3 audio) can still seek, by assuming a constant bitrate.
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

    init {
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) = updateStatus()

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateStatus()

                override fun onPlayerError(error: PlaybackException) = handleError(error)
            },
        )
    }

    /** Where playback is, in milliseconds. */
    val positionMs: Long get() = player.currentPosition

    /** The movie's length, or null until the file says (or when it never does). */
    val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    /** Opens [stream] and plays it from [startMs]. */
    fun play(stream: StreamRequest, startMs: Long) {
        current = stream
        retries = 0
        retryJob?.cancel()
        _status.value = MovieStatus.Loading
        // Stop first so an old connection closes before the new one opens (one-connection accounts).
        player.stop()
        val source = DefaultMediaSourceFactory(httpDataSource(client, stream), extractors).createMediaSource(MediaItem.fromUri(stream.url))
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** Reopens the movie where it stopped, after [stop] or a failure. */
    fun resume() {
        if (current == null) return
        retries = 0
        open()
    }

    /** OK: pause or play; a stopped or failed movie opens again where it was. */
    fun togglePause() {
        when (_status.value) {
            MovieStatus.Idle, is MovieStatus.Failed -> resume()
            MovieStatus.Ended -> Unit
            else -> player.playWhenReady = !player.playWhenReady
        }
    }

    fun seekTo(positionMs: Long) {
        val end = durationMs ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0, end))
    }

    /** Closes the file and its connection; the position stays for [resume]. */
    fun stop() {
        retryJob?.cancel()
        steadyJob?.cancel()
        player.stop()
        _status.value = MovieStatus.Idle
    }

    fun release() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        player.release()
    }

    // The player keeps its position after stop() or an error, so prepare() continues from there.
    private fun open() {
        retryJob?.cancel()
        _status.value = MovieStatus.Loading
        player.prepare()
        player.playWhenReady = true
    }

    private fun updateStatus() {
        // Stopped, failed or offline: only open(), play() or resume() leave those.
        if (player.playbackState == Player.STATE_IDLE) return
        steadyJob?.cancel()
        _status.value = when (player.playbackState) {
            Player.STATE_BUFFERING -> MovieStatus.Loading
            Player.STATE_ENDED -> MovieStatus.Ended
            else -> if (player.playWhenReady) MovieStatus.Playing else MovieStatus.Paused
        }
        if (_status.value == MovieStatus.Playing) {
            // Retries refill only after steady playback, so a file that keeps dropping can't reopen forever.
            steadyJob = scope.launch {
                delay(STEADY_MS)
                retries = 0
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        val stream = current ?: return
        val httpCode = error.httpCode
        val what = "${error.errorCodeName}${httpCode?.let { " (HTTP $it)" }.orEmpty()}"
        Log.w(TAG, "playback error on ${stream.label} at ${player.currentPosition} ms: $what")
        when {
            // Offline: retrying now can't work. Wait for the network callback instead of failing.
            error.isNetworkError() && connectivity?.isOnline() == false -> {
                retryJob?.cancel()
                player.stop()
                _status.value = MovieStatus.WaitingForNetwork
            }
            error.isRetryable() && retries < RETRY_DELAYS_MS.size -> {
                val wait = RETRY_DELAYS_MS[retries++]
                log.retry("${stream.label}: $what; trying again from ${player.currentPosition / 1000} s")
                _status.value = MovieStatus.Loading
                retryJob = scope.launch {
                    delay(wait)
                    open()
                }
            }
            else -> {
                val message = error.toMessage()
                log.error("${stream.label}: $message")
                // Closes the connection if it's still open: a one-connection account needs it back.
                player.stop()
                _status.value = MovieStatus.Failed(message, httpCode)
            }
        }
    }

    private fun PlaybackException.toMessage(): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (val code = httpCode) {
            401, 403 -> "The provider refused this movie (HTTP $code). The account may be in use on another device."
            404 -> "The provider doesn't have this movie's file any more (HTTP 404)."
            null -> "The provider refused this movie."
            else -> "The provider answered HTTP $code."
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> "Can't reach the movie's file. Check the internet connection."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        -> "This movie's file format can't play on this device."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> decodeFailure()
        else -> "Playback failed ($errorCodeName)."
    }

    /** Which part of the file the device can't decode, e.g. "video (HEVC 10-bit)". */
    private fun PlaybackException.decodeFailure(): String {
        val format = (this as? ExoPlaybackException)?.rendererFormat
        val mimeType = format?.sampleMimeType
        return when {
            format == null || mimeType == null -> "This device can't decode this movie's video or sound."
            MimeTypes.isVideo(mimeType) -> "This device can't decode this movie's video (${videoName(format, mimeType)})."
            else -> "This device can't decode this movie's sound ($mimeType)."
        }
    }

    private fun videoName(format: Format, mimeType: String): String {
        val name = when (mimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_MP4V -> "MPEG-4 Part 2, e.g. Xvid or DivX"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_AV1 -> "AV1"
            else -> mimeType
        }
        val bits = format.colorInfo?.lumaBitdepth?.takeIf { it > 8 }
        return if (bits == null) name else "$name $bits-bit"
    }

    private companion object {
        const val TAG = "FireMovie"
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)
        const val STEADY_MS = 30_000L
    }
}
