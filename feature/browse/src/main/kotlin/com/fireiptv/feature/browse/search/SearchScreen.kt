package com.fireiptv.feature.browse.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.ui.ChannelLogo
import com.fireiptv.core.ui.LocalLogosEnabled
import com.fireiptv.core.ui.rememberScrollIdle
import com.fireiptv.core.ui.verticalDpadMovesFocus
import com.fireiptv.feature.browse.options.ChannelOptionsDialog
import androidx.compose.material3.Text as FieldText

/**
 * Search by channel name. Results follow the typing; OK plays one (Up/Down then zaps its group),
 * long-press OK opens favourite and category choices, Menu (☰) toggles a favourite.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (channelId: Long, list: ChannelList) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val listIdle by rememberScrollIdle(listState)
    var channelOptions by remember { mutableStateOf<Long?>(null) }

    // Back from the player: focus the channel that was playing. Otherwise: the search field.
    var returnTo by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        returnTo = viewModel.takeLastPlayed()
        if (returnTo == null) fieldFocus.requestFocus()
    }
    LaunchedEffect(returnTo, state.results, state.searched) {
        val target = returnTo ?: return@LaunchedEffect
        if (!state.searched) return@LaunchedEffect // results still on their way
        val index = state.results.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            // Wait until the row is laid out, so its focus requester is attached.
            repeat(RETURN_FOCUS_FRAMES) {
                if (listState.layoutInfo.visibleItemsInfo.any { it.key == target }) {
                    withFrameNanos {}
                    returnFocus.requestFocus()
                    returnTo = null
                    return@LaunchedEffect
                }
                withFrameNanos {}
            }
        }
        returnTo = null
        fieldFocus.requestFocus()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Text("Search", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { FieldText("Channel name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The keyboard's search key closes it and moves to the results.
            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .verticalDpadMovesFocus(),
        )

        val results = state.results
        Text(
            when {
                state.query.isBlank() -> "Type part of a channel name, for example \"bbc 1\"."
                !state.searched -> ""
                results.isEmpty() -> "No channels match \"${state.query.trim()}\"."
                results.size >= RESULT_LIMIT -> "Showing the first ${results.size}. Type more to narrow it down."
                results.size == 1 -> "1 channel"
                else -> "${results.size} channels"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
        ) {
            itemsIndexed(results, key = { _, result -> result.id }) { _, result ->
                ResultItem(
                    result,
                    onClick = { onPlayChannel(result.id, result.zapList) },
                    onToggleFavorite = { viewModel.toggleFavorite(result.id) },
                    onOptions = { channelOptions = result.id },
                    logosMayDownload = listIdle,
                    modifier = if (result.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
                )
            }
        }
    }
    channelOptions?.let { id -> ChannelOptionsDialog(channelId = id, onDismiss = { channelOptions = null }) }
}

@Composable
private fun ResultItem(
    result: SearchResult,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOptions: () -> Unit,
    logosMayDownload: Boolean,
    modifier: Modifier = Modifier,
) {
    // The group line sits under the name, past the number and (when shown) the logo.
    val groupIndent = if (LocalLogosEnabled.current && !result.logoUrl.isNullOrBlank()) 136.dp else 72.dp
    DenseListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onOptions,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.number.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
                ChannelLogo(
                    result.logoUrl,
                    Modifier.padding(end = 16.dp).size(width = 48.dp, height = 27.dp),
                    mayDownload = logosMayDownload,
                )
                Box(Modifier.weight(1f)) {
                    Text(result.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        supportingContent = result.groupTitle?.let { title ->
            { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = groupIndent)) }
        },
        trailingContent = if (result.favorite) {
            { Text("★") }
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    onToggleFavorite()
                    true
                } else {
                    false
                }
            },
    )
}

/** Matches ChannelRepository's cap on results. */
private const val RESULT_LIMIT = 300
private const val RETURN_FOCUS_FRAMES = 60
