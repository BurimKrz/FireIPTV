package com.fireiptv.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Whether channel logos are shown; set from the app's setting. Off: no logo requests at all. */
val LocalLogosEnabled = compositionLocalOf { true }

/**
 * A channel's logo, fitted into [modifier]'s size and decoded at that size. Renders nothing when
 * logos are off or the channel has none.
 *
 * Data: a logo already on disk shows at once. One that isn't is downloaded only when [mayDownload]
 * (in lists: the list has stopped moving, see [rememberScrollIdle]) and after it has been on screen
 * for [SETTLE_MS], so channels passed on the way to another are never fetched.
 */
@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier, mayDownload: Boolean = true) {
    if (!LocalLogosEnabled.current || url.isNullOrBlank()) return

    var notCached by remember(url) { mutableStateOf(false) }
    var settled by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        delay(SETTLE_MS)
        settled = true
    }
    // Once a download starts it is left to finish, even if the list moves again.
    var useNetwork by remember(url) { mutableStateOf(false) }
    if (notCached && settled && mayDownload) useNetwork = true

    val context = LocalPlatformContext.current
    val request = remember(url, useNetwork) {
        ImageRequest.Builder(context)
            .data(url)
            .networkCachePolicy(if (useNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }
    Box(modifier) {
        // AsyncImage compares requests by data and cache keys only, so a request that differs just in
        // its network policy would be ignored: a new key gives the network try its own image.
        key(useNetwork) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // The first, cache-only try fails for a logo not downloaded yet: allow the network then.
                onError = { if (!useNetwork) notCached = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * True once [state] has not moved for [idleMs]. Holding the D-pad keeps a list moving, so logos for
 * the rows it passes stay off the network.
 */
@Composable
fun rememberScrollIdle(state: LazyListState, idleMs: Long = 400): State<Boolean> {
    val idle = remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.isScrollInProgress }.collectLatest { (_, scrolling) ->
            idle.value = false
            if (!scrolling) {
                delay(idleMs)
                idle.value = true
            }
        }
    }
    return idle
}

private const val SETTLE_MS = 300L
