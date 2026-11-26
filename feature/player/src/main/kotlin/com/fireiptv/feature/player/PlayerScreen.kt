package com.fireiptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
 * Left/Right open that list at the side (holding OK on one of its rows opens that channel's
 * favourite, categories and Hide), OK shows the banner (or retries a failed channel; when
 * the failed channel has other versions, Up/Down pick one of them or Try again instead),
 * Menu (☰) adds or removes a favourite, Play/Pause stops and restarts the stream, Fast-forward (⏩)
 * sets the sleep timer, Back returns to the list.
 */
// ContentFrame's keepContentOnReset is marked unstable in Media3.
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The TV may dim and sleep once nothing streams, e.g. after "Still watching?" went unanswered.
    val view = LocalView.current
    val streaming = state.status !is PlaybackStatus.Idle && state.status !is PlaybackStatus.Failed
    DisposableEffect(view, streaming) {
        view.keepScreenOn = streaming
        onDispose { view.keepScreenOn = false }
    }
    // With Settings → Match frame rate on, 25/50 fps channels play at 50 Hz and 30/60 fps at 60 Hz, so every
    // frame stays on screen equally long. Zaps keep the last rate until the new channel is measured; leaving
    // gives the choice back to the TV.
    val activity = checkNotNull(LocalActivity.current) { "The player screen must be shown in an activity" }
    val frameRate by viewModel.frameRate.collectAsStateWithLifecycle()
    LaunchedEffect(frameRate) {
        if (!viewModel.matchFrameRate) return@LaunchedEffect
        val refreshRate = frameRate?.let(::refreshRateFor) ?: return@LaunchedEffect
        val display = checkNotNull(view.display) { "The player screen isn't attached to a display" }
        val mode = displayModeFor(display.supportedModes, display.mode, refreshRate) ?: return@LaunchedEffect
        if (mode.modeId == display.mode.modeId) return@LaunchedEffect
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }
    DisposableEffect(activity) {
        onDispose {
            val attributes = activity.window.attributes
            if (attributes.preferredDisplayModeId != 0) {
                activity.window.attributes = attributes.apply { preferredDisplayModeId = 0 }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }

    val panelOpen = state.panel != null
    // A failed channel's choices: 0 is Try again, then its other versions. The first other
    // version is picked to start with, so one OK switches to it.
    val versions = state.otherVersions
    val choosing = state.status is PlaybackStatus.Failed && versions.isNotEmpty() && !panelOpen
    var choice by remember(versions) { mutableIntStateOf(if (versions.isEmpty()) 0 else 1) }
    BackHandler(enabled = panelOpen) { viewModel.closePanel() }
    // Focus comes back to the picture when the panel closes, so Up/Down zap again.
    LaunchedEffect(panelOpen) { if (!panelOpen) focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            // Sees every press first, also those meant for the side panel: any key counts as someone watching.
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && viewModel.onKey() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (choosing) {
                    when (event.key) {
                        Key.DirectionUp -> choice = (choice - 1).coerceAtLeast(0)
                        Key.DirectionDown -> choice = (choice + 1).coerceAtMost(versions.size)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            if (choice == 0) viewModel.retry() else viewModel.playVersion(versions[choice - 1].id)
                        else -> null
                    }?.let { return@onKeyEvent true }
                }
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
                        if (state.status is PlaybackStatus.Failed || (state.status is PlaybackStatus.Idle && state.channel != null)) {
                            viewModel.retry()
                        } else {
                            viewModel.toggleBanner()
                        }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> viewModel.togglePlayback()
                    Key.MediaFastForward -> viewModel.cycleSleepTimer()
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
        if (status is PlaybackStatus.Failed && !state.missing && versions.isNotEmpty()) {
            VersionsMessage(
                reason = state.accountNote ?: status.message,
                versions = versions,
                choice = choice,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (status is PlaybackStatus.Failed || state.missing) {
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
            Message(
                title = "Stopped",
                body = listOfNotNull(state.extras.stopReason, "Press OK or Play to resume.").joinToString("\n"),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        state.extras.stillWatchingSeconds?.let { seconds ->
            Message(
                title = "Still watching?",
                body = "Press any key to keep watching. Otherwise the stream stops in $seconds s to save data.",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        state.panel?.let { panel ->
            ChannelPanel(
                panel = panel,
                channels = viewModel.panelChannels.collectAsLazyPagingItems(),
                onPlay = viewModel::playFromPanel,
                onClose = viewModel::closePanel,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        AnimatedVisibility(
            visible = state.bannerVisible && state.channel != null && !panelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            state.channel?.let { Banner(it, state.extras, state.lastZapMs) }
        }
    }
}

@Composable
private fun Banner(channel: BannerChannel, extras: PlayerExtras, lastZapMs: Long?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
            Column(horizontalAlignment = Alignment.End) {
                extras.sleepMinutesLeft?.let {
                    Text("Sleep in $it min", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                extras.streamInfo?.let {
                    // The screen's refresh rate next to the channel's frame rate, e.g. "25 fps · 1.3 Mb/s · 50 Hz".
                    val refreshRate = checkNotNull(LocalView.current.display) { "The banner isn't attached to a display" }.mode.refreshRate
                    Text("$it · %.0f Hz".format(refreshRate), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                }
                // Zap time, shown until Gate 1 is measured on the stick.
                lastZapMs?.let {
                    Text("$it ms", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                }
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = remember(panel) { LazyListState(firstVisibleItemIndex = (panel.startIndex - 3).coerceAtLeast(0)) }
    // The row to focus: the playing channel at first, then the neighbour of a row that left the list.
    var focusId by remember(panel) { mutableLongStateOf(panel.currentId) }
    val focusRow = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(panel, focusId) {
        // Its page may still be loading: wait until the row is laid out, then focus it.
        repeat(PANEL_FOCUS_FRAMES) {
            if (listState.layoutInfo.visibleItemsInfo.any { it.key == focusId }) {
                withFrameNanos {}
                focusRow.requestFocus()
                return@LaunchedEffect
            }
            withFrameNanos {}
        }
    }
    menuFor?.let { id ->
        ChannelMenuDialog(
            channelId = id,
            panelList = panel.list,
            // Focus the row that takes its place; with none left, close the panel so focus returns to the picture.
            onLeavingList = {
                val rows = channels.itemSnapshotList
                val index = rows.indexOfFirst { it?.id == id }
                val next = rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
                if (next == null) onClose() else focusId = next.id
            },
            onDismiss = { menuFor = null },
        )
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
                        onLongClick = { menuFor = channel.id },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChannelLogo(channel.logoUrl, Modifier.padding(end = 12.dp).size(width = 48.dp, height = 27.dp))
                                Text(
                                    if (channel.favorite) "★ ${channel.name}" else channel.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = if (channel.id == focusId) Modifier.focusRequester(focusRow) else Modifier,
                    )
                }
            }
        }
    }
}

/** A failed channel with other versions: Try again, or one of them, picked with Up/Down. */
@Composable
private fun VersionsMessage(reason: String, versions: List<OtherVersion>, choice: Int, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .widthIn(max = 720.dp)
            .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.medium)
            .padding(24.dp),
    ) {
        Text("Can't play this channel", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        Text(
            "Other versions of it below: Up/Down to choose, OK to play. Left/Right for the channel list.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
            (listOf("Try again") + versions.map { it.name }).forEachIndexed { index, label ->
                val picked = index == choice
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (picked) Color.Black else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (picked) Color.White else Color.Transparent, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
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
