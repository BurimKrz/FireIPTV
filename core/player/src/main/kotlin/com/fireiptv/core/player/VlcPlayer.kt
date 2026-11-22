package com.fireiptv.core.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.fireiptv.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import javax.inject.Inject

/** The picture VLC is showing, for the info line. */
data class VlcVideo(val width: Int, val height: Int, val framesPerSecond: Double?)

sealed interface VlcStatus {
    data object Idle : VlcStatus
    data object Loading : VlcStatus
    data class Playing(val video: VlcVideo?) : VlcStatus

    /** The stream stopped by itself, or VLC couldn't play it; [message] says which, and after how long. */
    data class Stopped(val message: String) : VlcStatus
}

/**
 * Owns one libVLC player for a live channel: a second engine next to ExoPlayer, to compare the
 * picture. It decodes in software so VLC's Yadif (2x) deinterlacer can rebuild all 50 moments a
 * second of interlaced TV (hardware decoding skips VLC's filters). It doesn't retry: a stream that
 * stops is reported with how long it played, which is what the comparison needs to know.
 * Must be used from the main thread; [attach] the video view before [play].
 */
class VlcPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val log: AppLog,
) {
    private val libVlc = LibVLC(
        context,
        arrayListOf(
            // Deinterlace only video that is marked interlaced, with Yadif at the full field rate.
            "--deinterlace=-1",
            "--deinterlace-mode=yadif2x",
            // libVLC's Android default is 16-bit colour (RV16), which bands gradients.
            "--android-display-chroma",
            "RV32",
        ),
    )
    private val player = MediaPlayer(libVlc)

    private val _status = MutableStateFlow<VlcStatus>(VlcStatus.Idle)
    val status: StateFlow<VlcStatus> = _status.asStateFlow()

    private var current: StreamRequest? = null
    private var startedAt = 0L

    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> _status.value = VlcStatus.Loading
                MediaPlayer.Event.Playing, MediaPlayer.Event.Vout -> _status.value = VlcStatus.Playing(video())
                MediaPlayer.Event.EndReached -> stopped("the stream ended (the provider closed it)")
                MediaPlayer.Event.EncounteredError -> stopped("VLC couldn't open or decode the stream")
                else -> Unit
            }
        }
    }

    fun attach(layout: VLCVideoLayout) = player.attachViews(layout, null, false, false)

    fun detach() = player.detachViews()

    fun play(stream: StreamRequest) {
        current = stream
        startedAt = SystemClock.elapsedRealtime()
        _status.value = VlcStatus.Loading
        // Stop first so an old connection closes before the new one opens (one-connection accounts).
        player.stop()
        val media = Media(libVlc, stream.url.toUri())
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=$NETWORK_CACHING_MS")
        stream.userAgent?.let { media.addOption(":http-user-agent=$it") }
        stream.referrer?.let { media.addOption(":http-referrer=$it") }
        player.media = media
        // The player holds its own reference.
        media.release()
        player.play()
        Log.i(TAG, "playing ${stream.label} with libVLC ${LibVLC.version()}")
    }

    /** Closes the stream and its connection. */
    fun stop() {
        player.stop()
        _status.value = VlcStatus.Idle
    }

    fun release() {
        player.setEventListener(null)
        player.stop()
        player.release()
        libVlc.release()
    }

    private fun stopped(why: String) {
        val label = current?.label ?: return
        // An error is often followed by the end of the stream: report the first.
        if (_status.value is VlcStatus.Stopped) return
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000
        val message = "VLC stopped after $seconds s: $why."
        log.error("$label: $message")
        // Closes the connection if it's still open: a one-connection account needs it back.
        player.stop()
        _status.value = VlcStatus.Stopped(message)
    }

    private fun video(): VlcVideo? {
        val track: IMedia.VideoTrack = player.currentVideoTrack ?: return null
        val fps = if (track.frameRateDen > 0) track.frameRateNum.toDouble() / track.frameRateDen else null
        return VlcVideo(track.width, track.height, fps)
    }

    private companion object {
        const val TAG = "FireVlc"

        // libVLC's default is 1 s; live TV over Wi-Fi rides out more with a little extra.
        const val NETWORK_CACHING_MS = 2_000
    }
}
