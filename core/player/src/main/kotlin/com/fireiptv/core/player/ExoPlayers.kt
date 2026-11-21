package com.fireiptv.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import okhttp3.OkHttpClient

/** The setup live TV and movies share: FFmpeg for audio the device can't decode, movie audio focus. */
@OptIn(UnstableApi::class)
internal fun buildExoPlayer(context: Context, loadControl: LoadControl): ExoPlayer = ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .setRenderersFactory(
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // FFmpeg (:core:ffmpeg) only takes tracks the device can't decode, e.g. MP2 audio.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
    .setHandleAudioBecomingNoisy(true)
    .build()

/** HTTP through the app's OkHttp client (shared connection pool), with the playlist's headers. */
@OptIn(UnstableApi::class)
internal fun httpDataSource(client: OkHttpClient, stream: StreamRequest): OkHttpDataSource.Factory =
    OkHttpDataSource.Factory(client).apply {
        stream.userAgent?.let { setUserAgent(it) }
        stream.referrer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
    }

/** The connection failed or timed out: worth retrying, or waiting for the network. */
internal fun PlaybackException.isNetworkError(): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    -> true
    else -> false
}

/** Network errors and HTTP refusals, which one-connection providers send for a few seconds after a switch. */
internal fun PlaybackException.isRetryable(): Boolean =
    isNetworkError() || errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

internal val PlaybackException.httpCode: Int?
    get() = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

internal fun ConnectivityManager.isOnline(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
