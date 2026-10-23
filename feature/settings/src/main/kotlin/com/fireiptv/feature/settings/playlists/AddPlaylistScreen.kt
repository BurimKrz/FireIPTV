package com.fireiptv.feature.settings.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.Text as FieldText

@Composable
fun AddPlaylistScreen(
    onAdded: () -> Unit,
    prefillUrl: String? = null,
    viewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { if (state.added) onAdded() }

    var url by rememberSaveable { mutableStateOf(prefillUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }

    // With a prefilled URL there is nothing to type, so start on the Import button.
    val urlFocus = remember { FocusRequester() }
    val importFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (url.isEmpty()) urlFocus.requestFocus() else importFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add a playlist", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Enter the URL of your M3U playlist or your provider's get.php link. The app includes no channels; it plays the playlists you provide.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val fieldModifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { FieldText("Playlist URL") },
            singleLine = true,
            isError = state.urlError != null,
            supportingText = state.urlError?.let { { FieldText(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = fieldModifier.focusRequester(urlFocus),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { FieldText("Name (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = fieldModifier,
        )
        Button(
            onClick = { viewModel.submit(name, url) },
            enabled = !state.saving,
            modifier = Modifier.focusRequester(importFocus),
        ) {
            Text(if (state.saving) "Saving…" else "Import")
        }
    }
}
