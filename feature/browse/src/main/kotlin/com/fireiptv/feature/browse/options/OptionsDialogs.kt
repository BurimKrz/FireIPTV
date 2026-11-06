package com.fireiptv.feature.browse.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.ui.verticalDpadMovesFocus
import com.fireiptv.feature.browse.GroupRow
import androidx.compose.material3.Text as FieldText

/**
 * Long-press OK on a channel: favourite, each of the user's categories, and a new one. Every row
 * toggles and the menu stays open, so a channel can go into several categories in one visit.
 */
@Composable
fun ChannelOptionsDialog(channelId: Long, onDismiss: () -> Unit, viewModel: ChannelOptionsViewModel = hiltViewModel()) {
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
                current.lists.forEach { list ->
                    OptionRow(list.title, mark = if (list.contains) "✓" else null) { viewModel.toggleList(list.id) }
                }
                OptionRow("New category…") { naming = true }
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

@Composable
private fun OptionsDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.width(560.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    mark: String? = null,
    focusFirst: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { focus.requestFocus() }
    DenseListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = mark?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun NameEntry(label: String, initial: String, action: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val submit = { if (name.isNotBlank()) onDone(name.trim()) }
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
