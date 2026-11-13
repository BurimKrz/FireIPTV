package com.fireiptv.core.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/** Dark TV theme; also themes the Material 3 text fields used for input. */
@Composable
fun FireTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        M3Theme(colorScheme = m3DarkColorScheme(), content = content)
    }
}
