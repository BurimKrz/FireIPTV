package com.fireiptv.feature.settings.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.fireiptv.core.ui.verticalDpadMovesFocus

/** Rename a playlist, or change its Xtream login or M3U link. A changed login or link reloads the channels. */
@Composable
fun EditPlaylistScreen(
    onDone: () -> Unit,
    viewModel: EditPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved, state.missing) { if (state.saved || state.missing) onDone() }
    val form = state.form ?: return

    var name by rememberSaveable { mutableStateOf(form.name) }
    var useM3u by rememberSaveable { mutableStateOf(form.login == null) }
    var server by rememberSaveable { mutableStateOf(form.login?.server.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(form.login?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(form.login?.password.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(if (form.login == null) form.url else "") }

    val nameFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Edit playlist", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Changing the login or link loads the channels again. Favourites and categories stay.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val fieldModifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalDpadMovesFocus()
        // Down from the last field goes to Save: by distance alone it would pick Cancel, nearer the middle.
        val lastField = fieldModifier.focusProperties { down = saveFocus }
        Field("Name", name, { name = it }, null, KeyboardType.Text, fieldModifier.focusRequester(nameFocus))
        if (useM3u) {
            Field("Playlist URL", url, { url = it }, state.urlError, KeyboardType.Uri, lastField, ImeAction.Done)
        } else {
            Field("Server, e.g. http://provider.com:8080", server, { server = it }, state.serverError, KeyboardType.Uri, fieldModifier)
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
            Button(
                onClick = {
                    if (useM3u) viewModel.saveM3u(name, url) else viewModel.saveXtream(name, server, username, password)
                },
                enabled = !state.saving,
                modifier = Modifier.focusRequester(saveFocus),
            ) {
                Text(if (state.saving) "Saving…" else "Save")
            }
            if (!useM3u) {
                OutlinedButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide password" else "Show password")
                }
            }
            OutlinedButton(onClick = onDone, enabled = !state.saving) { Text("Cancel") }
        }
        OutlinedButton(onClick = { useM3u = !useM3u }, enabled = !state.saving) {
            Text(if (useM3u) "Use an Xtream login instead" else "Use an M3U link instead")
        }
    }
}
