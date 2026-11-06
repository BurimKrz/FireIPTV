package com.fireiptv.feature.settings.log

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.log.LogEntry

/** Crashes and player trouble kept on the device, newest first; rows are focusable so the list scrolls. */
@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Log", style = MaterialTheme.typography.headlineMedium)
            Text(
                summary(entries),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // A stray press shouldn't wipe what may explain a problem: ask for a second press.
            var armed by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (armed) viewModel.clear()
                    armed = !armed
                },
                enabled = entries.isNotEmpty(),
                modifier = Modifier
                    .onFocusChanged { if (!it.isFocused) armed = false }
                    .then(if (entries.isEmpty()) Modifier.focusRequester(first) else Modifier),
            ) {
                Text(if (armed) "Press again to clear" else "Clear")
            }
        }
        Text(
            "Crashes, channels that failed and automatic retries, kept on this device (the newest 100). Links are hidden, so no login is stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .widthIn(max = 1200.dp)
                .fillMaxSize()
                .focusRestorer(),
        ) {
            items(entries, key = { "${it.time}-${it.kind}-${it.message.hashCode()}" }) { entry ->
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = {
                        Text(
                            "${label(entry.kind)} · " + DateUtils.formatDateTime(
                                context,
                                entry.time,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                            ),
                        )
                    },
                    supportingContent = {
                        Text(
                            entry.message,
                            style = if (entry.kind == LogEntry.Kind.CRASH) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (entry == entries.first()) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

/** For the Settings row and this screen's header: "1 crash, 2 errors, 5 retries". */
fun summary(entries: List<LogEntry>): String {
    if (entries.isEmpty()) return "Nothing recorded"
    val counts = entries.groupingBy { it.kind }.eachCount()
    return listOfNotNull(
        counts[LogEntry.Kind.CRASH]?.let { if (it == 1) "1 crash" else "$it crashes" },
        counts[LogEntry.Kind.ERROR]?.let { if (it == 1) "1 error" else "$it errors" },
        counts[LogEntry.Kind.RETRY]?.let { if (it == 1) "1 retry" else "$it retries" },
    ).joinToString(", ")
}

private fun label(kind: LogEntry.Kind) = when (kind) {
    LogEntry.Kind.CRASH -> "Crash"
    LogEntry.Kind.ERROR -> "Error"
    LogEntry.Kind.RETRY -> "Retry"
}
