package com.fireiptv.feature.player.vlc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fireiptv.core.player.VlcStatus
import com.fireiptv.core.player.VlcVideo
import com.fireiptv.feature.player.movie.Message
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * A channel full screen in the built-in VLC, to compare its picture with FireIPTV's player. No
 * zapping or side list: Back returns to the channel list, OK shows the info line (or plays again
 * after the stream stopped).
 */
@Composable
fun VlcPlayerScreen(viewModel: VlcPlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Stopped, the TV may dim and sleep.
    val view = LocalView.current
    val streaming = status is VlcStatus.Playing || status == VlcStatus.Loading
    DisposableEffect(view, streaming) {
        view.keepScreenOn = streaming
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }

    // The info line shows when the picture starts, and on OK, for a few seconds.
    var infoRequest by remember { mutableIntStateOf(0) }
    var infoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(status is VlcStatus.Playing) { if (status is VlcStatus.Playing) infoRequest++ }
    LaunchedEffect(infoRequest) {
        if (infoRequest == 0) return@LaunchedEffect
        infoVisible = true
        delay(INFO_MS)
        infoVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            // Any key counts as someone watching.
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && viewModel.onKey() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        viewModel.retry()
                        infoRequest++
                    }
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        AndroidView(
            factory = { context -> VLCVideoLayout(context).also(viewModel::attach) },
            onRelease = { viewModel.detach() },
            modifier = Modifier.fillMaxSize(),
        )

        if (status == VlcStatus.Loading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        val message = when {
            state.missing -> "Can't open this channel" to "It isn't in the playlist any more. Go back and pick another."
            status is VlcStatus.Stopped -> "Stopped" to "${status.message}\nOK to play again, Back for the channel list."
            state.stopReason != null -> "Stopped" to "${state.stopReason}\nPress OK to go on."
            else -> null
        }
        message?.let { (title, body) -> Message(title, body, Modifier.align(Alignment.Center)) }
        state.stillWatchingSeconds?.let { seconds ->
            Message(
                title = "Still watching?",
                body = "Press any key to keep watching. Otherwise the channel stops in $seconds s to save data.",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = infoVisible && state.channelName != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 27.dp),
        ) {
            val video = (status as? VlcStatus.Playing)?.video
            Text(
                listOfNotNull(state.channelName, "VLC", video?.let(::videoInfo)).joinToString("  ·  "),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** "720×576 · 25 fps", what the stream says it carries. */
private fun videoInfo(video: VlcVideo): String {
    val size = "${video.width}×${video.height}"
    val fps = video.framesPerSecond ?: return size
    val rate = if (fps % 1.0 == 0.0) fps.toInt().toString() else String.format(Locale.ROOT, "%.2f", fps)
    return "$size  ·  $rate fps"
}

private const val INFO_MS = 5_000L
