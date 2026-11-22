package com.fireiptv.feature.browse.options

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.ui.OptionRow
import com.fireiptv.core.ui.OptionsDialog
import com.fireiptv.core.ui.verticalDpadMovesFocus
import com.fireiptv.feature.browse.GroupRow
import androidx.compose.material3.Text as FieldText

/**
 * Long-press OK on a channel: favourite, each of the user's categories, and a new one. Every row
 * toggles and the menu stays open, so a channel can go into several categories in one visit.
 * Opened from favourites or a category ([list]), it can also move the channel up or down there.
 * Hide closes the menu, since the channel leaves the list behind it.
 */
@Composable
fun ChannelOptionsDialog(
    channelId: Long,
    onDismiss: () -> Unit,
    list: ChannelList? = null,
    /** Called when Hide took the channel out of the list behind the menu. */
    onHidden: () -> Unit = {},
    viewModel: ChannelOptionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(channelId) { viewModel.open(channelId) }
    val options by viewModel.options.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val current = options?.takeIf { it.channelId == channelId }

    OptionsDialog(title = current?.name.orEmpty(), onDismiss = onDismiss) {
        when {
            current == null -> Unit
            naming -> NameEntry(label = "New category", initial = "", action = "Create and add") { title ->
                viewModel.createList(title)
                naming = false
            }
            else -> {
                OptionRow("Favourites", mark = if (current.favorite) "★" else null, focusFirst = true, onClick = viewModel::toggleFavorite)
                current.lists.forEach { option ->
                    OptionRow(option.title, mark = if (option.contains) "✓" else null) { viewModel.toggleList(option.id) }
                }
                OptionRow("New category…") { naming = true }
                if (list == ChannelList.Favorites || list is ChannelList.Custom) {
                    OptionRow("Move up") { viewModel.move(list, up = true) }
                    OptionRow("Move down") { viewModel.move(list, up = false) }
                }
                if (current.hidden) {
                    OptionRow("Show in lists again", mark = "Hidden", onClick = viewModel::toggleHidden)
                } else {
                    OptionRow("Hide channel") {
                        viewModel.toggleHidden()
                        onHidden()
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** Long-press OK on a group or category: pin it, and for the user's own, rename or delete it. */
@Composable
fun GroupOptionsDialog(
    row: GroupRow,
    onTogglePinned: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val custom = row.list is ChannelList.Custom
    var naming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    OptionsDialog(title = row.title, onDismiss = onDismiss) {
        if (naming) {
            NameEntry(label = "Category name", initial = row.title, action = "Rename") { title ->
                onRename(title)
                onDismiss()
            }
            return@OptionsDialog
        }
        OptionRow(if (row.pinned) "Unpin from the top" else "Pin to the top", focusFirst = true) {
            onTogglePinned()
            onDismiss()
        }
        if (custom) {
            OptionRow("Rename…") { naming = true }
            // A stray press shouldn't lose a category: ask for a second press, like playlist Delete.
            OptionRow(
                if (confirmDelete) "Press again to delete" else "Delete category",
                modifier = Modifier.onFocusChanged { if (!it.isFocused) confirmDelete = false },
            ) {
                if (confirmDelete) {
                    onDelete()
                    onDismiss()
                } else {
                    confirmDelete = true
                }
            }
        }
    }
}

/** The Playlists button on home: pick the playlist home shows, or add one. */
@Composable
fun PlaylistPickerDialog(
    playlists: List<PlaylistSummary>,
    activeId: Long?,
    onPick: (Long) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    OptionsDialog(title = "Playlists", onDismiss = onDismiss) {
        playlists.forEach { playlist ->
            val active = playlist.id == activeId
            OptionRow(
                playlist.name,
                mark = if (active) "✓" else "${"%,d".format(playlist.channelCount)} channels",
                focusFirst = active,
            ) {
                onPick(playlist.id)
                onDismiss()
            }
        }
        OptionRow("Add playlist…", focusFirst = activeId == null) {
            onDismiss()
            onAdd()
        }
        if (playlists.isNotEmpty()) {
            OptionRow("Edit playlists…") {
                onDismiss()
                onEdit()
            }
        }
    }
}

@Composable
private fun NameEntry(label: String, initial: String, action: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    // The field leaves the screen once the name is saved: close the keyboard first, so it isn't
    // left attached to a field that no longer exists (the emulator's keyboard then kept the D-pad).
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (name.isNotBlank()) {
            keyboard?.hide()
            onDone(name.trim())
        }
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { FieldText(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().verticalDpadMovesFocus().focusRequester(field),
    )
    Button(onClick = submit, enabled = name.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text(action) }
}
