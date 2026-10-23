package com.fireiptv.feature.settings.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    onOpenAccount: () -> Unit,
    appVersion: String,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Back from Playlists, Account or Groups lands on the row that opened it, not on the first row.
    var lastRow by rememberSaveable { mutableIntStateOf(ROW_PLAYLISTS) }
    val rows = remember { List(ROW_COUNT) { FocusRequester() } }
    LaunchedEffect(Unit) { rows[lastRow].requestFocus() }
    fun open(row: Int, action: () -> Unit) {
        lastRow = row
        action()
    }

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
            onClick = { open(ROW_PLAYLISTS, onOpenPlaylists) },
            modifier = rowModifier.focusRequester(rows[ROW_PLAYLISTS]),
        )
        if (state.hasAccount) {
            SettingRow(
                title = "Account",
                detail = "Status, expiry date and connections, from your provider",
                onClick = { open(ROW_ACCOUNT, onOpenAccount) },
                modifier = rowModifier.focusRequester(rows[ROW_ACCOUNT]),
            )
        }
        SettingRow(
            title = "Groups",
            detail = if (state.hiddenGroupCount == 0) {
                "All ${state.groupCount} groups shown. Hide the ones you never watch."
            } else {
                "${state.groupCount - state.hiddenGroupCount} of ${state.groupCount} groups shown"
            },
            onClick = { open(ROW_GROUPS, onOpenGroups) },
            modifier = rowModifier.focusRequester(rows[ROW_GROUPS]),
        )
        SettingRow(
            title = "Channel start: " + if (state.startMode == StartMode.FAST) "Fast" else "Stable",
            detail = if (state.startMode == StartMode.FAST) {
                "Channels start as soon as possible; on weak Wi-Fi they may pause. OK to switch to Stable."
            } else {
                "Channels start a second or two later, then pause less on weak Wi-Fi. OK to switch to Fast."
            },
            onClick = { open(ROW_START, viewModel::toggleStartMode) },
            modifier = rowModifier.focusRequester(rows[ROW_START]),
        )
        SettingRow(
            title = "Channel logos: " + if (state.logosEnabled) "On" else "Off",
            detail = if (state.logosEnabled) {
                "Each logo downloads once and is kept (up to 50 MB). OK to turn off: no logo downloads at all."
            } else {
                "No logos are shown or downloaded. OK to turn on."
            },
            onClick = { open(ROW_LOGOS, viewModel::toggleLogos) },
            modifier = rowModifier.focusRequester(rows[ROW_LOGOS]),
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

private const val ROW_PLAYLISTS = 0
private const val ROW_ACCOUNT = 1
private const val ROW_GROUPS = 2
private const val ROW_START = 3
private const val ROW_LOGOS = 4
private const val ROW_COUNT = 5
