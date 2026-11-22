package com.fireiptv.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/** A titled menu of [OptionRow]s, opened by a long press on OK. */
@Composable
fun OptionsDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // The menu opens while OK is still held (a long press). Its release would click the first row,
    // so OK counts only once a fresh press started inside the menu; held-key repeats don't count.
    var okPressedHere by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) okPressedHere = true
                        !okPressedHere
                    }
                    KeyEventType.KeyUp -> !okPressedHere
                    else -> false
                }
            },
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .width(560.dp)
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
fun OptionRow(
    title: String,
    modifier: Modifier = Modifier,
    mark: String? = null,
    focusFirst: Boolean = false,
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
