package com.fireiptv.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
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

/** What the player needs to open a channel. [url] carries provider credentials: never log it. */
data class StreamRequest(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Shown in logs and errors instead of the URL. */
    val label: String,
)

/** How much media to have before playback starts, and before it resumes after a stall. */
data class BufferSettings(val startMs: Int, val resumeMs: Int, val minBufferMs: Int) {
    companion object {
        /** Live zaps want speed; a stall waits 2.5 s so it doesn't stutter. */
        val FAST_START = BufferSettings(startMs = 1_000, resumeMs = 2_500, minBufferMs = 15_000)

        /** Starts a second or two later, then rides out weak Wi-Fi with fewer pauses. */
        val STABLE = BufferSettings(startMs = 2_500, resumeMs = 5_000, minBufferMs = 25_000)
    }
}

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Loading : PlaybackStatus
    data object Playing : PlaybackStatus

    /** The device is offline; the channel restarts by itself when a network is back. */
    data object WaitingForNetwork : PlaybackStatus

    /** [httpCode] is set when the provider refused the stream, so callers can explain why. */
    data class Failed(val message: String, val httpCode: Int? = null) : PlaybackStatus
}

/**
 * Owns one ExoPlayer for the life of the player screen; every zap reuses it, so decoders and the
 * HTTP connection pool stay warm. Must be used from the main thread.
 *
 * Nothing loads ahead: a stream is opened only by [play] and closed by [stop] or the next [play].
 * Providers that allow one connection per account need the old stream closed before the new one
 * opens, so the first HTTP refusal after a zap is retried once the old connection has gone.
 */
@OptIn(UnstableApi::class)
class PlayerManager @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OkHttpClient,
    private val buffer: BufferSettings,
    private val log: AppLog,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(buffer.minBufferMs, maxOf(buffer.minBufferMs, 30_000), buffer.startMs, buffer.resumeMs)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                // FFmpeg (:core:ffmpeg) only takes tracks the device can't decode, e.g. MP2 audio.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Called on a binder thread: hop to main, where the player lives.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (_status.value == PlaybackStatus.WaitingForNetwork) {
                    Log.i(TAG, "network back, reopening ${current?.label}")
                    resume()
                }
            }
        }
    }
    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /** Milliseconds from [play] to the first video frame of the last zap. */
    private val _lastZapMs = MutableStateFlow<Long?>(null)
    val lastZapMs: StateFlow<Long?> = _lastZapMs.asStateFlow()

    private var current: StreamRequest? = null
    private var asHls = false
    private var retries = 0
    private var stuckReopened = false
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var steadyJob: Job? = null
    private var zapStartedAt = 0L

    private val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        // Many IPTV TS streams start without an IDR frame; accept other keyframes so video starts sooner.
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )

    init {
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            steadyJob?.cancel()
                            if (_status.value !is PlaybackStatus.Failed) _status.value = PlaybackStatus.Loading
                            // A stall after playback started gets the same watchdog as the first start.
                            if (stallJob?.isActive != true) watchStall()
                        }
                        Player.STATE_READY -> {
                            stallJob?.cancel()
                            _status.value = PlaybackStatus.Playing
                            // Retries refill only after steady playback, so a channel that keeps
                            // dropping can't reopen (and re-download its burst) forever.
                            steadyJob?.cancel()
                            steadyJob = scope.launch {
                                delay(STEADY_MS)
                                retries = 0
                            }
                        }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) = handleError(error)
            },
        )
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    if (zapStartedAt == 0L) return
                    val ms = SystemClock.elapsedRealtime() - zapStartedAt
                    zapStartedAt = 0L
                    _lastZapMs.value = ms
                    Log.i(TAG, "zap ${current?.label}: first frame in $ms ms (${if (asHls) "HLS" else "TS"})")
                }
            },
        )
    }

    fun play(stream: StreamRequest) {
        current = stream
        retries = 0
        stuckReopened = false
        asHls = isHls(stream.url)
        zapStartedAt = SystemClock.elapsedRealtime()
        open()
    }

    /** Reopens the current channel at the live edge, after [stop] or a failure. */
    fun resume() {
        if (current == null) return
        retries = 0
        stuckReopened = false
        zapStartedAt = SystemClock.elapsedRealtime()
        open()
    }

    /** Closes the stream and its connection; the last frame stays on screen. */
    fun stop() {
        retryJob?.cancel()
        stallJob?.cancel()
        steadyJob?.cancel()
        player.stop()
        _status.value = PlaybackStatus.Idle
    }

    fun release() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        player.release()
    }

    private fun open() {
        val stream = current ?: return
        retryJob?.cancel()
        _status.value = PlaybackStatus.Loading
        // Stop first so the old connection closes before the new one opens (one-connection accounts).
        player.stop()
        player.setMediaSource(mediaSource(stream))
        player.prepare()
        player.playWhenReady = true
        watchStall()
    }

    /**
     * A provider can accept the connection and then send nothing, e.g. while it still counts the
     * previous stream against a one-connection account. Reopen instead of waiting for a timeout.
     * Data that arrives but doesn't play (a decoder stuck on the stream's format) gets one reopen,
     * since repeating it would only re-download the same burst.
     */
    private fun watchStall() {
        val stream = current ?: return
        stallJob?.cancel()
        stallJob = scope.launch {
            var buffered = player.totalBufferedDuration
            while (true) {
                delay(STALL_TIMEOUT_MS)
                if (player.playbackState != Player.STATE_BUFFERING) return@launch
                val now = player.totalBufferedDuration
                val stuck = now >= buffer.resumeMs + STUCK_MARGIN_MS
                when {
                    !isOnline() -> {
                        waitForNetwork()
                        return@launch
                    }
                    stuck && !stuckReopened -> {
                        stuckReopened = true
                        Log.w(TAG, "${stream.label} has $now ms buffered but doesn't play, reopening")
                        log.retry("${stream.label}: the picture stopped although the stream kept arriving; reopened")
                    }
                    stuck -> {
                        fail("The stream keeps arriving but the picture doesn't move. This channel's video may not play on this device.")
                        return@launch
                    }
                    // Still loading, just slowly: keep waiting.
                    now > buffered -> {
                        buffered = now
                        continue
                    }
                    retries < RETRY_DELAYS_MS.size -> {
                        retries++
                        Log.w(TAG, "no data from ${stream.label} after $STALL_TIMEOUT_MS ms, reopening")
                        log.retry("${stream.label}: no data for ${STALL_TIMEOUT_MS / 1000} s; reopened")
                    }
                    else -> {
                        fail("The provider stopped sending this channel.")
                        return@launch
                    }
                }
                open()
                return@launch
            }
        }
    }

    private fun waitForNetwork() {
        retryJob?.cancel()
        zapStartedAt = 0L
        player.stop()
        _status.value = PlaybackStatus.WaitingForNetwork
    }

    private fun fail(message: String, httpCode: Int? = null) {
        zapStartedAt = 0L
        log.error("${current?.label}: $message")
        // Closes the connection if it's still open: a one-connection account needs it for the next channel.
        player.stop()
        _status.value = PlaybackStatus.Failed(message, httpCode)
    }

    private fun mediaSource(stream: StreamRequest): MediaSource {
        val dataSource = OkHttpDataSource.Factory(client).apply {
            stream.userAgent?.let { setUserAgent(it) }
            stream.referrer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }
        val item = MediaItem.fromUri(stream.url)
        return if (asHls) {
            HlsMediaSource.Factory(dataSource).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSource, extractors).createMediaSource(item)
        }
    }

    private fun handleError(error: PlaybackException) {
        val stream = current ?: return
        stallJob?.cancel()
        val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        Log.w(TAG, "playback error on ${stream.label}: ${error.errorCodeName}${httpCode?.let { " (HTTP $it)" }.orEmpty()}")
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // A link without a .m3u8 extension can still be HLS: try that once before giving up.
            !asHls && error.errorCode in CONTAINER_ERRORS -> {
                asHls = true
                open()
                return
            }
            // Offline: retrying now can't work. Wait for the network callback instead of failing.
            error.isNetworkError() && !isOnline() -> {
                waitForNetwork()
                return
            }
            error.isRetryable() && retries < RETRY_DELAYS_MS.size -> {
                val wait = RETRY_DELAYS_MS[retries++]
                log.retry("${stream.label}: ${error.errorCodeName}${httpCode?.let { " (HTTP $it)" }.orEmpty()}; trying again")
                _status.value = PlaybackStatus.Loading
                retryJob = scope.launch {
                    delay(wait)
                    open()
                }
                return
            }
        }
        fail(error.toMessage(), httpCode)
    }

    private fun PlaybackException.isNetworkError(): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> true
        else -> false
    }

    private fun isOnline(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun PlaybackException.isRetryable(): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> true
        else -> false
    }

    private fun PlaybackException.toMessage(): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            when (code) {
                401, 403 -> "The provider refused this channel (HTTP $code). The account may be in use on another device."
                404 -> "This channel is offline at the provider (HTTP 404)."
                null -> "The provider refused this channel."
                else -> "The provider answered HTTP $code."
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Can't reach the stream. Check the internet connection."
        in CONTAINER_ERRORS -> "This channel's stream format isn't supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "This device can't decode this channel's video."
        else -> "Playback failed ($errorCodeName)."
    }

    private fun isHls(url: String): Boolean =
        Util.inferContentType(android.net.Uri.parse(url)) == C.CONTENT_TYPE_HLS || ".m3u8" in url.lowercase()

    private companion object {
        const val TAG = "FirePlayer"
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)
        const val STALL_TIMEOUT_MS = 8_000L
        const val STUCK_MARGIN_MS = 3_000
        const val STEADY_MS = 30_000L
        val CONTAINER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
