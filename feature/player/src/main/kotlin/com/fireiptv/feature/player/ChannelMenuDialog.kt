package com.fireiptv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.ui.OptionRow
import com.fireiptv.core.ui.OptionsDialog

/**
 * Long-press OK on a row of the side list: add to or remove from favourites and each of the user's
 * existing categories (the menu stays open, so several can change in one visit), or hide the
 * channel (closes the menu).
 * [onLeavingList] is called when the change takes the channel out of [panelList], the list behind
 * the menu, so the panel can move focus off its row before it disappears.
 */
@Composable
fun ChannelMenuDialog(
    channelId: Long,
    panelList: ChannelList,
    onLeavingList: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: ChannelMenuViewModel = hiltViewModel(),
) {
    LaunchedEffect(channelId) { viewModel.open(channelId) }
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val current = menu?.takeIf { it.channelId == channelId }

    OptionsDialog(title = current?.name.orEmpty(), onDismiss = onDismiss) {
        if (current == null) return@OptionsDialog
        OptionRow("Favourites", mark = if (current.favorite) "★" else null, focusFirst = true) {
            if (current.favorite && panelList == ChannelList.Favorites) onLeavingList()
            viewModel.toggleFavorite()
        }
        current.categories.forEach { category ->
            OptionRow(category.title, mark = if (category.contains) "✓" else null) {
                if (category.contains && panelList == ChannelList.Custom(category.id)) onLeavingList()
                viewModel.toggleCategory(category.id)
            }
        }
        OptionRow("Hide channel") {
            viewModel.hide()
            onLeavingList()
            onDismiss()
        }
    }
}
