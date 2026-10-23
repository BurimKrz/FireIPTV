package com.fireiptv.feature.settings.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fireiptv.core.data.settings.StartMode

@Composable
fun SettingsScreen(
    onOpenPlaylists: () -> Unit,
    onOpenGroups: () -> Unit,
    appVersion: String,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRow.requestFocus() }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))

        val rowModifier = Modifier.widthIn(max = 900.dp)
        SettingRow(
            title = "Playlists",
            detail = when (state.playlistCount) {
                0 -> "No playlist yet"
                1 -> "1 playlist · ${"%,d".format(state.channelCount)} channels"
                else -> "${state.playlistCount} playlists · ${"%,d".format(state.channelCount)} channels"
            },
            onClick = onOpenPlaylists,
            modifier = rowModifier.focusRequester(firstRow),
        )
        SettingRow(
            title = "Groups",
            detail = if (state.hiddenGroupCount == 0) {
                "All ${state.groupCount} groups shown. Hide the ones you never watch."
            } else {
                "${state.groupCount - state.hiddenGroupCount} of ${state.groupCount} groups shown"
            },
            onClick = onOpenGroups,
            modifier = rowModifier,
        )
        SettingRow(
            title = "Channel start: " + if (state.startMode == StartMode.FAST) "Fast" else "Stable",
            detail = if (state.startMode == StartMode.FAST) {
                "Channels start as soon as possible; on weak Wi-Fi they may pause. OK to switch to Stable."
            } else {
                "Channels start a second or two later, then pause less on weak Wi-Fi. OK to switch to Fast."
            },
            onClick = viewModel::toggleStartMode,
            modifier = rowModifier,
        )
        Text(
            "FireIPTV $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp, start = 16.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = modifier,
    )
}
