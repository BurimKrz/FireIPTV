package com.fireiptv.feature.settings.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.data.playlist.parseXtreamLogin
import com.fireiptv.core.ui.verticalDpadMovesFocus
import com.fireiptv.feature.settings.backup.rememberRestoreAction
import androidx.compose.material3.Text as FieldText

/**
 * Add a playlist: an Xtream login (server, username, password) first, since that imports live
 * channels only; an M3U link for any other playlist. A get.php link sent over adb fills the login.
 */
@Composable
fun AddPlaylistScreen(
    onAdded: () -> Unit,
    prefillUrl: String? = null,
    viewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { if (state.added) onAdded() }
    val restore = rememberRestoreAction(viewModel::restore)

    val prefillLogin = remember(prefillUrl) { prefillUrl?.let(::parseXtreamLogin) }
    var useM3u by rememberSaveable { mutableStateOf(prefillUrl != null && prefillLogin == null) }
    var server by rememberSaveable { mutableStateOf(prefillLogin?.server.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(prefillLogin?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(prefillLogin?.password.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(if (prefillLogin == null) prefillUrl.orEmpty() else "") }
    var name by rememberSaveable { mutableStateOf("") }

    // With everything filled in (sent over adb) there is nothing to type, so start on Import.
    val firstField = remember { FocusRequester() }
    val importFocus = remember { FocusRequester() }
    LaunchedEffect(useM3u) {
        val filled = if (useM3u) url.isNotEmpty() else server.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
        if (filled) importFocus.requestFocus() else firstField.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add a playlist", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (useM3u) {
                "Enter the link of an M3U playlist. A provider's get.php link works too and is read as an Xtream login."
            } else {
                "Enter your provider's Xtream login. Only live channels are loaded (under 1 MB). The app includes no " +
                    "channels; it plays the playlists you provide."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val fieldModifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalDpadMovesFocus()
        // Down from the last field goes to Import: by distance alone it would pick the wider Restore button.
        val lastField = fieldModifier.focusProperties { down = importFocus }
        if (useM3u) {
            Field("Playlist URL", url, { url = it }, state.urlError, KeyboardType.Uri, fieldModifier.focusRequester(firstField))
            Field("Name (optional)", name, { name = it }, null, KeyboardType.Text, lastField, ImeAction.Done)
        } else {
            Field("Server, e.g. http://provider.com:8080", server, { server = it }, state.serverError, KeyboardType.Uri, fieldModifier.focusRequester(firstField))
            Field("Username", username, { username = it }, state.usernameError, KeyboardType.Text, fieldModifier)
            Field(
                "Password",
                password,
                { password = it },
                state.passwordError,
                KeyboardType.Password,
                lastField,
                ImeAction.Done,
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val busy = state.saving || state.restoring
            Button(
                onClick = { if (useM3u) viewModel.submitM3u(name, url) else viewModel.submitXtream(server, username, password) },
                enabled = !busy,
                modifier = Modifier.focusRequester(importFocus),
            ) {
                Text(if (state.saving) "Saving…" else "Import")
            }
            if (!useM3u) {
                OutlinedButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                    Text(if (showPassword) "Hide password" else "Show password")
                }
            }
            OutlinedButton(onClick = restore, enabled = !busy) {
                Text(if (state.restoring) "Restoring… (loading the playlist)" else "Restore from backup")
            }
        }
        Text(
            state.restoreError ?: "Reinstalled? Restore from backup brings back the playlist, favourites and settings " +
                "saved with Settings → Back up.",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.restoreError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { useM3u = !useM3u }, enabled = !state.saving && !state.restoring) {
            Text(if (useM3u) "Use an Xtream login instead" else "Use an M3U link instead")
        }
    }
}

/** A one-line form field; shared with the edit screen. */
@Composable
internal fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    keyboardType: KeyboardType,
    modifier: Modifier,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { FieldText(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { FieldText(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        visualTransformation = visualTransformation,
        modifier = modifier,
    )
}
