package com.fireiptv.feature.player.movie

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fireiptv.core.player.MovieStatus
import com.fireiptv.core.ui.playbackTime

/**
 * A movie full screen. OK (or Play/Pause) pauses and plays, Left/Right (or ⏪/⏩) go back and
 * forward 10 s (held: 30 s, 1 min, 5 min), Up/Down show the progress bar, Menu (☰) chooses the
 * audio, the subtitles and the sleep timer, Back stops and returns to the movie's page.
 */
// ContentFrame's keepContentOnReset is marked unstable in Media3.
@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(onExit: () -> Unit, viewModel: MoviePlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status

    val focus = remember { FocusRequester() }
    LaunchedEffect(state.menuOpen) { if (!state.menuOpen) focus.requestFocus() }
    LaunchedEffect(status) { if (status == MovieStatus.Ended) onExit() }

    // Paused or stopped, the TV may dim and sleep.
    val view = LocalView.current
    val streaming = status == MovieStatus.Playing || status == MovieStatus.Loading
    DisposableEffect(view, streaming) {
        view.keepScreenOn = streaming
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            // Sees every press first, also those meant for the menu: any key counts as someone watching.
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && viewModel.onKey() -> true
                    // Handled here, before the menu's focused row, which would take the first Back to drop its focus.
                    state.menuOpen && event.key == Key.Back -> {
                        if (event.type == KeyEventType.KeyUp) viewModel.closeMenu()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || state.menuOpen) return@onKeyEvent false
                val firstPress = event.nativeKeyEvent.repeatCount == 0
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause ->
                        if (firstPress) viewModel.togglePause()
                    Key.DirectionLeft, Key.MediaRewind -> viewModel.seekPress(forward = false, firstPress = firstPress)
                    Key.DirectionRight, Key.MediaFastForward -> viewModel.seekPress(forward = true, firstPress = firstPress)
                    Key.DirectionUp, Key.DirectionDown -> viewModel.showControlsForAWhile()
                    Key.Menu -> viewModel.openMenu()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        ContentFrame(player = viewModel.player, modifier = Modifier.fillMaxSize(), keepContentOnReset = true)
        // Above the progress bar while it shows.
        Subtitles(viewModel.player, Modifier.fillMaxSize().padding(bottom = if (state.controlsVisible) CONTROLS_HEIGHT else 0.dp))

        if (status == MovieStatus.Loading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        val message = when {
            state.openError != null -> "Can't open this movie" to state.openError
            status is MovieStatus.Failed -> "Can't play this movie" to "${status.message}\nOK to try again from where it stopped."
            status == MovieStatus.WaitingForNetwork -> "No internet connection" to "The movie goes on by itself when the connection is back."
            status == MovieStatus.Idle && state.stopReason != null -> "Stopped" to "${state.stopReason}\nPress OK to go on."
            else -> null
        }
        message?.let { (title, body) -> Message(title, body.orEmpty(), Modifier.align(Alignment.Center)) }
        state.stillWatchingSeconds?.let { seconds ->
            Message(
                title = "Still watching?",
                body = "Press any key to keep watching. Otherwise the movie stops in $seconds s to save data.",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible && !state.menuOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Controls(viewModel.player, state)
        }

        if (state.menuOpen) {
            Menu(
                state = state,
                onAudio = viewModel::selectAudio,
                onSubtitles = viewModel::selectSubtitles,
                onSubtitlesOff = viewModel::subtitlesOff,
                onSleepTimer = viewModel::cycleSleepTimer,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Title, the progress bar, time played and time left. */
@OptIn(UnstableApi::class)
@Composable
private fun Controls(player: Player, state: MoviePlayerUiState) {
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1_000)
    val duration = progress.durationMs.takeIf { it > 0 }
    val position = state.seekTargetMs ?: progress.currentPositionMs
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (state.status == MovieStatus.Paused) "❚❚  ${state.title}" else state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            state.sleepMinutesLeft?.let {
                Text("Sleep in $it min", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
        state.tracks.unplayableSound?.let {
            Text(noSoundNote(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (duration != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((progress.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.35f)),
                )
                Box(
                    Modifier
                        .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Row {
            Text(playbackTime(position), style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
            if (duration != null) {
                Text("-${playbackTime(duration - position)}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** Menu (☰): audio tracks, subtitles and the sleep timer, over the right of the picture. */
@Composable
private fun Menu(
    state: MoviePlayerUiState,
    onAudio: (TrackOption) -> Unit,
    onSubtitles: (TrackOption) -> Unit,
    onSubtitlesOff: () -> Unit,
    onSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val tracks = state.tracks
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        item { Heading("Audio") }
        if (tracks.audio.isEmpty()) item { Note(tracks.unplayableSound?.let(::noSoundNote) ?: "No sound tracks yet.") }
        items(tracks.audio.size) { index ->
            val option = tracks.audio[index]
            Choice(option.label, option.selected, { onAudio(option) }, if (index == 0) Modifier.focusRequester(first) else Modifier)
        }
        item { Heading("Subtitles") }
        item {
            val modifierForOff = if (tracks.audio.isEmpty()) Modifier.focusRequester(first) else Modifier
            Choice("Off", tracks.subtitlesOff, onSubtitlesOff, modifierForOff)
        }
        if (tracks.subtitles.isEmpty()) item { Note("This movie has no subtitles.") }
        items(tracks.subtitles.size) { index ->
            val option = tracks.subtitles[index]
            Choice(option.label, option.selected, { onSubtitles(option) }, Modifier)
        }
        item { Heading("Sleep timer") }
        item {
            Choice(state.sleepMinutesLeft?.let { "Stops in $it min (OK to change)" } ?: "Off (OK for 30 min)", false, onSleepTimer, Modifier)
        }
    }
}

private fun noSoundNote(format: String) = "No sound: this device can't play this movie's $format audio."

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier) {
    DenseListItem(
        selected = chosen,
        onClick = onClick,
        headlineContent = { Text(if (chosen) "✓  $label" else label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

/** Media3's subtitle view: Compose has no renderer for text and picture subtitles yet. */
// SubtitleView.setCues is marked unstable in Media3.
@OptIn(UnstableApi::class)
@Composable
private fun Subtitles(player: Player, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(context) { SubtitleView(context).apply { setUserDefaultStyle() } }
    DisposableEffect(player, view) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) = view.setCues(cueGroup.cues)
        }
        view.setCues(player.currentCues.cues)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
private fun Message(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .widthIn(max = 640.dp)
            .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.medium)
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

private val CONTROLS_HEIGHT = 120.dp
