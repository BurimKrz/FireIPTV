package com.fireiptv.feature.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.ContentFrame
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fireiptv.core.player.PlaybackStatus
import com.fireiptv.core.ui.ChannelLogo

/**
 * Full-screen live TV. Up/Down zap through the list the channel was opened from (CH+/CH- too),
 * Left/Right open that list at the side, OK shows the banner (or retries a failed channel),
 * Menu (☰) adds or removes a favourite, Play/Pause stops and restarts the stream, Back returns
 * to the list.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }

    val panelOpen = state.panel != null
    BackHandler(enabled = panelOpen) { viewModel.closePanel() }
    // Focus comes back to the picture when the panel closes, so Up/Down zap again.
    LaunchedEffect(panelOpen) { if (!panelOpen) focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    viewModel.togglePanel()
                    return@onKeyEvent true
                }
                // With the panel open, Up/Down/OK belong to its list.
                if (panelOpen) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelDown -> viewModel.zap(forward = false)
                    Key.DirectionDown, Key.ChannelUp -> viewModel.zap(forward = true)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        if (state.status is PlaybackStatus.Failed) viewModel.retry() else viewModel.toggleBanner()
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> viewModel.togglePlayback()
                    Key.Menu -> viewModel.toggleFavorite()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        // Keeps the last frame on screen while the next channel starts, instead of a black flash.
        ContentFrame(player = viewModel.player, modifier = Modifier.fillMaxSize(), keepContentOnReset = true)

        val status = state.status
        if (status is PlaybackStatus.Loading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .size(40.dp),
            )
        }
        if (status is PlaybackStatus.Failed || state.missing) {
            Message(
                title = if (state.missing) "This channel is gone" else "Can't play this channel",
                body = if (state.missing) {
                    "It was removed by a playlist refresh. Press Back to choose another."
                } else {
                    "${state.accountNote ?: (status as PlaybackStatus.Failed).message}\nOK to try again, Up/Down for another channel."
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (status is PlaybackStatus.WaitingForNetwork) {
            Message(
                title = "No internet connection",
                body = "The channel starts again by itself when the connection is back.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (status is PlaybackStatus.Idle && state.channel != null && !state.missing) {
            Message(title = "Stopped", body = "Press Play to resume.", modifier = Modifier.align(Alignment.Center))
        }

        state.panel?.let { panel ->
            ChannelPanel(
                panel = panel,
                channels = viewModel.panelChannels.collectAsLazyPagingItems(),
                onPlay = viewModel::playFromPanel,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        AnimatedVisibility(
            visible = state.bannerVisible && state.channel != null && !panelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            state.channel?.let { Banner(it, state.lastZapMs) }
        }
    }
}

@Composable
private fun Banner(channel: BannerChannel, lastZapMs: Long?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(channel.number.toString(), style = MaterialTheme.typography.displaySmall, color = Color.White)
            ChannelLogo(channel.logoUrl, Modifier.size(width = 96.dp, height = 54.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (channel.favorite) "★ ${channel.name}" else channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    channel.listTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Zap time, shown while phase 1 measures Gate 1 on the stick.
            lastZapMs?.let {
                Text("$it ms", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

/** The list being zapped, over the left of the picture; opens on the channel that's playing. */
@Composable
private fun ChannelPanel(
    panel: PanelState,
    channels: LazyPagingItems<PanelChannel>,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = remember(panel) { LazyListState(firstVisibleItemIndex = (panel.startIndex - 3).coerceAtLeast(0)) }
    val current = remember { FocusRequester() }
    LaunchedEffect(panel) {
        // Its page may still be loading: wait until the row is laid out, then focus it.
        repeat(PANEL_FOCUS_FRAMES) {
            if (listState.layoutInfo.visibleItemsInfo.any { it.key == panel.currentId }) {
                withFrameNanos {}
                current.requestFocus()
                return@LaunchedEffect
            }
            withFrameNanos {}
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxHeight()
            .width(480.dp)
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            panel.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().focusRestorer()) {
            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                val channel = channels[index]
                if (channel == null) {
                    DenseListItem(selected = false, onClick = {}, enabled = false, headlineContent = { Text("") })
                } else {
                    DenseListItem(
                        selected = channel.id == panel.currentId,
                        onClick = { onPlay(channel.id) },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(channel.number.toString(), maxLines = 1, modifier = Modifier.width(64.dp))
                                ChannelLogo(channel.logoUrl, Modifier.padding(end = 12.dp).size(width = 48.dp, height = 27.dp))
                                Text(
                                    if (channel.favorite) "★ ${channel.name}" else channel.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = if (channel.id == panel.currentId) Modifier.focusRequester(current) else Modifier,
                    )
                }
            }
        }
    }
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

private const val PANEL_FOCUS_FRAMES = 60
